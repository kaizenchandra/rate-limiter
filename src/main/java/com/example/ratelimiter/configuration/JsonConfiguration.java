package com.example.ratelimiter.configuration;

import com.fasterxml.jackson.core.StreamReadConstraints;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class JsonConfiguration {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer constraints() {
        return b ->
                b.postConfigurer(
                        m ->
                                m.getFactory()
                                        .setStreamReadConstraints(
                                                StreamReadConstraints.builder()
                                                        .maxStringLength(1024)
                                                        .maxNestingDepth(8)
                                                        .maxNumberLength(32)
                                                        .build()));
    }
}
