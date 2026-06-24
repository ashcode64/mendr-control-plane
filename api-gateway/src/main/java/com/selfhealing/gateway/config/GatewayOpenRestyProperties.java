package com.selfhealing.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "gateway.openresty")
public class GatewayOpenRestyProperties {

    /**
     * When set (e.g. {@code host.docker.internal}), rewrites {@code localhost} / {@code 127.0.0.1}
     * in published snapshot URLs so OpenResty inside Docker can reach services on the host.
     */
    private String dockerHostRewrite = "";

    /** Routes always published to Redis even before contracts/rules exist (demo bootstrap). */
    private List<BaselineRoute> baselineRoutes = new ArrayList<>();

    /**
     * When true, fall back to the legacy heuristic that infers routes from contract
     * endpoint names. Explicit manifest-declared {@code service_routes} are always the
     * primary source; this only adds the older best-effort inference on top.
     */
    private boolean contractHeuristicRoutesEnabled = false;

    @Data
    public static class BaselineRoute {
        private String sourceService;
        private String targetService;
        private String endpoint;
    }
}
