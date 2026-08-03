package com.selfhealing.analysis.service.safety;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SemanticEntropyAndAgreementTest {

    @Test
    void singleCandidateIsNeutralNotPerfect() {
        Map<String, Object> p = Map.of("type", "DSL_PROGRAM", "ops", List.of(
                Map.of("op", "rename", "from", "/a", "to", "/b")));
        String h = CanonicalAstHasher.hashProgram(p);
        assertThat(SemanticEntropy.consistency(List.of(h))).isEqualTo(0.5);
        assertThat(SemanticEntropy.consistency(List.of())).isEqualTo(0.5);
    }

    @Test
    void identicalProgramsHaveFullConsistency() {
        Map<String, Object> p = Map.of("type", "DSL_PROGRAM", "ops", List.of(
                Map.of("op", "rename", "from", "/a", "to", "/b")));
        String h = CanonicalAstHasher.hashProgram(p);
        assertThat(SemanticEntropy.consistency(List.of(h, h, h))).isEqualTo(1.0);
    }

    @Test
    void diverseClustersLowerConsistency() {
        String a = CanonicalAstHasher.hashProgram(Map.of("ops", List.of(Map.of("op", "rename", "from", "/a", "to", "/b"))));
        String b = CanonicalAstHasher.hashProgram(Map.of("ops", List.of(Map.of("op", "default", "path", "/c", "value", 0))));
        double c = SemanticEntropy.consistency(List.of(a, b, a, b));
        assertThat(c).isLessThan(1.0);
        assertThat(c).isGreaterThanOrEqualTo(0.0);
    }

    @Test
    void opsOrderIsPreservedInHash() {
        Map<String, Object> a = Map.of("ops", List.of(
                Map.of("op", "rename", "from", "/a", "to", "/b"),
                Map.of("op", "default", "path", "/c", "value", 0)));
        Map<String, Object> b = Map.of("ops", List.of(
                Map.of("op", "default", "path", "/c", "value", 0),
                Map.of("op", "rename", "from", "/a", "to", "/b")));
        assertThat(CanonicalAstHasher.hashProgram(a)).isNotEqualTo(CanonicalAstHasher.hashProgram(b));
    }

    @Test
    void canonicalHashIgnoresRationale() {
        Map<String, Object> a = Map.of("ops", List.of(Map.of("op", "rename", "from", "/x", "to", "/y")),
                "rationale", "one");
        Map<String, Object> b = Map.of("ops", List.of(Map.of("op", "rename", "from", "/x", "to", "/y")),
                "rationale", "two");
        assertThat(CanonicalAstHasher.hashProgram(a)).isEqualTo(CanonicalAstHasher.hashProgram(b));
    }

    @Test
    void deterministicAgreementUsesPathOverlapNotBlindDsl() {
        Map<String, Object> ai = Map.of(
                "type", "DSL_PROGRAM",
                "ops", List.of(Map.of("op", "rename", "from", "/old", "to", "/new")));
        Map<String, Object> det = Map.of(
                "type", "FIELD_RENAME",
                "mappings", Map.of("/old", "/new"));
        double withOverlap = DeterministicAgreement.scoreFromRules(ai, det, true, true);
        Map<String, Object> aiMiss = Map.of(
                "type", "DSL_PROGRAM",
                "ops", List.of(Map.of("op", "rename", "from", "/x", "to", "/y")));
        double without = DeterministicAgreement.scoreFromRules(aiMiss, det, true, true);
        assertThat(withOverlap).isGreaterThan(without);
        assertThat(DeterministicAgreement.score("DSL_PROGRAM", "FIELD_RENAME", true, true))
                .isEqualTo(0.55);
    }

    @Test
    void pathJaccard() {
        assertThat(DeterministicAgreement.pathOverlap(
                Set.of("/a", "/b"), Set.of("/a", "/c"))).isEqualTo(1.0 / 3.0);
    }

    @Test
    void wilsonQualityUsesLaplaceThenLowerBound() {
        double laplace = WilsonScore.quality(1, 0, 3, 1.96);
        assertThat(laplace).isEqualTo((1 + 1.0) / (1 + 2.0));
        double wilson = WilsonScore.quality(10, 0, 3, 1.96);
        assertThat(wilson).isGreaterThan(0.7);
        assertThat(wilson).isLessThan(1.0);
    }

    @Test
    void nonconformityFeaturesAreLengthSeven() {
        SafetyScore score = new SafetyScore(1.0, 1.0, 1.0, 1.0, 0.0, 0.5, 0.8, 0.2,
                0.8, 0.1, 0.3, 0.25, 0.2, true);
        assertThat(score.nonconformityFeatures()).hasSize(7);
        assertEquals(1.0, score.nonconformityFeatures()[4], 1e-9);
        assertThat(LogisticNonconformityModel.DEFAULT_WEIGHTS).hasSize(7);
    }

    @Test
    void unfittedDisplayUsesRawCorrect() {
        SafetyScore s = new SafetyScore(0.9, 0.9, 0.9, 0.9, 0.9, 0.5, 0.9, 0.1,
                0.9, 0.0, 1.0, 0.5, 1.0, false);
        assertThat(s.displayConfidence()).isEqualTo(0.9);
        assertThat(s.vennAbersFitted()).isFalse();
    }

    @Test
    void causalVerificationFromCritics() {
        assertThat(CausalVerification.score(Map.of("ok", true), Map.of("ok", true))).isEqualTo(0.95);
        assertThat(CausalVerification.score(Map.of("ok", false), Map.of("ok", true))).isEqualTo(0.70);
        assertThat(CausalVerification.score(Map.of("ok", false), Map.of("ok", false))).isEqualTo(0.20);
        assertThat(CausalVerification.score(null, null)).isEqualTo(0.5);
    }

    @Test
    void causalVerificationReadsGatewaySimulationReportInts() {
        Map<String, Object> simOk = Map.of("results", List.of("x"), "passed", 3, "faulted", 0, "mismatched", 0);
        Map<String, Object> verifyOk = Map.of("valid", true, "errors", List.of());
        assertThat(CausalVerification.score(verifyOk, simOk)).isEqualTo(0.95);

        Map<String, Object> simBad = Map.of("passed", 1, "faulted", 0, "mismatched", 2);
        assertThat(CausalVerification.outcome(simBad)).isFalse();
        // Boolean.TRUE.equals on int must not be used — passed=2 alone with faulted keys works
        assertThat(CausalVerification.outcome(Map.of("passed", 2, "faulted", 0, "mismatched", 0))).isTrue();
    }

    @Test
    void emptyCaseSimulationIsUnknownNotSuccess() {
        Map<String, Object> emptySim = Map.of("results", List.of(), "passed", 0, "faulted", 0, "mismatched", 0);
        assertThat(CausalVerification.outcome(emptySim)).isNull();
        // verify ok + empty sim ⇒ partial (0.70), not full success (0.95)
        assertThat(CausalVerification.score(Map.of("valid", true), emptySim)).isEqualTo(0.70);
        assertThat(CausalVerification.outcome(Map.of("faulted", 0, "mismatched", 0, "passed", 0))).isNull();
    }

    @Test
    void winningClusterFrequencyReturnsMinusOneWhenWinnerAbsent() {
        assertThat(SemanticEntropy.winningClusterFrequency(List.of("a", "b"), "c")).isEqualTo(-1.0);
        assertThat(SemanticEntropy.winningClusterFrequency(List.of("a", "a"), "a")).isEqualTo(1.0);
    }

    @Test
    void aurocPerfectSeparationIsOne() {
        List<CalibrationDiagnostics.Scored> perfect = List.of(
                new CalibrationDiagnostics.Scored(0.1, false),
                new CalibrationDiagnostics.Scored(0.2, false),
                new CalibrationDiagnostics.Scored(0.8, true),
                new CalibrationDiagnostics.Scored(0.9, true));
        assertThat(CalibrationDiagnostics.auroc(perfect)).isEqualTo(1.0);
    }

    @Test
    void generationConfidencePrefersLogprobsOverClusterAndVerbalized() {
        var fromLp = GenerationConfidence.resolve(List.of(-0.1, -0.2), 0.99, 0.1);
        assertThat(fromLp.fromLogprobs()).isTrue();
        assertThat(fromLp.source()).isEqualTo("token_logprobs");
        assertThat(fromLp.value()).isBetween(0.8, 1.0);

        var fromCluster = GenerationConfidence.resolve(null, 0.75, 0.1);
        assertThat(fromCluster.source()).isEqualTo("cluster_frequency");
        assertThat(fromCluster.value()).isEqualTo(0.75);

        var fromVerbal = GenerationConfidence.resolve(null, -1.0, 0.42);
        assertThat(fromVerbal.source()).isEqualTo("verbalized");
        assertThat(fromVerbal.value()).isEqualTo(0.42);
    }

    @Test
    void reliabilityDiagramHasBins() {
        List<CalibrationDiagnostics.Scored> scored = List.of(
                new CalibrationDiagnostics.Scored(0.1, false),
                new CalibrationDiagnostics.Scored(0.2, false),
                new CalibrationDiagnostics.Scored(0.8, true),
                new CalibrationDiagnostics.Scored(0.9, true));
        var diagram = CalibrationDiagnostics.reliabilityDiagram(scored, 10);
        assertThat(diagram).isNotEmpty();
        assertThat(diagram.get(0)).containsKeys("meanPredicted", "empiricalPositiveRate", "count");
    }
}
