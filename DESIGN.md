# Design notes

## Scope & philosophy

The task asks for a **small, pragmatic** payment CRUD microservice and explicitly says
*"keep the solution simple and focused"* (senior effort: 3–5h; no auth, no frontend, no cloud, no
pagination). This submission is scoped to exactly that: the five CRUD endpoints, input validation,
backend-managed status, invalid-update prevention, containerised startup, tests, and docs — nothing
that isn't needed to satisfy the brief cleanly.

Where a lightweight, high-value production touch was cheap and directly supported a stated
requirement, it was included. Everything heavier is documented below as a deliberate *extension*
rather than built into the submission — knowing what **not** to build is part of the job.

## What's in the submission (and why)

| Area | Choice | Rationale |
|---|---|---|
| API | OpenAPI-first; interfaces/DTOs generated at build | single source of truth, validation from the spec |
| Persistence | PostgreSQL in a container (+ H2 for the test profile) | realistic; Flyway-managed schema |
| Startup | `docker compose up --build` | one command builds the image and starts DB + app |
| Validation | Bean Validation on the generated request DTO (`amount > 0`, required fields) | the task's rules |
| Status handling | backend sets `CREATED`; updates only allowed from `CREATED` → `409` otherwise | "prevent invalid updates" |
| Duplicate handling | identical payment rejected with `409 Conflict`, nothing persisted | clean REST semantics; `FAILED` stays a genuine failure state |
| **Optimistic locking** | `@Version` on `Payment`; concurrent update → `409` | ~a few lines; directly hardens "prevent invalid updates" under concurrency. The one production touch kept in-tree. |
| Error handling | `@RestControllerAdvice` → RFC-7807 `ProblemDetail` | meaningful, consistent responses |
| Tests | JUnit 5, MockMvc, Testcontainers (Postgres) + Bruno API collection | covers the functional requirements |

## Deliberately-left-out extensions

These were implemented on a separate branch (`feature/production-extensions`) to demonstrate the
production landscape, but are **intentionally excluded from the submission** because they exceed the
"simple and focused" brief. Each note is *when I would actually add it*.

### 1. Distributed caching (Redis)
`@Cacheable`/`@CachePut`/`@CacheEvict` over `GET /payments/{id}`, backed by a shared Redis so all
replicas stay consistent.
- **When it's worth it:** read-heavy load where the DB becomes the bottleneck, or expensive
  read queries. For single-row PK lookups at this scale it adds a dependency and a staleness risk
  for **no measurable gain** — so it's out.

### 2. Idempotency keys
An `Idempotency-Key` header so a retried `POST` (after a lost response) replays the original result
instead of creating a second payment — with a stored response snapshot and scheduled key expiry.
- **When it's worth it:** any real money-moving API. Mandatory at production scale; overkill for a
  CRUD demo, and a large amount of infrastructure for an unstated requirement.

### 3. Concurrency-safe deduplication
The in-tree duplicate check is check-then-insert, which can race under two simultaneous identical
requests. A partial unique index (`WHERE status = 'CREATED'`) plus an isolated-attempt + retry
pattern makes the `409` rejection atomic even then.
- **When it's worth it:** when duplicate creates can genuinely race in production. The in-tree
  version keeps it simple; the branch shows the race-proof version.

### 4. Deployment (Kubernetes)
Deployment/Service/HPA/Ingress manifests for local and prod.
- **When it's worth it:** actual cluster deployment. The task says *no cloud deployment required*, so
  it's out of the submission.

### 5. Connection-pool tuning
Explicit HikariCP sizing.
- **When it's worth it:** under real load, sized against Postgres `max_connections` and replica
  count. Defaults are fine for the demo.

### 6. Payment processing (making `FAILED` a real outcome)
Today `FAILED` is a supported terminal state that the API guards (a `FAILED` payment can't be
updated → `409`), but no endpoint *produces* it — a pure CRUD service has no genuine failure
trigger, and inventing an arbitrary rule (e.g. "amount > X fails") would be neither simple nor
realistic. The clean way to make `FAILED` reachable is a **payment gateway port**:

```java
interface PaymentGateway { PaymentOutcome process(Payment p); }   // APPROVED | DECLINED
```

A `MockPaymentGateway` adapter would stand in for a bank/PSP; processing a `CREATED` payment
(via `PUT` or a dedicated `POST /payments/{id}/process`) would call it and transition to
`COMPLETED` (approved) or `FAILED` (declined). It's deterministically testable by mocking the
gateway.
- **When it's worth it:** when the service actually settles payments. Left out here because it
  reinterprets `PUT` as a processing step and adds an adapter — more than *"simple but realistic"*
  asks for, given the statuses are specified only as examples ("e.g. CREATED, COMPLETED, FAILED").

## Trade-offs worth calling out

- **Migrations:** the initial schema baseline (including the `version` column) lives in `V1`;
  changes made after a release go in new `Vn__*.sql` files rather than editing an applied migration
  (avoids Flyway checksum drift).
- **Optimistic vs pessimistic locking:** optimistic (`@Version`) chosen because update conflicts on a
  single payment are rare — no locks held, cheaper. Pessimistic would suit high-contention rows.
- **Duplicate detection** returns `409 Conflict` rather than inventing a status for it, so `FAILED`
  keeps its real meaning (a payment that could not be completed). This isn't a spec requirement;
  it's kept intentionally simple.