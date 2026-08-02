package com.betterself.growth.privacy;

import com.betterself.growth.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttachmentPolicyTest {

    @Test
    void validatesMimeExtensionSizeAndScanStatus() {
        assertThatCode(() -> AttachmentPolicy.validate("image/png", "png", 1024)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AttachmentPolicy.validate("image/png", "exe", 1024)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AttachmentPolicy.validate("image/png", "png", AttachmentPolicy.MAX_BYTES + 1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AttachmentPolicy.requireClean("PENDING")).isInstanceOf(ApiException.class);
        assertThatCode(() -> AttachmentPolicy.requireClean("CLEAN")).doesNotThrowAnyException();
    }
}
