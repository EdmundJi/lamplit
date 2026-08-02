package com.betterself.growth.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final JdbcTemplate jdbc;

    public JwtAuthenticationFilter(JwtService jwtService, JdbcTemplate jdbc) {
        this.jwtService = jwtService;
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String token = cookie(request, CookieFactory.ACCESS_COOKIE);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                CurrentUser user = jwtService.parse(token);
                String status = jdbc.query(
                    "select status from sys_user where id = ?",
                    resultSet -> resultSet.next() ? resultSet.getString(1) : null,
                    user.id()
                );
                boolean deletionRoute = request.getRequestURI().startsWith("/api/v1/privacy/deletion");
                if (!"ACTIVE".equals(status) && !("DELETION_PENDING".equals(status) && deletionRoute)) {
                    throw new IllegalStateException("Account is not active");
                }
                var authentication = new UsernamePasswordAuthenticationToken(
                    user,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (RuntimeException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }

    static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
            .filter(cookie -> name.equals(cookie.getName()))
            .map(Cookie::getValue)
            .findFirst()
            .orElse(null);
    }
}
