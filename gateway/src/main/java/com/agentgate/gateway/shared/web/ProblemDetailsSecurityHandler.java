package com.agentgate.gateway.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Security errors happen in filters, BEFORE any controller runs, so ApiExceptionHandler never sees them.
 * This writes 401/403 as RFC 9457 Problem Details too (course rule C3), so every error looks the same.
 *
 * {@code challenge} adds the standard WWW-Authenticate header (Basic or Bearer) that tells the client how to log in.
 */
public class ProblemDetailsSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final AuthenticationEntryPoint challenge;

    public ProblemDetailsSecurityHandler(ObjectMapper objectMapper, AuthenticationEntryPoint challenge) {
        this.objectMapper = objectMapper;
        this.challenge = challenge;
    }

    /** For HTTP Basic. (Spring's BasicAuthenticationEntryPoint calls sendError, which would drop our body.) */
    public static AuthenticationEntryPoint basicChallenge(String realm) {
        return (request, response, ex) ->
                response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + realm + "\"");
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException, jakarta.servlet.ServletException {
        challenge.commence(request, response, ex);
        write(response, request, HttpStatus.UNAUTHORIZED, "Missing or invalid credentials");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        write(response, request, HttpStatus.FORBIDDEN, "You are not allowed to use this endpoint");
    }

    private void write(HttpServletResponse response, HttpServletRequest request, HttpStatus status, String detail)
            throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        problem.put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
