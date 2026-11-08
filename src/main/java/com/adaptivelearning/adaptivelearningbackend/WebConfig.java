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

    // Spring bean for consistency with the other interceptors.
    @Autowired
    private DeviceDetectionInterceptor deviceDetectionInterceptor;

    // Spring bean: it needs AccountAccessService.
    @Autowired
    private AuthInterceptor authInterceptor;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(
                        "https://ai-adaptive-repo-production.up.railway.app",
                        "https://adapt-learn-adapt.online",
                        "https://www.adapt-learn-adapt.online"
                )
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    // No resource handler for /uploads/**: files are never served statically. Diagrams are served
    // through the authenticated GET /api/materials/diagram/{filename}; raw handout bytes are never
    // served to any client.

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