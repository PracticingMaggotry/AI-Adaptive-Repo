package com.adaptivelearning.adaptivelearningbackend;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs on every request, stashes DeviceDetector's classification as a
 * request attribute so controllers/pages can read it without
 * recomputing it, and applies the caching-safety Vary header.
 *
 * Presentation-only — see DeviceDetector's class-level SECURITY WARNING.
 * This interceptor deliberately never touches the session and never
 * blocks a request; the classification is scoped to a single request
 * and recomputed fresh every time, so a spoofed value on one request
 * can never "stick" and influence a later, unrelated request the way a
 * session flag could.
 */
@Component
public class DeviceDetectionInterceptor implements HandlerInterceptor {

    /** Request attribute key holding the resolved DeviceDetector.DeviceType. */
    public static final String REQUEST_ATTR = "deviceType";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        DeviceDetector.DeviceType deviceType = DeviceDetector.classify(request);
        request.setAttribute(REQUEST_ATTR, deviceType);
        DeviceDetector.applyVaryHeader(response);
        return true; // never blocks — presentation only, always continues
    }
}