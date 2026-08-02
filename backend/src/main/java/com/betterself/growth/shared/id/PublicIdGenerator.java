package com.betterself.growth.shared.id;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Clock;

@Component
public class PublicIdGenerator {

    private static final char[] ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PublicIdGenerator(Clock clock) {
        this.clock = clock;
    }

    public String next() {
        char[] value = new char[26];
        long timestamp = clock.millis();
        for (int index = 9; index >= 0; index--) {
            value[index] = ENCODING[(int) (timestamp & 31)];
            timestamp >>>= 5;
        }
        for (int index = 10; index < value.length; index++) {
            value[index] = ENCODING[random.nextInt(32)];
        }
        return new String(value);
    }
}
