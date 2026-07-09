package com.selfhealing.analysis.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.selfhealing.analysis.dto.AnalysisConversationDto;
import com.selfhealing.analysis.dto.AnalysisConversationMessageDto;
import com.selfhealing.analysis.dto.AppendConversationMessagesRequest;
import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.repository.AnalysisResultRepository;
import com.selfhealing.analysis.tenant.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
public class AnalysisConversationService {

    static final int MAX_STORED_MESSAGES = 20;
    private static final int MAX_CONTENT_CHARS = 8_000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Pattern[] SECRET_PATTERNS = new Pattern[]{
            Pattern.compile("\\bsk-[A-Za-z0-9]{16,}\\b"),
            Pattern.compile("\\bmendr_[A-Za-z0-9._-]{12,}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9._-]+\\b"),
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._-]{16,}\\b")
    };

    private final JdbcTemplate jdbcTemplate;
    private final AnalysisResultRepository analysisRepository;
    private final ObjectMapper objectMapper;
    private final AnalysisConversationService self;

    public AnalysisConversationService(
            JdbcTemplate jdbcTemplate,
            AnalysisResultRepository analysisRepository,
            ObjectMapper objectMapper,
            @Lazy AnalysisConversationService self) {
        this.jdbcTemplate = jdbcTemplate;
        this.analysisRepository = analysisRepository;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public AnalysisConversationDto getOrCreateConversation(UUID analysisId, int limit) {
        AnalysisResult analysis = requireAnalysis(analysisId);
        ConversationRow row = findConversationRow(analysis.getId())
                .orElseGet(() -> self.createConversationRow(analysis));
        return toDto(analysis, row, Math.min(Math.max(limit, 1), MAX_STORED_MESSAGES));
    }

    @Transactional
    public AnalysisConversationDto appendMessages(UUID analysisId, AppendConversationMessagesRequest request) {
        AnalysisResult analysis = requireAnalysis(analysisId);
        if (analysis.getStatus() != AnalysisResult.AnalysisStatus.PENDING_APPROVAL) {
            throw new IllegalStateException("chat is read-only after approval or rejection");
        }

        ConversationRow row = findConversationRow(analysis.getId())
                .orElseGet(() -> self.createConversationRow(analysis));
        List<AppendConversationMessagesRequest.MessageInput> inputs =
                request != null && request.getMessages() != null ? request.getMessages() : List.of();

        int nextSeq = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) + 1 FROM analysis_conversation_messages WHERE conversation_id = ?::uuid",
                Integer.class, row.id().toString());

        for (AppendConversationMessagesRequest.MessageInput input : inputs) {
            if (input == null) continue;
            String role = normalizeRole(input.getRole());
            String content = sanitizeContent(input.getContent());
            if (content == null || content.isBlank()) continue;

            jdbcTemplate.update("""
                    INSERT INTO analysis_conversation_messages
                        (id, conversation_id, tenant_id, seq, role, content, metadata, created_at)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, NOW())
                    """,
                    UUID.randomUUID().toString(),
                    row.id().toString(),
                    TenantContext.currentOrDefault().toString(),
                    nextSeq++,
                    role,
                    content,
                    json(input.getMetadata()));
        }

        trimOldMessages(row.id(), MAX_STORED_MESSAGES);

        jdbcTemplate.update("""
                UPDATE analysis_conversations
                   SET last_result = COALESCE(?::jsonb, last_result),
                       updated_at = NOW()
                 WHERE id = ?::uuid
                """,
                json(request == null ? null : request.getLastResult()),
                row.id().toString());

        return toDto(analysis, reloadConversationRow(row.id()), MAX_STORED_MESSAGES);
    }

    void trimOldMessages(UUID conversationId, int maxMessages) {
        jdbcTemplate.update("""
                DELETE FROM analysis_conversation_messages
                 WHERE conversation_id = ?::uuid
                   AND seq <= COALESCE((
                       SELECT MAX(seq) - ? FROM analysis_conversation_messages WHERE conversation_id = ?::uuid
                   ), -1)
                """,
                conversationId.toString(), maxMessages, conversationId.toString());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    ConversationRow createConversationRow(AnalysisResult analysis) {
        String sessionId = UUID.randomUUID().toString();
        UUID tenantId = TenantContext.currentOrDefault();
        try {
            jdbcTemplate.update("""
                    INSERT INTO analysis_conversations
                        (id, analysis_id, tenant_id, session_id, created_at, updated_at)
                    VALUES (?::uuid, ?::uuid, ?::uuid, ?, NOW(), NOW())
                    """,
                    UUID.randomUUID().toString(),
                    analysis.getId().toString(),
                    tenantId.toString(),
                    sessionId);
        } catch (DataIntegrityViolationException e) {
            log.debug("Analysis conversation already created for {}: {}", analysis.getId(), e.getMessage());
        }
        return reloadConversationRowByAnalysis(analysis.getId());
    }

    private AnalysisResult requireAnalysis(UUID analysisId) {
        return analysisRepository.findById(analysisId)
                .orElseThrow(() -> new NoSuchElementException("analysis not found"));
    }

    private AnalysisConversationDto toDto(AnalysisResult analysis, ConversationRow row, int limit) {
        List<AnalysisConversationMessageDto> messages = jdbcTemplate.query("""
                SELECT id, role, content, metadata, created_at
                  FROM analysis_conversation_messages
                 WHERE conversation_id = ?::uuid
                 ORDER BY seq DESC
                 LIMIT ?
                """, (rs, ignored) -> mapMessage(rs), row.id().toString(), limit);
        java.util.Collections.reverse(messages);

        return AnalysisConversationDto.builder()
                .id(row.id())
                .analysisId(analysis.getId())
                .sessionId(row.sessionId())
                .chatEnabled(analysis.getStatus() == AnalysisResult.AnalysisStatus.PENDING_APPROVAL)
                .lastResult(row.lastResult())
                .messages(messages)
                .createdAt(row.createdAt())
                .updatedAt(row.updatedAt())
                .build();
    }

    private java.util.Optional<ConversationRow> findConversationRow(UUID analysisId) {
        List<ConversationRow> rows = jdbcTemplate.query("""
                SELECT id, analysis_id, session_id, last_result, created_at, updated_at
                  FROM analysis_conversations
                 WHERE analysis_id = ?::uuid
                """, (rs, ignored) -> mapConversation(rs), analysisId.toString());
        return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
    }

    private ConversationRow reloadConversationRow(UUID id) {
        return jdbcTemplate.query("""
                SELECT id, analysis_id, session_id, last_result, created_at, updated_at
                  FROM analysis_conversations
                 WHERE id = ?::uuid
                """, (rs, ignored) -> mapConversation(rs), id.toString()).stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("conversation not found"));
    }

    private ConversationRow reloadConversationRowByAnalysis(UUID analysisId) {
        return jdbcTemplate.query("""
                SELECT id, analysis_id, session_id, last_result, created_at, updated_at
                  FROM analysis_conversations
                 WHERE analysis_id = ?::uuid
                """, (rs, ignored) -> mapConversation(rs), analysisId.toString()).stream().findFirst()
                .orElseThrow(() -> new NoSuchElementException("conversation not found"));
    }

    private ConversationRow mapConversation(ResultSet rs) throws SQLException {
        return new ConversationRow(
                uuid(rs, "id"),
                uuid(rs, "analysis_id"),
                rs.getString("session_id"),
                parseMap(rs.getString("last_result")),
                rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime()
        );
    }

    private AnalysisConversationMessageDto mapMessage(ResultSet rs) throws SQLException {
        return AnalysisConversationMessageDto.builder()
                .id(uuid(rs, "id"))
                .role(rs.getString("role"))
                .content(rs.getString("content"))
                .metadata(parseMap(rs.getString("metadata")))
                .createdAt(rs.getTimestamp("created_at").toLocalDateTime())
                .build();
    }

    private UUID uuid(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private Map<String, Object> parseMap(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return objectMapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse conversation JSON: {}", e.getMessage());
            return null;
        }
    }

    private String json(Object value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeRole(String role) {
        if (role == null) return "assistant";
        return switch (role.trim().toLowerCase()) {
            case "user" -> "user";
            case "system" -> "system";
            default -> "assistant";
        };
    }

    private String sanitizeContent(String content) {
        if (content == null) return null;
        String trimmed = content.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.length() > MAX_CONTENT_CHARS) {
            trimmed = trimmed.substring(0, MAX_CONTENT_CHARS);
        }
        String scrubbed = trimmed;
        for (Pattern pattern : SECRET_PATTERNS) {
            scrubbed = pattern.matcher(scrubbed).replaceAll("[redacted]");
        }
        return scrubbed;
    }

    record ConversationRow(
            UUID id,
            UUID analysisId,
            String sessionId,
            Map<String, Object> lastResult,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
    }
}
