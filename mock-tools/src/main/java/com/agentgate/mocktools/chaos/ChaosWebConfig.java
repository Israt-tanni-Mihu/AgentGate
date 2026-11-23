package com.agentgate.mocktools.chaos;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Applies chaos only to the tool endpoints, never to /admin/chaos itself (so you can always turn it off). */
@Configuration
public class ChaosWebConfig implements WebMvcConfigurer {

    private final ChaosState chaosState;

    public ChaosWebConfig(ChaosState chaosState) {
        this.chaosState = chaosState;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new ChaosInterceptor(chaosState)).addPathPatterns("/api/**");
    }
}
