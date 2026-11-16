package com.example.ratelimiter.configuration;

import com.example.ratelimiter.api.EnforcementFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration
public class FilterConfiguration {
    @Bean
    FilterRegistrationBean<EnforcementFilter> registration(EnforcementFilter f) {
        var r = new FilterRegistrationBean<>(f);
        r.setEnabled(false);
        return r;
    }
}
