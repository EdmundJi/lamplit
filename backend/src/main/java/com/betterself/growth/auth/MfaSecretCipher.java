package com.betterself.growth.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class MfaSecretCipher {

    private static final byte FORMAT_VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MfaSecretCipher(@Value("${app.security.mfa-encryption-key}") String encodedKey) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(encodedKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("MFA encryption key must be valid Base64", exception);
        }
        if (keyBytes.length != 32) {
            throw new IllegalStateException("MFA encryption key must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public byte[] encrypt(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("MFA secret is required");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + nonce.length + ciphertext.length)
                .put(FORMAT_VERSION)
                .put(nonce)
                .put(ciphertext)
                .array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt MFA secret", exception);
        }
    }

    public String decrypt(byte[] encrypted) {
        if (encrypted == null || encrypted.length <= 1 + NONCE_BYTES) {
            throw new IllegalStateException("MFA secret ciphertext is invalid");
        }
        ByteBuffer buffer = ByteBuffer.wrap(encrypted);
        if (buffer.get() != FORMAT_VERSION) {
            throw new IllegalStateException("MFA secret ciphertext version is unsupported");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to decrypt MFA secret", exception);
        }
    }
}
