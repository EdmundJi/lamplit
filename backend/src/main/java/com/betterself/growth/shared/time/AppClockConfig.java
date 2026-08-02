package com.betterself.growth.shared.time;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppClockConfig {

    @Bean
    Clock appClock() {
        return Clock.systemUTC();
    }
}
