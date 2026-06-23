package com.selfhealing.analysis.controller;

import com.selfhealing.analysis.model.AnalysisResult;
import com.selfhealing.analysis.repository.AnalysisResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AnalysisController {

    private final AnalysisResultRepository analysisRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @GetMapping
    public ResponseEntity<Page<AnalysisResult>> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(analysisRepository.findAllByOrderByAnalyzedAtDesc(PageRequest.of(page, size)));
    }

    @GetMapping("/pending")
    public ResponseEntity<List<AnalysisResult>> getPending() {
        return ResponseEntity.ok(analysisRepository.findByStatus(AnalysisResult.AnalysisStatus.PENDING_APPROVAL));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AnalysisResult> getById(@PathVariable UUID id) {
        return analysisRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** Approve a transformation - triggers rule deployment */
    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {

        return analysisRepository.findById(id).map(result -> {
            result.setStatus(AnalysisResult.AnalysisStatus.APPROVED);
            analysisRepository.save(result);

            // Publish approval event to trigger rule deployment
            Map<String, Object> approvalEvent = new HashMap<>();
            approvalEvent.put("analysisId", id);
            approvalEvent.put("action", "APPROVED");
            approvalEvent.put("actedBy", body != null ? body.getOrDefault("approvedBy", "dashboard-user") : "dashboard-user");
            approvalEvent.put("transformationRules", result.getTransformationRules());
            approvalEvent.put("failureId", result.getFailureId());

            kafkaTemplate.send("api.transformations.approved", id.toString(), approvalEvent);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Transformation rule approved and deployment triggered");
            response.put("analysisId", id);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    /** Reject a transformation suggestion */
    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @PathVariable UUID id,
            @RequestBody(required = false) Map<String, String> body) {

        return analysisRepository.findById(id).map(result -> {
            result.setStatus(AnalysisResult.AnalysisStatus.REJECTED);
            analysisRepository.save(result);

            Map<String, Object> response = new HashMap<>();
            response.put("message", "Transformation suggestion rejected");
            response.put("analysisId", id);
            return ResponseEntity.ok(response);
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        long total = analysisRepository.count();
        long pending = analysisRepository.findByStatus(AnalysisResult.AnalysisStatus.PENDING_APPROVAL).size();
        long approved = analysisRepository.findByStatus(AnalysisResult.AnalysisStatus.APPROVED).size();
        long rejected = analysisRepository.findByStatus(AnalysisResult.AnalysisStatus.REJECTED).size();

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", total);
        stats.put("pending", pending);
        stats.put("approved", approved);
        stats.put("rejected", rejected);
        return ResponseEntity.ok(stats);
    }
}
