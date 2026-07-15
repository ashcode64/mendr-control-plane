package com.selfhealing.gateway.dto.openapi;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenApiImportResult {

    private boolean success;
    private String serviceName;
    private String specHash;
    private boolean dryRun;
    private int routesCreated;
    private int routesUpdated;
    private int routesSoftDeactivated;
    private int contractsCreated;
    private int contractsUpdated;

    @Builder.Default
    private List<String> warnings = new ArrayList<>();

    @Builder.Default
    private List<String> errors = new ArrayList<>();

    @Builder.Default
    private List<String> plannedChanges = new ArrayList<>();
}
