package com.selfhealing.analysis.controller;

import com.selfhealing.analysis.service.regression.RegressionHarnessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Manual / CI entry for Phase 1 RegressionHarness.
 */
@RestController
@RequestMapping("/api/analysis/regression")
@RequiredArgsConstructor
public class RegressionHarnessController {

    private final RegressionHarnessService regressionHarnessService;

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> run(
            @RequestParam(defaultValue = "manual") String triggeredBy) {
        RegressionHarnessService.HarnessReport report = "gate".equals(triggeredBy)
                ? regressionHarnessService.gatePromotion("manual", "api")
                : regressionHarnessService.runManual();
        return ResponseEntity.ok(report.toMap());
    }
}
