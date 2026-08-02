package com.betterself.growth.shared.web;

import com.betterself.growth.shared.id.PublicIdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-ID";
    public static final String ATTRIBUTE = "requestId";
    private static final Pattern ULID = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

    private final PublicIdGenerator idGenerator;

    public RequestIdFilter(PublicIdGenerator idGenerator) {
        this.idGenerator = idGenerator;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = valid(incoming) ? incoming.toUpperCase(Locale.ROOT) : idGenerator.next();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(ATTRIBUTE, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(ATTRIBUTE);
        }
    }

    private boolean valid(String value) {
        return value != null && ULID.matcher(value.toUpperCase(Locale.ROOT)).matches();
    }
}
