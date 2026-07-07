package com.selfhealing.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "gateway.sync")
public class RouteSyncProperties {

    /** Poll interval for the transactional outbox relay (ms). */
    private long outboxPollIntervalMs = 3_000;

    /** Initial delay before the first outbox poll (ms). */
    private long outboxInitialDelayMs = 5_000;

    /** Max relay attempts before giving up on a single outbox row. */
    private int outboxMaxAttempts = 10;

    /** Seconds multiplied by attempt count before retrying a failed outbox row. */
    private int outboxRetryBackoffSeconds = 5;

    /** Reconciler sweep interval (ms). */
    private long reconcilerIntervalMs = 60_000;

    /** Initial delay before the first reconciler sweep (ms). */
    private long reconcilerInitialDelayMs = 45_000;

    /** Whether the outbox relay is enabled. */
    private boolean outboxEnabled = true;

    /** Whether the route-program reconciler is enabled. */
    private boolean reconcilerEnabled = true;
}
