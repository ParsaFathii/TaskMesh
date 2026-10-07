package com.taskmesh.observability;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registers the request logging filter ahead of the security filter chain so that
 * authentication failures are logged with a request id too.
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    FilterRegistrationBean<RequestLoggingFilter> requestLoggingFilter() {
        FilterRegistrationBean<RequestLoggingFilter> registration =
                new FilterRegistrationBean<>(new RequestLoggingFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/api/*", "/ws/*");
        return registration;
    }
}
