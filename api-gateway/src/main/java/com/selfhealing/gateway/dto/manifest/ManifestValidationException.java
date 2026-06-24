package com.selfhealing.gateway.dto.manifest;

import lombok.Getter;

import java.util.List;

/**
 * Thrown when a manifest fails validation before any persistence occurs.
 * Carries the collected fatal errors so the API can return them as a 400.
 */
@Getter
public class ManifestValidationException extends RuntimeException {

    private final List<String> errors;

    public ManifestValidationException(List<String> errors) {
        super("Manifest validation failed: " + String.join("; ", errors));
        this.errors = errors;
    }
}
