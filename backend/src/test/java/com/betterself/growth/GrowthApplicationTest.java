package com.betterself.growth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.MySQLDialect",
    "app.security.jwt-secret=test-only-secret-at-least-thirty-two-bytes",
    "app.security.mfa-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class GrowthApplicationTest {

    @Test
    void contextLoads() {
    }
}
