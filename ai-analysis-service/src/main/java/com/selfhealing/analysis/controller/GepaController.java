package com.selfhealing.analysis.controller;

import com.selfhealing.analysis.service.gepa.GepaCompileGate;
import com.selfhealing.analysis.service.gepa.GepaCompileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Manual / CI entry for Phase 6 GEPA compile + status.
 */
@RestController
@RequestMapping("/api/analysis/gepa")
@RequiredArgsConstructor
public class GepaController {

    private final GepaCompileService gepaCompileService;
    private final GepaCompileGate gepaCompileGate;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        return ResponseEntity.ok(Map.of(
                "status", gepaCompileGate.status(),
                "canCompile", gepaCompileGate.canCompile(),
                "scrubProven", gepaCompileGate.scrubProven(),
                "preferDspyGepa", gepaCompileGate.preferDspyGepa()));
    }

    @PostMapping("/compile")
    public ResponseEntity<Map<String, Object>> compile() {
        return ResponseEntity.ok(gepaCompileService.compileAndMaybePromote(null));
    }
}
