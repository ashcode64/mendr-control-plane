package com.selfhealing.rules.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records a durable outbox row (same transaction as the caller) and fires the
 * best-effort Redis pub/sub notification after commit.
 */
@Service
@RequiredArgsConstructor
public class RouteSyncNotifier {

    private final RouteChangeOutboxService outbox;
    private final RouteChangedPublisher routeChangedPublisher;

    public void notifyRouteChanged(String source, String target, String endpoint, String reason) {
        outbox.enqueueRoute(source, target, endpoint, reason);
        afterCommit(() -> routeChangedPublisher.publishRoute(source, target, endpoint));
    }

    public void notifyTargetServiceChanged(String targetService, String reason) {
        outbox.enqueueTargetService(targetService, reason);
        afterCommit(() -> routeChangedPublisher.publishTargetService(targetService));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
