package com.betterself.growth.shared;

import com.betterself.growth.shared.api.ApiEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ApiEnvelopeTest {

    @Test
    void usesTheRequestIdAndInjectedClock() {
        Instant now = Instant.parse("2026-07-31T08:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        ApiEnvelope<String> envelope = ApiEnvelope.of("ready", "01K1H7W8M0TESTREQUESTID0", clock);

        assertThat(envelope.data()).isEqualTo("ready");
        assertThat(envelope.requestId()).isEqualTo("01K1H7W8M0TESTREQUESTID0");
        assertThat(envelope.timestamp()).isEqualTo(now);
    }
}
