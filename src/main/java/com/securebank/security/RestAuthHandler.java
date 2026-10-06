package com.securebank.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securebank.common.ApiError;
import com.securebank.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** JSON 401 / 403 responses for failures that happen inside the security filter chain. */
@Component
@RequiredArgsConstructor
public class RestAuthHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) throws IOException {
        write(res, ApiError.of(ErrorCode.UNAUTHORIZED, "Authentication required or token invalid",
                req.getRequestURI(), MDC.get("requestId")));
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex) throws IOException {
        write(res, ApiError.of(ErrorCode.FORBIDDEN, "Access denied", req.getRequestURI(), MDC.get("requestId")));
    }

    private void write(HttpServletResponse res, ApiError error) throws IOException {
        res.setStatus(error.status());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), error);
    }
}
