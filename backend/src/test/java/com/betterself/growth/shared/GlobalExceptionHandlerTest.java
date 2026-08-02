package com.betterself.growth.shared.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    @Test
    void methodNotSupportedReturns405Envelope() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(
            Clock.fixed(Instant.parse("2026-08-01T00:00:00Z"), ZoneOffset.UTC)
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/refresh");
        request.setAttribute("requestId", "request-1");

        var response = handler.handleMethodNotSupported(
            new HttpRequestMethodNotSupportedException("GET", List.of("POST")),
            request
        );

        assertThat(response.getStatusCode().value()).isEqualTo(405);
        assertThat(response.getHeaders().getAllow()).containsExactly(HttpMethod.POST);
        ApiEnvelope<ApiError> envelope = response.getBody();
        assertThat(envelope).isNotNull();
        assertThat(envelope.data().code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(envelope.data().status()).isEqualTo(405);
        assertThat(envelope.data().details()).containsEntry("path", "/api/v1/auth/refresh");
    }
}
