package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // Injected as a Spring bean (not `new`) so its @Value property is actually populated.
    @Autowired
    private CsrfInterceptor csrfInterceptor;

    // Injected as a Spring bean for consistency with the other interceptors here;
    // it currently has no @Value/@Autowired state of its own, but `new`-ing it up
    // directly would opt it out of Spring's lifecycle for no benefit.
    @Autowired
    private DeviceDetectionInterceptor deviceDetectionInterceptor;

    // Now a Spring bean (needs AccountAccessService) rather than `new AuthInterceptor()`.
    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(
                        "http://localhost:5500",
                        "http://127.0.0.1:5500",
                        "https://ai-adaptive-repo-production.up.railway.app"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    // No resource handler for /uploads/** — files now live in R2, not the ephemeral
    // container filesystem. Diagrams are served via the authenticated
    // GET /api/materials/diagram/{filename} proxy; raw handout bytes are never
    // served to any client, admin or student.

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Runs first: cheap, never blocks, and makes request.getAttribute("deviceType")
        // available to everything downstream (including AuthInterceptor's redirects,
        // if it's ever extended to redirect somewhere device-specific).
        registry.addInterceptor(deviceDetectionInterceptor).addPathPatterns("/**");
        registry.addInterceptor(authInterceptor).addPathPatterns("/**");
        registry.addInterceptor(csrfInterceptor).addPathPatterns("/**");
    }
}