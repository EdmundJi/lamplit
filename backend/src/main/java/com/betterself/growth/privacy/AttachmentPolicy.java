package com.betterself.growth.privacy;

import com.betterself.growth.shared.api.ApiException;
import org.springframework.http.HttpStatus;

import java.util.Map;
import java.util.Set;

public final class AttachmentPolicy {

    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Map<String, Set<String>> ALLOWED = Map.of(
        "image/jpeg", Set.of("jpg", "jpeg"),
        "image/png", Set.of("png"),
        "application/pdf", Set.of("pdf"),
        "text/plain", Set.of("txt")
    );

    private AttachmentPolicy() {
    }

    public static void validate(String contentType, String extension, long sizeBytes) {
        String normalized = extension == null ? "" : extension.toLowerCase();
        if (!ALLOWED.getOrDefault(contentType, Set.of()).contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_ATTACHMENT_TYPE", "Attachment type is not supported");
        }
        if (sizeBytes <= 0 || sizeBytes > MAX_BYTES) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ATTACHMENT_SIZE", "Attachment size is invalid");
        }
    }

    public static void requireClean(String scanStatus) {
        if (!"CLEAN".equals(scanStatus)) {
            throw new ApiException(HttpStatus.CONFLICT, "ATTACHMENT_NOT_CLEAN", "Attachment is not ready to associate");
        }
    }
}
