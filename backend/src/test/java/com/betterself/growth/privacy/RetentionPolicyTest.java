package com.betterself.growth.privacy;

import com.betterself.growth.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionPolicyTest {

    @Test
    void enforcesDocumentedDurations() {
        assertThat(RetentionPolicy.EXPORT_OBJECT_TTL).isEqualTo(Duration.ofHours(24));
        assertThat(RetentionPolicy.EXPORT_DOWNLOAD_TTL).isEqualTo(Duration.ofMinutes(15));
        assertThat(RetentionPolicy.DELETION_COOLING_OFF).isEqualTo(Duration.ofDays(7));
        assertThat(RetentionPolicy.requireAiRetentionDays(30)).isEqualTo(30);
        assertThatThrownBy(() -> RetentionPolicy.requireAiRetentionDays(365)).isInstanceOf(ApiException.class);
    }
}
