package com.betterself.growth.auth;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        JwtAuthenticationFilter jwtAuthenticationFilter,
        CsrfDoubleSubmitFilter csrfDoubleSubmitFilter
    ) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers(
                    "/api/v1/auth/register",
                    "/api/v1/auth/login",
                    "/api/v1/auth/mfa/verify",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/password/forgot",
                    "/api/v1/auth/password/reset",
                    "/actuator/health/**"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED))
                .accessDeniedHandler((request, response, exception) ->
                    response.sendError(HttpServletResponse.SC_FORBIDDEN))
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(csrfDoubleSubmitFilter, JwtAuthenticationFilter.class);
        return http.build();
    }
}
