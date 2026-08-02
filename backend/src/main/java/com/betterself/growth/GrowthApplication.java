package com.betterself.growth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class GrowthApplication {

    public static void main(String[] args) {
        SpringApplication.run(GrowthApplication.class, args);
    }
}
