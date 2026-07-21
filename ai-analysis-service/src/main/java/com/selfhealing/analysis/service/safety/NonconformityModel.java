package com.selfhealing.analysis.service.safety;

/**
 * Predicted P(failure | features) used as the conformal nonconformity score.
 */
public interface NonconformityModel {

    /** @return predicted probability of post-deploy failure in [0, 1] */
    double predictFailureProbability(double[] features);

    String modelKind();

    String modelVersion();
}
