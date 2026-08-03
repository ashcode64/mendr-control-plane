-- Confidence scoring v2: first-class calibrated probability columns.
-- confidence remains the display score (pVa when fitted, else rawCorrect).
ALTER TABLE analysis_results
    ADD COLUMN IF NOT EXISTS calibrated_confidence NUMERIC,
    ADD COLUMN IF NOT EXISTS confidence_interval_width NUMERIC,
    ADD COLUMN IF NOT EXISTS venn_abers_fitted BOOLEAN DEFAULT false;

COMMENT ON COLUMN analysis_results.calibrated_confidence IS 'Venn-Abers pVa point estimate';
COMMENT ON COLUMN analysis_results.confidence_interval_width IS 'Venn-Abers epistemic width (p1-p0)';
COMMENT ON COLUMN analysis_results.venn_abers_fitted IS 'False until inductive VA has calibration examples';
