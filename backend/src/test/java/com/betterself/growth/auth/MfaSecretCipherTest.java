package com.betterself.growth.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("security")
class MfaSecretCipherTest {

    private final MfaSecretCipher cipher = new MfaSecretCipher(
        Base64.getEncoder().encodeToString(new byte[32])
    );

    @Test
    void encryptsWithAuthenticatedEncryptionAndDecryptsTheSecret() {
        byte[] encrypted = cipher.encrypt("JBSWY3DPEHPK3PXP");

        assertThat(new String(encrypted, StandardCharsets.ISO_8859_1)).doesNotContain("JBSWY3DPEHPK3PXP");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void rejectsTamperedCiphertext() {
        byte[] encrypted = cipher.encrypt("JBSWY3DPEHPK3PXP");
        encrypted[encrypted.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(encrypted))
            .isInstanceOf(IllegalStateException.class);
    }
}
