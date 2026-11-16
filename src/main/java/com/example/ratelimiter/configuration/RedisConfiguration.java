package com.example.ratelimiter.configuration;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.ClusterTopologyRefreshOptions;

import java.time.Duration;

import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class RedisConfiguration {
    @Bean
    LettuceClientConfigurationBuilderCustomizer boundedRedis() {
        return builder ->
                builder.clientOptions(
                        ClusterClientOptions.builder()
                                .autoReconnect(true)
                                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                                .requestQueueSize(256)
                                .maxRedirects(2)
                                .topologyRefreshOptions(
                                        ClusterTopologyRefreshOptions.builder()
                                                .enablePeriodicRefresh(Duration.ofSeconds(30))
                                                .enableAllAdaptiveRefreshTriggers()
                                                .build())
                                .build());
    }
}
