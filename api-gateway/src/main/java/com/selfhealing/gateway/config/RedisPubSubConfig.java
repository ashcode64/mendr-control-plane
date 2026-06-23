package com.selfhealing.gateway.config;

import com.selfhealing.gateway.service.RouteConfigService;
import com.selfhealing.gateway.service.RouteConfigSnapshotPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

    private final GatewayFastPathProperties properties;
    private final RouteConfigService routeConfigService;
    private final RouteConfigSnapshotPublisher snapshotPublisher;

    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory factory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(routeChangedListener(), channelTopic());
        return container;
    }

    @Bean
    ChannelTopic channelTopic() {
        return new ChannelTopic(properties.getRouteChangedChannel());
    }

    @Bean
    MessageListenerAdapter routeChangedListener() {
        return new MessageListenerAdapter(
                new RouteChangedMessageHandler(routeConfigService, snapshotPublisher), "onMessage");
    }

    @RequiredArgsConstructor
    public static class RouteChangedMessageHandler {
        private final RouteConfigService routeConfigService;
        private final RouteConfigSnapshotPublisher snapshotPublisher;

        public void onMessage(String message) {
            log.debug("Received route-changed: {}", message);
            routeConfigService.handleInvalidationMessage(message);
            snapshotPublisher.handleInvalidationMessage(message);
        }
    }
}
