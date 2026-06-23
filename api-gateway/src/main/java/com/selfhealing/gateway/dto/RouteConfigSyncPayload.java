package com.selfhealing.gateway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Full route-config sync payload for data-plane long-poll consumers.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RouteConfigSyncPayload {

    private long version;
    private Map<String, String> routes;
    private List<String> removed;
}
