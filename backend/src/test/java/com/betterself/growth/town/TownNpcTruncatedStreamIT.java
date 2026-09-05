package com.betterself.growth.town;

import com.betterself.growth.ai.QwenProvider;
import com.betterself.growth.shared.api.ApiException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Base64;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The provider often finishes the prose long before the option tail, and sometimes drops
 * out in between. The reply the user already read has to survive that.
 */
@Testcontainers
@SpringBootTest(properties = {
    "app.ai.provider=mock",
    "app.execution.expiry-delay-ms=3600000",
    "app.insights.rebuild-cron=0 0 0 1 1 *",
    "app.town.reflection-cron=0 0 0 1 1 *"
})
@AutoConfigureMockMvc
class TownNpcTruncatedStreamIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> true);
        registry.add("app.security.jwt-secret", () -> "test-only-secret-at-least-thirty-two-bytes");
        registry.add("app.security.mfa-encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.security.secure-cookies", () -> false);
    }

    @Autowired MockMvc mvc;

    @Test
    void keepsTheProseWhenTheProviderDropsBeforeTheOptionTail() throws Exception {
        Session owner = register("town-truncated@example.test");

        MvcResult started = mvc.perform(post("/api/v1/town/npc/GUIDE/chat:stream")
                .cookie(owner.access(), owner.csrf())
                .header("X-CSRF-Token", owner.csrf().getValue())
                .contentType("application/json")
                .content("{\"message\":\"今天从哪里开始\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();
        started.getAsyncResult(15_000);
        String sse = mvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(sse).contains("先挑一个最小的步骤");
        assertThat(sse).doesNotContain("event:error");
        assertThat(sse).contains("event:done");
        assertThat(sse).contains("\"status\":\"COMPLETED\"");

        mvc.perform(get("/api/v1/town/npc/GUIDE/messages").cookie(owner.access()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[1].role").value("ASSISTANT"))
            .andExpect(jsonPath("$.data[1].content").value("先挑一个最小的步骤，做完再看下一步。"))
            .andExpect(jsonPath("$.data[1].options.length()").value(0));
    }

    private Session register(String email) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/register")
                .contentType("application/json")
                .content("""
                    {"email":"%s","password":"Correct-Horse-Battery-2026!","displayName":"Town Owner","birthDate":"1990-01-01","timezone":"Asia/Shanghai","consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
                    """.formatted(email)))
            .andExpect(status().isCreated())
            .andReturn();
        return new Session(result.getResponse().getCookie("access_token"), result.getResponse().getCookie("csrf_token"));
    }

    private record Session(Cookie access, Cookie csrf) {
    }

    @TestConfiguration
    static class TruncatingProvider {

        @Bean
        @Primary
        QwenProvider truncatingProvider(QwenProvider delegate) {
            return new QwenProvider() {
                @Override
                public StructuredResult generateStructured(StructuredPrompt prompt) {
                    return delegate.generateStructured(prompt);
                }

                @Override
                public StreamMetadata stream(ChatPrompt prompt, Consumer<String> deltaConsumer) {
                    deltaConsumer.accept("先挑一个最小的步骤，");
                    deltaConsumer.accept("做完再看下一步。");
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_PROVIDER_UNAVAILABLE", "AI service is temporarily unavailable");
                }

                @Override
                public Classification classify(ClassificationPrompt prompt) {
                    return delegate.classify(prompt);
                }
            };
        }
    }
}
