package com.adaptivelearning.adaptivelearningbackend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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

    // ── REMOVED: addResourceHandlers() for /uploads/** ──────────────────
    //
    // Uploaded handouts and diagram images no longer live on the local
    // filesystem — they're stored in Cloudflare R2 (see FileStorageService).
    // Railway's container filesystem is ephemeral: anything written to
    // local disk is wiped on every redeploy, restart, or scale event unless
    // a persistent Volume is attached. Serving files from local disk via a
    // static ResourceHandler meant every uploaded handout/diagram silently
    // disappeared on the next deploy while the Material DB row (and its
    // stored_filename / diagram_image_filename columns) kept pointing at a
    // file that no longer existed.
    //
    // Diagram images are now served through an authenticated proxy endpoint
    // instead of a static handler:
    //
    //     GET /api/materials/diagram/{filename}
    //         → MaterialController.serveDiagram()
    //         → requires a logged-in session (the old static handler had
    //           NO auth check at all — any URL guesser could fetch any
    //           student's diagram image)
    //         → reads the PNG bytes from R2 via FileStorageService.load()
    //
    // Handout files themselves (PDF/DOCX/TXT/etc.) are NEVER served raw to
    // any client, admin or student — that was already true before this
    // migration (see AdminController's Content Review feature, which only
    // ever sends already-extracted text or a server-rendered PDF, never the
    // original bytes). So no public-facing handout file endpoint is needed;
    // FileStorageService.load() is called directly server-side wherever the
    // raw bytes are needed (text extraction, diagram extraction, content
    // review, PDF re-typesetting).
    //
    // The old BLOCKED_UPLOAD_EXTENSIONS allowlist defense (preventing a
    // malicious .html/.js upload from being served back as same-origin
    // content) is no longer needed for the same reason: nothing under
    // /uploads/** is served anymore, so there's no path through which an
    // uploaded file could ever be returned to a browser as a renderable
    // same-origin resource. The upload-time extension/content-type
    // allowlist in MaterialController.uploadMaterial() still applies, since
    // it also gates what reaches the AI extraction pipeline.

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