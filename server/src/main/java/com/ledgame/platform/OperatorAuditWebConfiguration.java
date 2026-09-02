package com.ledgame.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class OperatorAuditWebConfiguration implements WebMvcConfigurer {
    private final OperatorAuditInterceptor interceptor;
    private final StartupGateInterceptor startupGateInterceptor;

    public OperatorAuditWebConfiguration(
            OperatorAuditInterceptor interceptor,
            StartupGateInterceptor startupGateInterceptor) {
        this.interceptor = interceptor;
        this.startupGateInterceptor = startupGateInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(startupGateInterceptor).addPathPatterns("/api/**");
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
