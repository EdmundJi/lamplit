package com.betterself.growth.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

@ConfigurationProperties(prefix = "app.admin.local-login")
public record LocalAdminLoginProperties(
    boolean enabled,
    String username,
    String password
) {
    public boolean allowsDirectLogin(String identifier, String role) {
        return enabled
            && present(username)
            && normalize(username).equals(normalize(identifier))
            && "ADMIN".equals(role);
    }

    public void validate() {
        if (!enabled) return;
        if (!present(username) || !present(password)) {
            throw new IllegalStateException(
                "LOCAL_ADMIN_LOGIN_USERNAME and LOCAL_ADMIN_LOGIN_PASSWORD are required when local admin login is enabled"
            );
        }
    }

    public String normalizedUsername() {
        return normalize(username);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
