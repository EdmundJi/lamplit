package com.betterself.growth.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;

@Component
public class CsrfDoubleSubmitFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of(
        HttpMethod.GET.name(),
        HttpMethod.HEAD.name(),
        HttpMethod.OPTIONS.name(),
        HttpMethod.TRACE.name()
    );
    private static final Set<String> EXEMPT_PATHS = Set.of(
        "/api/v1/auth/register",
        "/api/v1/auth/login",
        "/api/v1/auth/mfa/verify",
        "/api/v1/auth/refresh",
        "/api/v1/auth/password/forgot",
        "/api/v1/auth/password/reset"
    );

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        if (requiresCsrf(request) && !matches(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean requiresCsrf(HttpServletRequest request) {
        return !SAFE_METHODS.contains(request.getMethod()) && !EXEMPT_PATHS.contains(request.getRequestURI());
    }

    private boolean matches(HttpServletRequest request) {
        String cookie = JwtAuthenticationFilter.cookie(request, CookieFactory.CSRF_COOKIE);
        String header = request.getHeader("X-CSRF-Token");
        if (cookie == null || header == null) {
            return false;
        }
        return MessageDigest.isEqual(
            cookie.getBytes(StandardCharsets.UTF_8),
            header.getBytes(StandardCharsets.UTF_8)
        );
    }
}
