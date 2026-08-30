package com.ledgame.platform;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class OperatorAuditWebConfiguration implements WebMvcConfigurer {
    private final OperatorAuditInterceptor interceptor;

    public OperatorAuditWebConfiguration(OperatorAuditInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
