# Design decisions

A step-by-step walk through the decisions in this codebase and **why** each was made. The guiding
constraint from the task is *"keep the solution simple and focused"* (senior effort 3–5h; no auth,
frontend, cloud, or pagination), so every decision is weighed against that.

---

## 1. Contract-first API (OpenAPI)

**Decision.** The API is defined in [`openapi/payment-api.yaml`](src/main/resources/openapi/payment-api.yaml);
the request/response DTOs and the `PaymentsApi` interface are **generated at build time**, and
`PaymentController` implements that generated interface.

**Why.** One source of truth. The spec drives the Java types, the request validation, and the Swagger
UI, so documentation and code cannot drift. Changing the API becomes a deliberate edit to the
contract, which a reviewer can diff.

## 2. Layered architecture with a DTO boundary

**Decision.** `Controller → Service → Repository`, plus a MapStruct `PaymentMapper` that converts the
`Payment` entity to the response DTO.

**Why.** Separation of concerns and testability: the web layer, business logic, and persistence are
each testable in isolation. The JPA entity is never serialized over the wire — the API contract is
independent of the database schema, so persistence changes don't leak to clients.

## 3. Persistence: PostgreSQL + Flyway, explicit entity design

**Decision.** PostgreSQL (H2 only for the `test` profile), schema owned by **Flyway** migrations, and
Hibernate `ddl-auto` set to `none`/`validate` (never `update`). Entity: `UUID` id, `BigDecimal`
amount, `String`-length-limited fields, enum `status`.

**Why.**
- Flyway gives a **versioned, reviewable, reproducible** schema; Hibernate validating (not
  generating) DDL means the schema never changes by surprise.
- `UUID` id: safe to expose, needs no central sequence, generated on insert.
- `BigDecimal` amount: money must be exact — floating point is not.

## 4. Status is backend-managed

**Decision.** `PaymentRequest` has **no `status` field**; `PaymentService.create` always sets
`CREATED`.

**Why.** The task rules: *"status is managed by the backend"* and *"new payment status should be
CREATED."* A client cannot inject a status.

## 5. Validation declared in the contract

**Decision.** Bean Validation on the generated request DTO — `amount` `minimum: 0, exclusiveMinimum`,
`currency` exactly 3 chars, debtor/creditor non-blank — enforced automatically → `400 Bad Request`.

**Why.** The task rules *"amount must be > 0"* and *"validate input data."* Declaring it in the OpenAPI
schema keeps validation in the same single source of truth (decision 1), rather than hand-written
`if` checks.

## 6. Update is a status state machine

**Decision.** Only a `CREATED` payment can be updated (and doing so transitions it to `COMPLETED`);
updating a `COMPLETED` or `FAILED` payment is rejected with `409 Conflict`.

**Why.** The task's *"prevent invalid updates (e.g. modifying a completed payment)."* Terminal states
stay terminal, so the lifecycle can't be corrupted.

## 7. Duplicate create → `409 Conflict`, not a `FAILED` record

**Decision.** A create whose debtor/creditor/amount/currency matches an existing payment is rejected
with `409` (body carries `existingPaymentId`); **nothing is persisted**. See
`DuplicatePaymentException` + `PaymentExceptionHandler`.

**Why.** A duplicate submission is a *conflict*, not a *failed payment*. Recording it as `FAILED`
would overload the status, pollute the table with rows per retry, and rob `FAILED` of its real
meaning ("the payment could not be completed"). `409` states exactly what happened. (Duplicate
detection itself is beyond the spec, so it's kept to a single query.)

## 8. Optimistic locking on updates

**Decision.** `Payment` has a `@Version` column; `update` calls `saveAndFlush`; a concurrent-update
conflict throws `ObjectOptimisticLockingFailureException`, mapped to `409`.

**Why.** Prevents **lost updates**: if two requests update the same payment at once, exactly one wins
and the other gets `409` — for only a few lines of code, this hardens the spec's "prevent invalid
updates" under real traffic.
- **Optimistic, not pessimistic:** conflicts on a single payment are rare, so it's cheaper to detect
  a clash at write time than to hold a row lock.
- **`saveAndFlush`:** forces the version check to run *inside* the method so the failure surfaces
  cleanly as `409`, rather than being deferred to transaction commit.

## 9. Centralised, standard error handling

**Decision.** A single `@RestControllerAdvice` maps exceptions to **RFC-7807 `application/problem+json`**:
`400` (validation / malformed / bad UUID), `404` (not found), `409` (duplicate / invalid update /
concurrent update), `500` (fallback).

**Why.** Consistent, machine-readable errors defined in one place; controllers and services stay free
of error-formatting code.

## 10. Auditing built in

**Decision.** JPA auditing populates `createdAt/createdBy/modifiedAt/modifiedBy`; the auditor comes
from an `AuditorAware<String>` bean (currently `"system"`).

**Why.** A cheap, always-on audit trail. When authentication is added, only the `AuditorAware` bean
changes — it returns the authenticated principal, and every row is attributed automatically.

## 11. Containerised DB + one-command startup

**Decision.** A multi-stage `Dockerfile` (Maven build → slim JRE) and a `docker-compose.yaml`
(app + PostgreSQL) so `docker compose up --build` builds the image and starts everything.

**Why.** The task's persistence option "database in a container" plus its requirement that a single
command builds the image and starts all containers ready to use.

## 12. Layered testing strategy

**Decision.**
- `PaymentControllerTest` — `@WebMvcTest` slice, service mocked: status-code/mapping behaviour
  (`201`, `400`, `404`, `409` ×3, `500`).
- `PaymentIntegrationTest` — `@SpringBootTest` against a **Testcontainers PostgreSQL**, running the
  real Flyway migrations: full lifecycle, duplicate `409`, invalid-update `409`, and a **deterministic
  optimistic-locking** conflict.
- `PaymentMapperTest`, `PaymentApplicationTests`, the **Bruno** HTTP collection, and
  `scripts/concurrency-check.sh`. A JaCoCo gate enforces **80% line coverage**.

**Why.** Each layer is tested with the cheapest tool that's still meaningful: fast slices for wiring,
real-DB integration for behaviour (Testcontainers over H2 so we test the **actual engine** used in
production), black-box HTTP for the contract, and a parallel-load check for the concurrency invariant.

## 13. Concurrency: what's guaranteed, and how it's verified

**Decision.**
- **Updates** are protected by optimistic locking (decision 8) — safe against lost updates.
- **Identical creates** use a best-effort *check-then-insert*, which can race under two simultaneous
  requests; this is accepted as a simplification (duplicate detection is not a spec requirement).
- Verified by `PaymentIntegrationTest#staleUpdateIsRejectedByOptimisticLocking` and, on the running
  stack, by [`scripts/concurrency-check.sh`](scripts/concurrency-check.sh) (Scenario 1 asserts the
  update invariant; Scenario 2 demonstrates the create race). CI runs the script; only the
  deterministic Scenario 1 governs the exit code.

**Why.** Make the guarantee that matters for correctness (no lost updates) rock-solid and tested,
while being explicit that the non-required race is intentionally left simple. A race-proof version
would use a partial unique index (`WHERE status = 'CREATED'`) plus an isolated-attempt + retry, but
that's more machinery than the "simple and focused" brief warrants for a non-required feature.