package com.securebank.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.securebank.account.Account;
import com.securebank.account.AccountRepository;
import com.securebank.account.AccountType;
import com.securebank.common.BusinessException;
import com.securebank.common.ClientContext;
import com.securebank.customer.Customer;
import com.securebank.customer.CustomerRepository;
import com.securebank.fraud.TransactionEvent;
import com.securebank.ledger.PostingService;
import com.securebank.ledger.ReconciliationReport;
import com.securebank.ledger.ReconciliationService;
import com.securebank.transaction.TransferDtos.TransferRequest;
import com.securebank.transaction.TransferDtos.TransferResult;
import com.securebank.transaction.TransferService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the money-movement guarantees against a REAL MySQL (InnoDB) instance.
 * Run with:  mvn verify -Pintegration     (requires Docker)
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
        "securebank.reconciliation.enabled=false",
        "spring.kafka.listener.auto-startup=false",
        "spring.jpa.hibernate.ddl-auto=create",
        "securebank.jwt.secret=integration-test-secret-integration-test-secret",
        "securebank.crypto.aes-key=N8ZG1X9U3/lmq/1QO0R3qJY/4TIaKN9KNgm8XGF51uA="})
class TransferConcurrencyIT {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }

    @MockBean StringRedisTemplate redis;
    @MockBean KafkaAdmin kafkaAdmin;
    @MockBean KafkaTemplate<String, TransactionEvent> kafka;

    @Autowired TransferService transferService;
    @Autowired AccountRepository accounts;
    @Autowired CustomerRepository customers;
    @Autowired PostingService posting;
    @Autowired ReconciliationService reconciliation;
    @Autowired PlatformTransactionManager txManager;

    Customer customer;
    String accountA;
    String accountB;
    final ClientContext ctx = new ClientContext("127.0.0.1", "IN");

    @BeforeEach
    void seed() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        Customer c = new Customer();
        c.setFullName("Test");
        c.setEmail(UUID.randomUUID() + "@test.com");
        c.setPhone("9999999999");
        c.setPasswordHash("x");
        customer = customers.save(c);
        accountA = newAccount();
        accountB = newAccount();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> posting.postDeposit(accountA, new BigDecimal("1000.00"), "seed", customer.getId()));
    }

    private String newAccount() {
        Account a = new Account();
        a.setAccountNumber("SB" + String.format("%012d", Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000_000_000L));
        a.setCustomerId(customer.getId());
        a.setAccountType(AccountType.SAVINGS);
        return accounts.save(a).getAccountNumber();
    }

    private BigDecimal balance(String number) {
        return accounts.findByAccountNumber(number).orElseThrow().getBalance();
    }

    private List<Object> runConcurrently(List<Callable<TransferResult>> jobs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransferResult>> futures = new ArrayList<>();
        for (Callable<TransferResult> job : jobs) {
            futures.add(pool.submit(() -> {
                start.await();
                return job.call();
            }));
        }
        start.countDown();
        List<Object> outcomes = new ArrayList<>();
        for (Future<TransferResult> f : futures) {
            try {
                outcomes.add(f.get(60, TimeUnit.SECONDS));
            } catch (Exception e) {
                outcomes.add(e.getCause() == null ? e : e.getCause());
            }
        }
        pool.shutdownNow();
        return outcomes;
    }

    private TransferRequest req(String from, String to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount), "it");
    }

    @Test
    void concurrentTransfersCanNeverOverdrawOrCreateMoney() throws Exception {
        List<Callable<TransferResult>> jobs = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            String key = "key-" + UUID.randomUUID();
            jobs.add(() -> transferService.transfer(customer.getId(), req(accountA, accountB, "100.00"), key, ctx));
        }
        List<Object> outcomes = runConcurrently(jobs);

        long successes = outcomes.stream().filter(o -> o instanceof TransferResult).count();
        assertThat(successes).isBetween(1L, 10L);                         // 1000 / 100 => at most 10 can ever succeed
        assertThat(balance(accountA)).isEqualByComparingTo(new BigDecimal("1000.00").subtract(BigDecimal.valueOf(100 * successes)));
        assertThat(balance(accountB)).isEqualByComparingTo(BigDecimal.valueOf(100 * successes));
        assertThat(balance(accountA).signum()).isGreaterThanOrEqualTo(0);
        outcomes.stream().filter(o -> !(o instanceof TransferResult))
                .forEach(o -> assertThat(o).isInstanceOf(BusinessException.class));   // clean business errors only
    }

    @Test
    void sameIdempotencyKeyDebitsExactlyOnce() throws Exception {
        String key = "idem-" + UUID.randomUUID();
        List<Callable<TransferResult>> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            jobs.add(() -> transferService.transfer(customer.getId(), req(accountA, accountB, "100.00"), key, ctx));
        }
        List<Object> outcomes = runConcurrently(jobs);

        Set<String> references = new HashSet<>();
        outcomes.stream().filter(o -> o instanceof TransferResult)
                .forEach(o -> references.add(((TransferResult) o).transaction().reference()));
        assertThat(references).hasSize(1);
        assertThat(balance(accountA)).isEqualByComparingTo("900.00");
        assertThat(balance(accountB)).isEqualByComparingTo("100.00");
    }

    @Test
    void oppositeDirectionTransfersDoNotDeadlock() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(s -> posting.postDeposit(accountB, new BigDecimal("1000.00"), "seed", customer.getId()));
        List<Callable<TransferResult>> jobs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String k1 = "ab-" + UUID.randomUUID();
            String k2 = "ba-" + UUID.randomUUID();
            jobs.add(() -> transferService.transfer(customer.getId(), req(accountA, accountB, "10.00"), k1, ctx));
            jobs.add(() -> transferService.transfer(customer.getId(), req(accountB, accountA, "10.00"), k2, ctx));
        }
        runConcurrently(jobs);   // would time out (60s) if a lock cycle existed
        assertThat(balance(accountA).add(balance(accountB))).isEqualByComparingTo("2000.00");
    }

    @Test
    void reconciliationConfirmsLedgerMatchesBalances() throws Exception {
        transferService.transfer(customer.getId(), req(accountA, accountB, "250.00"), "rec-" + UUID.randomUUID(), ctx);
        ReconciliationReport report = reconciliation.run(LocalDate.now(ZoneOffset.UTC));
        assertThat(report.getStatus()).isEqualTo(ReconciliationReport.Status.BALANCED);
        assertThat(report.getTotalDebits()).isEqualByComparingTo(report.getTotalCredits());
    }
}
