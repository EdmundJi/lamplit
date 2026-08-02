package com.betterself.growth.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.admin.bootstrap")
public record AdminBootstrapProperties(
    String email,
    String password,
    String displayName,
    String timezone,
    String mfaSecret
) {
    boolean hasAnyCredential() {
        return present(email) || present(password) || present(displayName) || present(timezone) || present(mfaSecret);
    }

    boolean hasRequiredCredential() {
        return present(email) && present(password);
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }
}
