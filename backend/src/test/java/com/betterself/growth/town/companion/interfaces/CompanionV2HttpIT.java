package com.betterself.growth.town.companion.interfaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Real TCP + auth + persistence smoke, against an isolated disposable database and no paid model.
 * The exported public response also feeds the browser test, avoiding a second hand-written town. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.ai.provider=mock"})
class CompanionV2HttpIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access", () -> true);
        registry.add("app.security.jwt-secret", () -> "test-only-secret-at-least-thirty-two-bytes");
        registry.add("app.security.mfa-encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.security.secure-cookies", () -> false);
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper mapper;
    final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
        .connectTimeout(Duration.ofSeconds(10)).build();

    @Test void authenticatedTownSurvivesReloadAndExportsItsRealMapForTheBrowser() throws Exception {
        request("POST", "/auth/register", """
            {"email":"town-v2-http@example.test","password":"Town-v2-Http-Safe-2026!",
             "displayName":"地图验收","birthDate":"1994-04-18","timezone":"Asia/Shanghai",
             "consents":{"terms":"2026-07","privacy":"2026-07","ai":"2026-07"}}
            """, 201);
        assertThat(request("GET", "/town/companion", null, 200).path("joined").asBoolean()).isFalse();
        JsonNode joined = request("POST", "/town/companion/join",
            "{\"name\":\"地图验收\",\"timezone\":\"Asia/Shanghai\"}", 200);
        JsonNode world = joined.path("world");
        assertThat(world.path("residents").size()).isEqualTo(25);
        Set<String> locations = new HashSet<>();
        world.path("locations").forEach(place -> locations.add(place.path("id").asText()));
        assertThat(locations).contains("cafe", "garden", "academy", "gym", "board", "shop");
        assertThat(world.path("rooms").size()).isGreaterThan(25);
        Set<String> roomIds = new HashSet<>();
        world.path("rooms").forEach(room -> roomIds.add(room.path("id").asText()));
        world.path("positions").forEach(position -> {
            assertThat(locations).contains(position.path("place").asText());
            assertThat(roomIds).contains(position.path("roomId").asText());
            assertThat(position.path("condition").asText()).isIn("usable", "broken");
        });
        world.path("residentStates").forEach(state -> assertThat(roomIds).contains(state.path("roomId").asText()));
        // "谁在用什么" (docs/04-decisions.md 2026-09-14 「对话出口与物品使用状态」): activitySince is a
        // plain field on the same wire shape already sent whole, so the frontend can derive how long a
        // resident has been at their current activity without a second DTO - present (though possibly
        // still null right after join, before anyone's activity has actually changed) rather than
        // silently missing.
        world.path("residentStates").forEach(state -> assertThat(state.has("activitySince")).isTrue());
        world.path("objects").forEach(object -> {
            assertThat(locations).contains(object.path("place").asText());
            assertThat(roomIds).contains(object.path("roomId").asText());
            if(!object.path("ownerId").isNull())assertThat(object.path("holderId").asText()).isNotBlank();
        });
        JsonNode advanced = request("POST", "/town/companion/advance", "{}", 200);
        JsonNode reloaded = request("GET", "/town/companion", null, 200);
        assertThat(reloaded.path("world").path("id")).isEqualTo(world.path("id"));
        assertThat(reloaded.path("world").path("residents").size()).isEqualTo(25);
        assertThat(reloaded.path("world").path("revision").asLong())
            .isGreaterThanOrEqualTo(advanced.path("world").path("revision").asLong());
        JsonNode repeatedJoin = request("POST", "/town/companion/join",
            "{\"name\":\"不能覆盖原存档\",\"timezone\":\"UTC\"}", 200);
        assertThat(repeatedJoin.path("world").path("id")).isEqualTo(world.path("id"));
        assertThat(repeatedJoin.path("world").path("avatar").path("name").asText()).isEqualTo("地图验收");
        Files.createDirectories(Path.of("target"));
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/town-v2-http-world.json").toFile(), reloaded);
    }

    private JsonNode request(String method, String path, String body, int expectedStatus) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        cookies.getCookieStore().getCookies().stream().filter(cookie -> "csrf_token".equals(cookie.getName()))
            .findFirst().ifPresent(cookie -> builder.header("X-CSRF-Token", cookie.getValue()));
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s", method, path).isEqualTo(expectedStatus);
        return mapper.readTree(response.body()).path("data");
    }
}
