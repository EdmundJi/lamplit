package com.betterself.growth.auth;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class CookieFactory {

    public static final String ACCESS_COOKIE = "access_token";
    public static final String REFRESH_COOKIE = "refresh_token";
    public static final String CSRF_COOKIE = "csrf_token";

    private final boolean secure;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public CookieFactory(
        @Value("${app.security.secure-cookies:false}") boolean secure,
        @Value("${app.security.access-token-ttl:PT30M}") Duration accessTtl,
        @Value("${app.security.refresh-token-ttl:P7D}") Duration refreshTtl
    ) {
        this.secure = secure;
        this.accessTtl = accessTtl;
        this.refreshTtl = refreshTtl;
    }

    public void write(HttpServletResponse response, SessionService.IssuedSession session) {
        add(response, ACCESS_COOKIE, session.accessToken(), "/", accessTtl, true);
        add(response, REFRESH_COOKIE, session.refreshToken(), "/api/v1/auth", refreshTtl, true);
        add(response, CSRF_COOKIE, session.csrfToken(), "/", refreshTtl, false);
    }

    public void clear(HttpServletResponse response) {
        add(response, ACCESS_COOKIE, "", "/", Duration.ZERO, true);
        add(response, REFRESH_COOKIE, "", "/api/v1/auth", Duration.ZERO, true);
        add(response, CSRF_COOKIE, "", "/", Duration.ZERO, false);
    }

    private void add(
        HttpServletResponse response,
        String name,
        String value,
        String path,
        Duration maxAge,
        boolean httpOnly
    ) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
            .httpOnly(httpOnly)
            .secure(secure)
            .sameSite("Lax")
            .path(path)
            .maxAge(maxAge)
            .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
