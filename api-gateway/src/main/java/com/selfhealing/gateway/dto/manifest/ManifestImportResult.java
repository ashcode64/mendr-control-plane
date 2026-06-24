package com.selfhealing.gateway.dto.manifest;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured outcome of a manifest import. Surfaced to the UI so users see
 * exactly what was registered and any non-fatal warnings.
 */
@Data
@Builder
public class ManifestImportResult {

    private boolean success;

    /** Name of the registered/updated service. */
    private String service;

    private int contractsCreated;
    private int routesCreated;

    /** Detailed, human-readable lines for the UI summary. */
    @Builder.Default
    private List<String> contracts = new ArrayList<>();

    @Builder.Default
    private List<String> routes = new ArrayList<>();

    /** Non-fatal issues (e.g. skipped duplicates, suspected inline secret). */
    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    /** Fatal validation errors; when present, nothing is persisted. */
    @Builder.Default
    private List<String> errors = new ArrayList<>();
}
