package com.selfhealing.gateway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Autonomy: after repeated WAF false-positive signals (detect-mode matches with
 * subsequent 2xx), propose allow-list entries into Redis for operator review.
 * Never auto-writes production WAF block rules without confidence gate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WafDriftHealer {

    private final StringRedisTemplate stringRedisTemplate;
    private final JdbcTemplate jdbcTemplate;

    @Scheduled(fixedDelayString = "${mendr.autonomy.waf-heal-ms:600000}")
    public void proposeAllowlistFixes() {
        if (!"true".equalsIgnoreCase(System.getenv().getOrDefault("MENDR_WAF_AUTO_HEAL", "false"))) {
            return;
        }
        try {
            // Edge writes mendr:waf:fp:{ruleId} counters when detect-mode matches clear later
            var keys = stringRedisTemplate.keys("mendr:waf:fp:*");
            if (keys == null || keys.isEmpty()) return;
            for (String key : keys) {
                String count = stringRedisTemplate.opsForValue().get(key);
                long n = count != null ? Long.parseLong(count) : 0;
                if (n < 20) continue;
                String ruleId = key.substring("mendr:waf:fp:".length());
                String proposalKey = "mendr:waf:proposal:" + ruleId;
                stringRedisTemplate.opsForValue().set(proposalKey,
                        "{\"ruleId\":\"" + ruleId + "\",\"falsePositives\":" + n
                                + ",\"action\":\"review_allowlist\",\"confidence\":0.7}");
                log.info("WAF heal proposal for rule {} after {} false-positive signals", ruleId, n);
            }
        } catch (Exception e) {
            log.debug("WAF drift healer skipped: {}", e.getMessage());
        }

        // Optional: surface recent detect-mode findings from api_failures message patterns
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    SELECT error_message, COUNT(*) AS cnt FROM api_failures
                    WHERE detected_at > now() - interval '1 day'
                      AND error_message LIKE 'WAF%'
                    GROUP BY 1 HAVING COUNT(*) >= 10 LIMIT 10
                    """);
            for (Map<String, Object> row : rows) {
                log.debug("WAF failure cluster: {} × {}", row.get("cnt"), row.get("error_message"));
            }
        } catch (Exception e) {
            log.debug("WAF failure cluster scan skipped: {}", e.getMessage());
        }
    }
}
