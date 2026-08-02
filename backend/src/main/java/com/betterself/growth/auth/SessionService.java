package com.betterself.growth.auth;

import jakarta.servlet.http.HttpServletRequest;

public interface SessionService {

    IssuedSession issue(long userId, String deviceLabel, HttpServletRequest request);

    IssuedSession rotate(String rawRefreshToken, HttpServletRequest request);

    void revokeCurrent(long userId, String rawRefreshToken);

    void revokeAll(long userId);

    record IssuedSession(long userId, String accessToken, String refreshToken, String csrfToken) {
    }
}
