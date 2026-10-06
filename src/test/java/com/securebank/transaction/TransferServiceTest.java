package com.securebank.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securebank.audit.AuditService;
import com.securebank.common.BusinessException;
import com.securebank.common.ClientContext;
import com.securebank.common.ErrorCode;
import com.securebank.transaction.TransferDtos.TransferRequest;
import com.securebank.transaction.TransferDtos.TransferResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;

class TransferServiceTest {

    TransferExecutor executor = mock(TransferExecutor.class);
    TransactionRepository repo = mock(TransactionRepository.class);
    AuditService audit = mock(AuditService.class);
    TransferService service;
    ClientContext ctx = new ClientContext("1.1.1.1", "IN");
    TransferRequest request = new TransferRequest("AAAAAAAA1", "BBBBBBBB1", new BigDecimal("100.00"), "x");

    @BeforeEach
    void setUp() {
        service = new TransferService(executor, repo, audit);
    }

    private BankTransaction tx(String from, String to, String amount) {
        return BankTransaction.builder().reference("TXN-1").type(TransactionType.TRANSFER)
                .status(TransactionStatus.COMPLETED).fromAccount(from).toAccount(to)
                .amount(new BigDecimal(amount)).currency("INR").createdAt(Instant.now()).build();
    }

    @Test
    void freshRequestExecutesAndAudits() {
        when(repo.findByIdempotencyKey("9:key-12345")).thenReturn(Optional.empty());
        when(executor.execute(eq(9L), eq(request), eq("9:key-12345"), eq(ctx))).thenReturn(tx("AAAAAAAA1", "BBBBBBBB1", "100.00"));
        TransferResult r = service.transfer(9L, request, "key-12345", ctx);
        assertThat(r.replayed()).isFalse();
        assertThat(r.transaction().reference()).isEqualTo("TXN-1");
        verify(audit).record(anyString(), eq("TRANSFER"), anyString(), eq("TXN-1"), eq("SUCCESS"), anyString());
    }

    @Test
    void duplicateKeyReplaysOriginalWithoutMovingMoney() {
        when(repo.findByIdempotencyKey("9:key-12345")).thenReturn(Optional.of(tx("AAAAAAAA1", "BBBBBBBB1", "100.00")));
        TransferResult r = service.transfer(9L, request, "key-12345", ctx);
        assertThat(r.replayed()).isTrue();
        verify(executor, never()).execute(any(), any(), anyString(), any());
    }

    @Test
    void sameKeyDifferentPayloadIsConflict() {
        when(repo.findByIdempotencyKey("9:key-12345")).thenReturn(Optional.of(tx("AAAAAAAA1", "BBBBBBBB1", "999.00")));
        assertThatThrownBy(() -> service.transfer(9L, request, "key-12345", ctx))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    void concurrentDuplicateLosingTheRaceReplaysWinner() {
        when(repo.findByIdempotencyKey("9:key-12345"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(tx("AAAAAAAA1", "BBBBBBBB1", "100.00")));
        when(executor.execute(any(), any(), anyString(), any())).thenThrow(new DataIntegrityViolationException("dup"));
        assertThat(service.transfer(9L, request, "key-12345", ctx).replayed()).isTrue();
    }

    @Test
    void unrelatedIntegrityViolationIsRethrown() {
        when(repo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(executor.execute(any(), any(), anyString(), any())).thenThrow(new DataIntegrityViolationException("other"));
        assertThatThrownBy(() -> service.transfer(9L, request, "key-12345", ctx))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deadlockIsRetriedThenSucceeds() {
        when(repo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(executor.execute(any(), any(), anyString(), any()))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(tx("AAAAAAAA1", "BBBBBBBB1", "100.00"));
        assertThat(service.transfer(9L, request, "key-12345", ctx).replayed()).isFalse();
        verify(executor, times(2)).execute(any(), any(), anyString(), any());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        when(repo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(executor.execute(any(), any(), anyString(), any())).thenThrow(new CannotAcquireLockException("deadlock"));
        assertThatThrownBy(() -> service.transfer(9L, request, "key-12345", ctx))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(ErrorCode.CONCURRENCY_CONFLICT));
        verify(executor, times(TransferService.MAX_ATTEMPTS)).execute(any(), any(), anyString(), any());
    }

    @Test
    void businessRejectionIsAuditedAndRethrown() {
        when(repo.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(executor.execute(any(), any(), anyString(), any()))
                .thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_FUNDS, "no money"));
        assertThatThrownBy(() -> service.transfer(9L, request, "key-12345", ctx)).isInstanceOf(BusinessException.class);
        verify(audit).record(anyString(), eq("TRANSFER"), anyString(), anyString(), eq("REJECTED_INSUFFICIENT_FUNDS"), anyString());
    }
}
