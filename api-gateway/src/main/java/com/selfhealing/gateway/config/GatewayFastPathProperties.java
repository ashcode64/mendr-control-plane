package com.selfhealing.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "gateway.fastpath")
public class GatewayFastPathProperties {

    private int l1MaxSize = 10_000;
    private int l1TtlSeconds = 3;
    private String routeChangedChannel = "route-changed";
    private boolean streamingTransformsEnabled = true;
    private HttpPool httpPool = new HttpPool();

    @Data
    public static class HttpPool {
        private int maxTotal = 200;
        private int maxPerRoute = 50;
        private int connectTimeoutMs = 5_000;
        private int responseTimeoutMs = 10_000;
    }
}
