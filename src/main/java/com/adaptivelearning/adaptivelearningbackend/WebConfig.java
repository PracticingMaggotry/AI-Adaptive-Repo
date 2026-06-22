package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.util.Locale;

@Configuration
public class WebConfig implements WebMvcConfigurer {
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

    /**
     * Serves uploaded materials but refuses to serve file extensions that a
     * browser would execute as code if loaded from our origin. Without this,
     * a malicious .html or .js file that slipped past the upload check (or was
     * placed directly on disk) could be fetched from /uploads/**, run in the
     * victim's browser as same-origin content, and steal session cookies —
     * exactly the stored-XSS scenario the upload allowlist above is also
     * defending against. Defense-in-depth: block at the serving layer too.
     */
    private static final java.util.Set<String> BLOCKED_UPLOAD_EXTENSIONS = java.util.Set.of(
            ".html", ".htm", ".xhtml", ".js", ".mjs", ".cjs",
            ".svg",  ".xml", ".xsl",  ".php", ".jsp", ".asp",
            ".aspx", ".sh",  ".py",   ".rb",  ".pl"
    );

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:uploads/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    public Resource resolveResource(
                            jakarta.servlet.http.HttpServletRequest request,
                            String requestPath,
                            java.util.List<? extends Resource> locations,
                            org.springframework.web.servlet.resource.ResourceResolverChain chain) {
                        // Reject any path whose final segment has a dangerous extension.
                        String lower = requestPath.toLowerCase(Locale.ROOT);
                        for (String ext : BLOCKED_UPLOAD_EXTENSIONS) {
                            if (lower.endsWith(ext)) return null; // 404
                        }
                        return super.resolveResource(request, requestPath, locations, chain);
                    }
                });
    }

    // Server-side login/role gate for every *.html page — see AuthInterceptor
    // for the full rationale. Registered against "/**" so it sees every
    // request, including static resources; it internally ignores anything
    // that isn't an .html request (or "/"), so API calls, CSS, JS, and
    // uploaded files are completely unaffected.
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor()).addPathPatterns("/**");
        // CSRF interceptor enforces a synchronizer token on every
        // state-mutating request (POST/PUT/DELETE/PATCH) once the user is
        // logged in. GET/HEAD/OPTIONS are safe-method exemptions per RFC 7231.
        registry.addInterceptor(new CsrfInterceptor()).addPathPatterns("/**");
    }
}