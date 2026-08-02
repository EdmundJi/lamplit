package com.betterself.growth.admin;

import com.betterself.growth.shared.api.ApiException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryPrivacyTest {
    @Test
    void acceptsOnlyEnumeratedNonTextProperties() {
        assertThat(TelemetryPolicy.validate(Map.of("eventType", "COMPLETED", "count", 1))).hasSize(2);
        assertThatThrownBy(() -> TelemetryPolicy.validate(Map.of("email", "person@example.test"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> TelemetryPolicy.validate(Map.of("eventType", "free form private text"))).isInstanceOf(ApiException.class);
    }
}
