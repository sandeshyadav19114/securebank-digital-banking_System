package com.securebank.transaction;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securebank.common.BusinessException;
import com.securebank.common.ErrorCode;
import com.securebank.common.GlobalExceptionHandler;
import com.securebank.transaction.TransferDtos.TransactionResponse;
import com.securebank.transaction.TransferDtos.TransferResult;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TransferControllerTest {

    TransferService service = mock(TransferService.class);
    TransactionQueryService query = mock(TransactionQueryService.class);
    MockMvc mvc;

    static final String BODY = "{\"fromAccount\":\"SB0000000001\",\"toAccount\":\"SB0000000002\",\"amount\":150.50,\"remarks\":\"rent\"}";

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TransferController(service, query))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("5").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private TransactionResponse response() {
        return new TransactionResponse("TXN-1", TransactionType.TRANSFER, TransactionStatus.COMPLETED,
                "SB0000000001", "SB0000000002", new BigDecimal("150.50"), "INR", "rent", Instant.now());
    }

    @Test
    void createsTransfer() throws Exception {
        when(service.transfer(eq(5L), any(), eq("key-12345"), any())).thenReturn(new TransferResult(response(), false));
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-12345").header("X-Client-Country", "in"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("TXN-1"));
    }

    @Test
    void replayReturns200WithHeader() throws Exception {
        when(service.transfer(eq(5L), any(), eq("key-12345"), any())).thenReturn(new TransferResult(response(), true));
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-12345"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void invalidBodyReturnsFieldErrors() throws Exception {
        String bad = "{\"fromAccount\":\"x\",\"toAccount\":\"SB0000000002\",\"amount\":-5}";
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(bad)
                        .header("Idempotency-Key", "key-12345"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.amount").exists())
                .andExpect(jsonPath("$.fieldErrors.fromAccount").exists());
    }

    @Test
    void businessErrorMapsToHttpStatus() throws Exception {
        when(service.transfer(any(), any(), any(), any()))
                .thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds"));
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-12345"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void unexpectedErrorDoesNotLeakDetails() throws Exception {
        when(service.transfer(any(), any(), any(), any())).thenThrow(new IllegalStateException("db password is hunter2"));
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Idempotency-Key", "key-12345"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Unexpected error"));
    }
}
