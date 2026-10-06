# SecureBank — Core Digital Banking & Transaction Management System

A production-style core banking backend: customer onboarding & KYC, Savings / Current / Fixed Deposit accounts,
ACID-safe fund transfers, double-entry ledger, beneficiary management, statements, Kafka-based fraud detection,
MFA, and a full audit trail.

**Stack:** Java 17 · Spring Boot 3.2 · Spring Security (OAuth2 Resource Server + JWT, BCrypt) · Spring Data JPA ·
Apache Kafka · Redis · MySQL 8 · Docker · Kubernetes · Jenkins · AWS · Swagger/OpenAPI 3 · JUnit 5 · Mockito · JaCoCo

> **Verification status (please read).** This project was generated without access to a Maven repository, so it has
> **not been compiled or executed in the generation environment**. It was written and cross-reviewed carefully, but you
> should run `mvn clean verify` first and fix any small compile/dependency nits that surface. The JaCoCo gate in `pom.xml`
> is set to 70 % line coverage by default; check `target/site/jacoco/index.html` after `mvn verify` and raise
> `jacoco.minimum` to `0.85` once your numbers support it. Do not quote a coverage figure you have not measured.

---

## 1. Features

| Area | What is implemented |
|---|---|
| Onboarding & KYC | Register, PAN/Aadhaar/address submission (AES-256-GCM encrypted at rest), admin approve/reject workflow, accounts only for `VERIFIED` customers |
| Accounts | Savings (3.5 %), Current (overdraft limit), Fixed Deposit (tenure-based rate, maturity lock), open / list / balance / close |
| Fund transfers | Pessimistic row locks + `SERIALIZABLE`, deterministic lock ordering (deadlock-free), retry on lock failures, per-transaction limit, beneficiary rule |
| Idempotency | Mandatory `Idempotency-Key` header, DB-unique key → client retries and concurrent duplicates debit exactly once |
| Ledger | Double-entry (every posting = 1 DEBIT + 1 CREDIT), immutable append-only tables, daily reconciliation job |
| Fraud detection | Kafka event stream → rule engine: high value, velocity (Redis sliding window), geo-inconsistency (impossible travel); alerts persisted, admin review |
| Security | JWT (HS256) via Spring OAuth2 Resource Server, BCrypt(12), Redis OTP MFA with TTL + attempt throttling, account lockout, role-based access |
| API quality | 28 REST endpoints, OpenAPI/Swagger UI, centralized exception handling, request-id correlated structured audit logging |
| Ops | Dockerfile, docker-compose, Kubernetes manifests (HPA, PDB, probes), Jenkins pipeline, AWS mapping |

## 2. Architecture

```
                     ┌──────────────────────────────────────────────────────────┐
  Client ──HTTPS──▶  │  Spring Boot (N pods)                                    │
  (JWT bearer)       │  Controllers ─▶ Services ─▶ Repositories                 │
                     │                    │                                     │
                     │   AuthService ─────┼──▶ Redis  (OTP, throttle, fraud     │
                     │   TransferService  │           windows, job lock)        │
                     │     └▶ TransferExecutor (SERIALIZABLE tx)                │
                     │          └▶ PostingService ─▶ MySQL (accounts,           │
                     │                                 transactions, ledger,    │
                     │   after-commit event            audit, alerts)           │
                     │        │                                                 │
                     │        ▼                                                 │
                     │   Kafka topic transactions.completed (3 partitions)      │
                     │        │                                                 │
                     │        ▼                                                 │
                     │   FraudDetectionConsumer ─▶ rules ─▶ fraud_alerts        │
                     │   ReconciliationJob (nightly, Redis-locked) ─▶ reports   │
                     └──────────────────────────────────────────────────────────┘
```

### Transfer sequence

```
POST /transfers  (Idempotency-Key)
  └ TransferService            (not transactional)
      1. lookup idempotency key → replay original result if present
      2. loop (max 3 attempts, back-off) ──▶ TransferExecutor.execute   [SERIALIZABLE tx]
            a. validate amount / limit
            b. SELECT … FOR UPDATE on both accounts, in lexicographic account-number order
            c. validate ownership, status, FD maturity, beneficiary  (on LOCKED, fresh rows)
            d. PostingService.post: balance check → update balances → INSERT transaction
               → INSERT DEBIT + CREDIT ledger entries
            e. publish TransactionEvent (delivered to Kafka only AFTER COMMIT)
      3. audit success / rejection; DataIntegrityViolation on idempotency key ⇒ replay winner
```

## 3. Project layout

```
securebank-core/
├── pom.xml, Dockerfile, docker-compose.yml, Jenkinsfile
├── k8s/                       namespace, configmap, secret.example, deployment, service, hpa+pdb
├── docs/
│   ├── demo.sh                end-to-end curl demo (register → KYC → transfer → fraud → reconcile)
│   └── sql/immutability-triggers.sql   DB-level append-only enforcement
└── src/main/java/com/securebank/
    ├── common/       ApiError, ErrorCode, BusinessException, GlobalExceptionHandler, CorrelationIdFilter
    ├── config/       typed @ConfigurationProperties, OpenAPI, Kafka topic, DataInitializer
    ├── security/     SecurityConfig, JwtConfig/Service, AesEncryptor + JPA converter, RestAuthHandler
    ├── auth/         register, login, OTP (Redis), AuthService
    ├── customer/     Customer entity, KYC workflow
    ├── account/      Account entity, AccountService, controller
    ├── beneficiary/  beneficiary CRUD (soft delete)
    ├── transaction/  BankTransaction, TransferService (idempotency+retry), TransferExecutor (ACID)
    ├── ledger/       LedgerEntry, PostingService (double entry), reconciliation service + job
    ├── statement/    ledger-derived statements (JSON + CSV)
    ├── fraud/        events, rules, consumer, alerts
    ├── audit/        append-only audit log + structured logging
    └── admin/        operations endpoints (KYC review, deposit, alerts, reconciliation)
```

## 4. Quick start

### Option A — everything in Docker
```bash
docker compose up --build        # MySQL, Redis, Kafka (KRaft), app
open http://localhost:8080/swagger-ui.html
./docs/demo.sh                   # needs curl, jq
```
The `dev` profile (set by compose) creates an admin: **admin@securebank.local / Admin@12345!** and prints OTPs to the app log:
```bash
docker compose logs app | grep "OTP for"
```

### Option B — run the app from your IDE
```bash
docker compose up -d mysql redis kafka
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
Without the `dev` profile the app refuses to start unless `JWT_SECRET` and `AES_KEY` are supplied (by design — no secrets in git):
```bash
export JWT_SECRET=$(openssl rand -base64 48)   # >= 32 bytes
export AES_KEY=$(openssl rand -base64 32)      # 256-bit
```

### Manual walk-through
1. `POST /api/v1/auth/register` → 2. `POST /auth/login` (returns `mfaRequired:true`, OTP sent) → 3. `POST /auth/verify-otp` → `accessToken`
4. `POST /customers/me/kyc` → admin `POST /admin/kyc/{id}/approve`
5. `POST /accounts` `{"accountType":"SAVINGS","initialDeposit":50000}`
6. `POST /beneficiaries` → 7. `POST /transfers` with header `Idempotency-Key: <8-64 chars>`
8. `GET /accounts/{n}/statement?from=2026-01-01&to=2026-01-31`

Click **Authorize** in Swagger UI and paste the `accessToken`.

## 5. API reference (28 endpoints, base `/api/v1`)

| # | Method & path | Auth | Purpose |
|---|---|---|---|
| 1 | `POST /auth/register` | public | Create customer |
| 2 | `POST /auth/login` | public | Password check; sends OTP (or token if MFA disabled) |
| 3 | `POST /auth/verify-otp` | public | Verify OTP, issue JWT |
| 4 | `GET /customers/me` | user | Profile (PII masked) |
| 5 | `POST /customers/me/kyc` | user | Submit KYC (PAN, Aadhaar, address) |
| 6 | `GET /customers/me/kyc` | user | KYC status |
| 7 | `POST /accounts` | user | Open Savings / Current / FD |
| 8 | `GET /accounts` | user | List my accounts |
| 9 | `GET /accounts/{n}` | user | Account details |
| 10 | `GET /accounts/{n}/balance` | user | Balance & available balance |
| 11 | `POST /accounts/{n}/close` | user | Close (zero balance only) |
| 12 | `GET /accounts/{n}/transactions` | user | Paged history |
| 13 | `POST /beneficiaries` | user | Add / re-activate beneficiary |
| 14 | `GET /beneficiaries` | user | List beneficiaries |
| 15 | `DELETE /beneficiaries/{id}` | user | Soft-delete |
| 16 | `POST /transfers` | user | **Transfer funds** (needs `Idempotency-Key`; optional `X-Client-Country`) |
| 17 | `GET /transfers/{reference}` | user | Transaction lookup |
| 18 | `GET /accounts/{n}/statement?from&to` | user | JSON statement |
| 19 | `GET /accounts/{n}/statement/csv?from&to` | user | CSV download |
| 20 | `GET /admin/kyc/pending` | admin | KYC queue |
| 21 | `POST /admin/kyc/{id}/approve` | admin | Approve |
| 22 | `POST /admin/kyc/{id}/reject` | admin | Reject with reason |
| 23 | `POST /admin/customers/{id}/unlock` | admin | Clear lockout |
| 24 | `POST /admin/accounts/{n}/deposit` | admin | Cash deposit (DR cash GL / CR customer) |
| 25 | `GET /admin/fraud-alerts?status=` | admin | Fraud alerts |
| 26 | `POST /admin/fraud-alerts/{id}/review` | admin | Mark reviewed |
| 27 | `POST /admin/reconciliation/run?date=` | admin | Run reconciliation now |
| 28 | `GET /admin/reconciliation/reports` | admin | Past reports |

Errors always look like:
```json
{"timestamp":"…","status":422,"code":"INSUFFICIENT_FUNDS","message":"Insufficient funds","path":"/api/v1/transfers","requestId":"…"}
```
Validation errors add `fieldErrors`. Replayed transfers return **200** with header `Idempotent-Replay: true`; first execution returns **201**.

## 6. Design deep-dives

### 6.1 Preventing double-spend and race conditions
* `TransferExecutor.execute` runs under `Isolation.SERIALIZABLE`.
* Both accounts are read with `SELECT … FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`), so a competing transfer blocks until commit.
* **Validation happens after the locks**, on the freshest committed balance — the classic check-then-act race cannot occur.
* **Lock ordering:** accounts are always locked in lexicographic order of account number, so `A→B` and `B→A` never form a wait cycle.
* If MySQL still picks a deadlock victim or a lock wait times out, Spring raises `ConcurrencyFailureException`; `TransferService` retries up to 3× with back-off. The `Account` entity also carries `@Version` as a second guard.
* `PostingService.post` re-checks `balance + overdraft ≥ amount` as the last line of defence.
* Tune `innodb_lock_wait_timeout` (e.g. 5–10 s) on the DB; the transaction timeout is 15 s.

### 6.2 Idempotent APIs
`transactions.idempotency_key` is `UNIQUE` and stores `"{customerId}:{Idempotency-Key}"`. Same key + same payload ⇒ the original result is returned; same key + different payload ⇒ `409 IDEMPOTENCY_CONFLICT`. Two simultaneous requests with the same key both pass the first lookup, but only one INSERT can win; the loser catches the integrity violation and replays the winner's result.

### 6.3 Double-entry ledger & reconciliation
* Each posting writes one `transactions` row and exactly two `ledger_entries` rows (DEBIT/CREDIT, same amount, `balance_after` snapshot).
* Cash enters/leaves the bank through a system **GL account** (`GL0000000001`), so even deposits are balanced; the GL balance is negative by the total deposits.
* Entities are Hibernate `@Immutable`; repositories expose **only insert + read**; `docs/sql/immutability-triggers.sql` adds DB triggers that reject UPDATE/DELETE. Corrections = reversing entries.
* `ReconciliationJob` (cron `0 30 0 * * *` UTC, guarded by a Redis lock so only one pod runs) verifies, in one `REPEATABLE_READ` snapshot:
  1. total debits = total credits for the date,
  2. every transaction nets to zero,
  3. every account's `balance` = Σcredits − Σdebits.
  
  Results are stored in `reconciliation_reports`, logged (`ERROR` on mismatch) and audited.

### 6.4 Fraud detection pipeline
`TransferExecutor` publishes a `TransactionEvent`; `TransactionEventPublisher` sends it to Kafka **after commit** (key = source account → per-account ordering). `FraudDetectionConsumer` runs every `FraudRule`:

| Rule | Logic | Default |
|---|---|---|
| `HIGH_VALUE` | amount ≥ threshold (HIGH if ≥ 2×) | ₹5,00,000 |
| `VELOCITY` | > N transfers per account in a sliding window (Redis ZSET) | 5 / 60 s |
| `GEO_INCONSISTENT` | customer's country changes within a window (`X-Client-Country`) | 30 min |

Alerts are de-duplicated per (transaction, rule) so Kafka redelivery is safe. Add a rule by writing a `@Component implements FraudRule`.
*Production hardening:* swap the after-commit publish for a transactional outbox, add a DLQ topic, and derive country from a GeoIP service instead of a client header.

### 6.5 Authentication & MFA
* Login = password (BCrypt cost 12) → OTP → JWT. Unknown e-mail performs a dummy BCrypt compare to flatten timing.
* OTP: 6 digits from `SecureRandom`; only a SHA-256 hash is stored in Redis (`otp:code:*`, TTL 5 min). Wrong guesses increment `otp:attempts:*`; after 3 the identity is blocked for 15 min (`otp:block:*`) and the code is destroyed.
* Lockout: 5 consecutive wrong passwords lock the account 30 min (persisted even though the request fails — `noRollbackFor`). Admin can unlock.
* JWT is validated by Spring's OAuth2 Resource Server (issuer + expiry + signature); roles come from the `roles` claim. Swap `JwtConfig` for an RS256/JWKS decoder to integrate with an external IdP (Keycloak, Cognito).
* Replace `LoggingOtpSender` with an SES/SNS/Twilio implementation of `OtpSender` for real delivery.

### 6.6 PII protection & audit
* PAN, Aadhaar and address use a JPA `AttributeConverter` → AES-256-GCM with a random 96-bit IV per value; the key comes from `AES_KEY` (store in AWS Secrets Manager/KMS). API responses only return masked values.
* Every security- or money-relevant action calls `AuditService`, which writes an `AUDIT` log line (`action, actor, entity, outcome, ip, requestId`) **and** an immutable `audit_logs` row in a `REQUIRES_NEW` transaction, so rejected/failed operations are also recorded.
* `CorrelationIdFilter` puts `requestId` / `clientIp` into the MDC and returns `X-Request-Id`.

## 7. Data model (main tables)

`customers` · `accounts` (unique `account_number`, `@Version`) · `transactions` (unique `reference`, unique `idempotency_key`) ·
`ledger_entries` (indexed by account/date and transaction) · `beneficiaries` (unique customer+account) ·
`fraud_alerts` (unique txn+rule) · `audit_logs` · `reconciliation_reports`.
Tables are generated by Hibernate (`ddl-auto=update` in dev, `validate` in `prod`). For production add Flyway/Liquibase migrations and run `immutability-triggers.sql`.

## 8. Configuration reference (`application.yml`)

| Property | Default | Notes |
|---|---|---|
| `securebank.jwt.secret` | env `JWT_SECRET` | ≥ 32 bytes |
| `securebank.jwt.access-token-minutes` | 15 | |
| `securebank.crypto.aes-key` | env `AES_KEY` | base64, 32 bytes |
| `securebank.otp.*` | 6 digits, 300 s TTL, 3 attempts, 900 s block | `log-plaintext` only in dev |
| `securebank.lockout.*` | 5 attempts, 30 min | |
| `securebank.bank.per-transaction-limit` | 1,000,000 | |
| `securebank.bank.current-overdraft-limit` | 10,000 | |
| `securebank.fraud.*` | see §6.4 | |
| `securebank.reconciliation.cron` / `.enabled` | `0 30 0 * * *` / true | |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS` | local defaults | |

Business rules: min opening deposit — Savings ₹500, Current ₹5,000, FD ₹10,000 (tenure 6–120 months; rate 6.5 % ≤12 m, 7.0 % ≤36 m, 7.25 % above). FDs cannot be debited before maturity or credited after opening.

## 9. Testing & coverage

```bash
mvn clean verify                 # unit tests + JaCoCo report + coverage gate
open target/site/jacoco/index.html
mvn verify -Pintegration         # Testcontainers MySQL concurrency tests (needs Docker)
```
**Unit tests (JUnit 5 + Mockito):** AES/GCM, OTP (TTL, throttling, blocking), auth & lockout, all three fraud rules + alert de-duplication, `PostingService` (double entry, lock order, overdraft, GL), `TransferExecutor` (every validation), `TransferService` (idempotent replay, payload conflict, race loser, retry/give-up), account rules, reconciliation (balanced / tampered / unbalanced), statements & CSV-injection guard, KYC & beneficiaries, and a MockMvc test of the transfer controller + error contract.

**Integration tests (`*IT`, real MySQL via Testcontainers):** 25 concurrent transfers can never overdraw or create money; 10 simultaneous requests with one idempotency key debit once; opposite-direction transfers do not deadlock; reconciliation reports `BALANCED`.

Coverage: `jacoco-maven-plugin` excludes DTO holders, `config/` and the main class and enforces `jacoco.minimum` (line coverage). Measure it, then raise the gate — add controller/`OtpService`/admin tests if you need more headroom.

## 10. Deployment

**Docker:** multi-stage build, non-root JRE image (`docker build -t securebank-core .`).

**Kubernetes (`k8s/`):** `kubectl apply -f k8s/namespace.yaml -f k8s/configmap.yaml`, create the secret (see `secret.example.yaml`), then `deployment.yaml service.yaml hpa.yaml`. 3 replicas, rolling update with zero unavailable, startup/liveness/readiness probes on Actuator, read-only root FS, non-root, HPA on CPU, PDB `minAvailable: 2`.

**Jenkins (`Jenkinsfile`):** build + unit tests + JaCoCo gate → (main) Testcontainers ITs → Docker build → push to ECR → `kubectl set image` on EKS + rollout check.

**AWS mapping:** ECR (images) · EKS (runtime) · RDS MySQL Multi-AZ · ElastiCache Redis · MSK (Kafka) · Secrets Manager/KMS (`JWT_SECRET`, `AES_KEY`, DB creds via External Secrets) · SES/SNS (OTP delivery) · ALB/Ingress + WAF · CloudWatch/OpenSearch (JSON-structured logs).

## 11. Known limitations & next steps

* Interest accrual, FD maturity payout, standing instructions and inter-bank rails (NEFT/IMPS/UPI) are not implemented.
* Reconciliation loads all accounts; for very large books switch to chunked/partitioned runs or per-day checkpoints.
* The cash GL account is a hot row for deposits; shard it into several GL accounts at scale.
* Kafka publish is after-commit best-effort (logged on failure); use a transactional outbox for guaranteed delivery.
* OTP delivery is a logging stub; JWT has no refresh/revocation list (15-min access tokens) — add refresh tokens + Redis deny-list if required.
* Add rate limiting at the gateway, Flyway migrations, and field-level key rotation (key-id prefix in ciphertext).

## 12. Resume bullet → code map

| Bullet | Where |
|---|---|
| Secure core banking backend | all modules; `customer/`, `account/`, `beneficiary/`, `statement/` |
| ACID transfer engine, pessimistic locking + serializable | `transaction/TransferExecutor`, `ledger/PostingService.lock`, `account/AccountRepository.findForUpdate`, `it/TransferConcurrencyIT` |
| Double-entry ledger, immutable records, daily reconciliation | `ledger/*`, `transaction/BankTransaction`, `docs/sql/immutability-triggers.sql` |
| Event-driven fraud detection on Kafka | `fraud/*` (`TransactionEventPublisher`, `FraudDetectionConsumer`, rules) |
| MFA OTP (Redis TTL + throttling), BCrypt, lockout, AES PII, idempotent APIs | `auth/OtpService`, `auth/AuthService`, `security/AesEncryptor`, `transaction/TransferService` |
| 20+ documented REST APIs, central exception handling, audit logging | §5 table, `common/GlobalExceptionHandler`, `audit/*`, `config/AppConfig` (OpenAPI) |
| Docker / Kubernetes / Jenkins / AWS | `Dockerfile`, `docker-compose.yml`, `k8s/`, `Jenkinsfile`, §10 |
# securebank-digital-banking_System
