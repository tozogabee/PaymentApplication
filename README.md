# PaymentApplication

A Spring Boot service for managing payments, built **contract-first** with OpenAPI. It exposes a
REST API to create, read, update, list, and delete payments, backed by PostgreSQL in production and
H2 for local testing, with database schema managed by Flyway.

> **Design notes / scope decisions:** see [`DESIGN.md`](DESIGN.md).

---

## Table of contents

- [Tech stack](#tech-stack)
- [Domain model](#domain-model)
- [REST API](#rest-api)
- [Project layout](#project-layout)
- [Configuration & profiles](#configuration--profiles)
- [Building the project](#building-the-project)
- [Running the application (locally, step by step)](#running-the-application-locally-step-by-step)
- [Running with Docker (whole stack, one command)](#running-with-docker-whole-stack-one-command)
- [Running the published image (GHCR)](#running-the-published-image-ghcr)
- [Database migrations (Flyway)](#database-migrations-flyway)
- [Health & monitoring](#health--monitoring)
- [Testing](#testing)
- [Bruno API collection](#bruno-api-collection)
- [Concurrency check](#concurrency-check)
- [Continuous integration](#continuous-integration)

---

## Tech stack

| Area            | Technology                                             |
|-----------------|--------------------------------------------------------|
| Language        | Java 21                                                |
| Framework       | Spring Boot 4.1 (Web MVC, Data JPA, Actuator, Validation) |
| API             | OpenAPI 3 (contract-first, `openapi-generator`); Swagger UI via springdoc |
| Database        | PostgreSQL (default), H2 (test profile)                |
| Migrations      | Flyway                                                 |
| Build           | Maven (wrapper included: `./mvnw`)                     |
| Tests           | JUnit 5, MockMvc, Testcontainers (PostgreSQL)          |
| API tests       | Bruno collection (`bruno/`)                            |
| Containerization| Docker + Docker Compose                                |
| CI              | GitHub Actions                                         |

---

## Domain model

A **Payment** has the following fields:

| Field              | Type              | Notes                                            |
|--------------------|-------------------|--------------------------------------------------|
| `id`               | UUID              | Generated primary key                            |
| `amount`           | BigDecimal        | Must be > 0                                       |
| `currency`         | String (3 chars)  | ISO 4217 code, normalized to upper case (e.g. `EUR`) |
| `debtorAccount`    | String            | Required                                         |
| `creditorAccount`  | String            | Required                                         |
| `status`           | enum              | `CREATED`, `COMPLETED`, `FAILED` (new payments start as `CREATED`) |
| `createdAt`        | timestamp         | Set automatically (JPA auditing)                 |
| `createdBy`        | String            | Set automatically (JPA auditing)                 |
| `modifiedAt`       | timestamp         | Set automatically (JPA auditing)                 |
| `modifiedBy`       | String            | Set automatically (JPA auditing)                 |

Auditing fields are populated by Spring Data JPA auditing. The current auditor is provided by an
`AuditorAware<String>` bean (`config/JpaAuditingConfig`), currently returning `"system"` — replace
this with the authenticated principal once security is added.

---

## REST API

Base URL: `http://localhost:8080`

| Method   | Path              | Description             | Success        | Error responses |
|----------|-------------------|-------------------------|----------------|-----------------|
| `POST`   | `/payments`       | Create a payment        | `201 Created`  | `400` invalid body · `409` duplicate |
| `GET`    | `/payments/{id}`  | Get a payment by id     | `200 OK`       | `404` not found |
| `GET`    | `/payments`       | List all payments       | `200 OK`       | — |
| `PUT`    | `/payments/{id}`  | Update a payment        | `200 OK`       | `400` invalid body · `404` not found · `409` not `CREATED` / concurrent update |
| `DELETE` | `/payments/{id}`  | Delete a payment        | `200 OK` with `{message, id}` | `404` not found · `409` payment is `COMPLETED` |

All errors are returned as RFC-7807 `application/problem+json` (see [Error responses](#error-responses)
below and the [Error handling](#error-handling) section).

The API is defined in [`src/main/resources/openapi/payment-api.yaml`](src/main/resources/openapi/payment-api.yaml).
The Java interfaces and DTOs are generated from it at build time (`generate-sources` phase) into
`target/generated-sources/openapi`; `PaymentController` implements the generated `PaymentsApi`.

### Example — create a payment

```bash
curl -X POST http://localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -d '{
        "amount": 100.0,
        "currency": "EUR",
        "debtorAccount": "DE123456789",
        "creditorAccount": "DE987654321"
      }'
```

Response (`201 Created`):

```json
{
  "id": "25c89f74-9d75-45a5-82c2-0f8adb2ad61f",
  "amount": 100.0,
  "currency": "EUR",
  "debtorAccount": "DE123456789",
  "creditorAccount": "DE987654321",
  "status": "CREATED",
  "createdAt": "2026-07-03T17:40:34.981Z",
  "createdBy": "system",
  "modifiedAt": "2026-07-03T17:40:34.981Z",
  "modifiedBy": "system"
}
```

### Duplicate detection

A new payment is a **duplicate** when its `debtorAccount`, `creditorAccount`, `amount`, and
`currency` all match an existing payment. Handling:

- **No match** → created with status `CREATED` (`201 Created`).
- **Match exists** → rejected with **`409 Conflict`**; nothing is persisted. The response body
  includes `existingPaymentId` pointing at the payment that already exists.

### Example — delete a payment

```bash
curl -X DELETE http://localhost:8080/payments/{id}
```

Response (`200 OK`):

```json
{ "id": "25c89f74-9d75-45a5-82c2-0f8adb2ad61f", "message": "Payment deleted successfully" }
```

> **A `COMPLETED` payment cannot be deleted** (it is kept for audit): the API responds `409 Conflict`
> with an error message and the payment is left untouched. `CREATED` and `FAILED` payments can be
> deleted.

### Update rules (`PUT /payments/{id}`)

Updating a payment enforces a simple status state machine:

| Current status | Result of update                                        |
|----------------|---------------------------------------------------------|
| `CREATED`      | Fields updated **and status transitions to `COMPLETED`** → `200 OK` |
| `COMPLETED`    | Rejected → `409 Conflict` (already completed)           |
| `FAILED`       | Rejected → `409 Conflict` (only `CREATED` is updatable) |

Only a payment in `CREATED` status can be updated; a successful update marks it `COMPLETED`.
Any other status is rejected with a `409 Conflict` carrying the debtor/creditor accounts, the current
`paymentStatus`, and the payment id:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Payment is failed",
  "debtorAccount": "DE123456789",
  "creditorAccount": "DE987654321",
  "paymentStatus": "COMPLETED",
  "existingPaymentId": "25c89f74-9d75-45a5-82c2-0f8adb2ad61f"
}
```

### Error responses

**Every** error is returned as an **RFC-7807** `application/problem+json` body — the standard fields
(`type`, `title`, `status`, `detail`) plus, where relevant, extra properties such as
`existingPaymentId`, `paymentStatus`, `debtorAccount`, and `creditorAccount`.

**`400 Bad Request`** — validation / malformed body / invalid UUID:

```json
{ "type": "about:blank", "title": "Bad Request", "status": 400,
  "detail": "amount: must be greater than 0, currency: size must be between 3 and 3" }
```

**`404 Not Found`** — no payment for the id:

```json
{ "type": "about:blank", "title": "Not Found", "status": 404,
  "detail": "Payment not found: 25c89f74-9d75-45a5-82c2-0f8adb2ad61f" }
```

**`409 Conflict` — duplicate create** (`POST`), carries the existing payment's id:

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "A payment already exists for debtor=DE123456789 creditor=DE987654321 (id=25c8…)",
  "existingPaymentId": "25c89f74-9d75-45a5-82c2-0f8adb2ad61f" }
```

**`409 Conflict` — deleting a `COMPLETED` payment** (`DELETE`):

```json
{ "type": "about:blank", "title": "Conflict", "status": 409,
  "detail": "Payment 25c8… cannot be deleted because its status is COMPLETED",
  "paymentStatus": "COMPLETED" }
```

**`409 Conflict` — concurrent update** (optimistic-locking) returns
`{ "status": 409, "detail": "The payment was modified by another request; please retry." }`.

### API documentation (Swagger UI)

Interactive API documentation is served by springdoc-openapi when the app is running:

| Resource        | URL                                          |
|-----------------|----------------------------------------------|
| Swagger UI      | `http://localhost:8080/swagger-ui.html`      |
| OpenAPI JSON    | `http://localhost:8080/v3/api-docs`          |

The curated API contract itself lives in
[`src/main/resources/openapi/payment-api.yaml`](src/main/resources/openapi/payment-api.yaml).

### Error handling

Errors are returned as RFC-9457 `application/problem+json`:

- `400 Bad Request` — validation failure (e.g. negative amount, invalid currency), malformed JSON,
  or an invalid UUID path parameter.
- `404 Not Found` — no payment exists for the given id.
- `409 Conflict` — one of:
  - **duplicate create** — a payment with the same debtor/creditor/amount/currency already exists
    (body includes `existingPaymentId`);
  - **invalid update** — the payment is not in `CREATED` status (already `COMPLETED`/`FAILED`);
  - **concurrent update** — the payment was modified by another request (optimistic-locking conflict);
  - **delete of a `COMPLETED` payment** — completed payments are kept for audit and cannot be deleted.

---

## Project layout

```
src/main/java/com/example/payment
├── PaymentApplication.java
├── config/JpaAuditingConfig.java          # enables JPA auditing + AuditorAware bean
└── payment
    ├── controller/PaymentController.java   # implements generated PaymentsApi
    ├── controller/exceptionhandler/PaymentExceptionHandler.java
    ├── exception/                          # AbstractPaymentException + NotFound / NotUpdatable / Duplicate / NotDeletable
    ├── mapper/PaymentMapper.java           # entity -> generated response model
    ├── model/Payment.java                  # JPA entity (auditing + @Version optimistic locking)
    ├── model/PaymentRepository.java
    └── service/PaymentService.java

src/main/resources
├── application.yaml                        # default profile (PostgreSQL)
├── application-test.yaml                   # test profile (H2)
├── db/migration/                           # Flyway migrations (V1, V2)
└── openapi/payment-api.yaml                # API contract

bruno/                                      # Bruno API test collection
scripts/concurrency-check.sh                # ad-hoc parallel-load / optimistic-locking check
Dockerfile                                  # multi-stage image build
docker-compose.yaml                         # app + PostgreSQL
DESIGN.md                                    # design notes & scope decisions
.github/workflows/ci.yml                    # CI pipeline
```

---

## Configuration & profiles

| Profile         | Database                    | Schema             | Notes                       |
|-----------------|-----------------------------|--------------------|-----------------------------|
| default         | PostgreSQL                  | Flyway migrations  | Used in production / Docker |
| `test`          | H2 (in-memory)              | Flyway migrations  | Used for local runs & tests |

The default profile reads its datasource from environment variables (with local defaults):

| Variable      | Default                                      |
|---------------|----------------------------------------------|
| `DB_URL`      | `jdbc:postgresql://localhost:5432/payment`   |
| `DB_USERNAME` | `payment`                                    |
| `DB_PASSWORD` | `payment`                                    |

---

## Building the project

### Prerequisites

- JDK 21 (the Maven wrapper `./mvnw` is included — no local Maven install needed)
- Docker running (only required for the tests, which use Testcontainers)

### Build

```bash
./mvnw clean package          # compile + run tests + produce the runnable jar
./mvnw clean package -DskipTests   # build without running tests (no Docker needed)
```

The build also **generates code** during `generate-sources`:

- the REST API interfaces/models from `openapi/payment-api.yaml` (openapi-generator), and
- the `PaymentMapperImpl` (MapStruct).

Output: `target/payment-0.0.1-SNAPSHOT.jar` (an executable Spring Boot jar).

To build the Docker image instead:

```bash
docker compose build          # builds the image via the multi-stage Dockerfile
```

---

## Running the application (locally, step by step)

You can run the service two ways locally. **Path A** runs the Java app on your machine against a
PostgreSQL container (closest to production). **Path B** needs no database or Docker at all (H2
in-memory) — the fastest way to just try the API.

### Prerequisites

```bash
java -version      # must be 21.x
docker info        # must succeed (only needed for Path A)
```
The Maven wrapper `./mvnw` is included, so you do **not** need a local Maven install.

### Path A — app on your machine + PostgreSQL in Docker

**Step 1 — start PostgreSQL only** (the `postgres` service from the compose file):

```bash
docker compose up -d postgres
```

**Step 2 — wait until it is healthy** (should print `healthy`):

```bash
docker compose ps postgres
```

**Step 3 — run the application** (default profile → connects to `localhost:5432`):

```bash
./mvnw spring-boot:run
```
On startup Flyway applies the migrations and you should see a line like:
`Started PaymentApplication in X.Xs`. The API is now at `http://localhost:8080`.

**Step 4 — verify it works** (in another terminal):

```bash
# health
curl http://localhost:8080/actuator/health          # -> {"status":"UP",...}

# create a payment
curl -X POST http://localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -d '{"amount":100.0,"currency":"EUR","debtorAccount":"DE123456789","creditorAccount":"DE987654321"}'
# -> 201 Created, body includes "id" and "status":"CREATED"

# list payments
curl http://localhost:8080/payments                  # -> [ ... ]
```

**Step 5 — stop**:

```bash
# stop the app: press Ctrl+C in the terminal running mvnw
docker compose stop postgres        # stop the database (keeps data)
# or: docker compose down -v         # stop and delete the database volume
```

### Path B — no database needed (H2 in-memory)

H2 is only on the **test** classpath, so use the `test-run` goal with the `test` profile:

**Step 1 — run:**

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test
```

**Step 2 — verify:**

- API: `http://localhost:8080` (e.g. `curl http://localhost:8080/actuator/health`)
- H2 console: `http://localhost:8080/h2-console`
  (JDBC URL `jdbc:h2:mem:payment;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`, user `sa`, empty password)

**Step 3 — stop:** press `Ctrl+C`. The in-memory database disappears with the process.

> **IntelliJ:** run the Maven goal `spring-boot:test-run` with active profile `test`. A plain
> `main()` run uses the runtime classpath where H2 is present via `runtime` scope, so that also works.

---

## Running with Docker (whole stack, one command)

This builds the app image and starts **everything** (app + PostgreSQL) — this is the single-command
startup the task asks for. Works with **Docker or Podman** (`podman compose ...`).

### Prerequisites

```bash
docker info        # the Docker/Podman daemon must be running
```

**Step 1 — build the image and start the stack, waiting for healthchecks:**

```bash
docker compose up -d --build --wait
```
What happens:
1. the `app` image is built from the multi-stage [`Dockerfile`](Dockerfile) (Maven build → slim JRE);
2. the `postgres` container starts and becomes healthy;
3. the `app` container starts, waits for the DB, runs Flyway, and exposes the API on port `8080`.
`--wait` makes the command return only once both containers report **healthy**.

**Step 2 — confirm both containers are healthy:**

```bash
docker compose ps
# NAME               STATUS
# payment-app        Up (healthy)
# payment-postgres   Up (healthy)
```

**Step 3 — verify the API:**

```bash
curl http://localhost:8080/actuator/health           # -> {"status":"UP",...}

curl -X POST http://localhost:8080/payments \
  -H 'Content-Type: application/json' \
  -d '{"amount":100.0,"currency":"EUR","debtorAccount":"DE123456789","creditorAccount":"DE987654321"}'
# -> 201 Created
```

**Step 4 — view logs (optional):**

```bash
docker compose logs -f app         # follow the application logs (Ctrl+C to stop following)
```

**Step 5 — stop / clean up:**

```bash
docker compose down                # stop containers, KEEP the PostgreSQL data volume
docker compose down -v             # stop AND delete the data volume (fresh DB next time)
docker compose up -d --build       # rebuild after a code change and restart
```

> **Podman:** replace `docker compose` with `podman compose` (Podman v4+). Everything else is identical.

---

## Running the published image (GHCR)

CI publishes the application image to the **GitHub Container Registry** on pushes to `main`
(see [Continuous integration](#continuous-integration)):

```
ghcr.io/tozogabee/paymentapplication:latest      # also: :main, :sha-<commit>, :pr-<n>
```

The image contains only the app (a JRE + the fat jar) — it still needs a PostgreSQL to talk to. The
datasource is configured via environment variables (`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`).

**Step 1 — authenticate to GHCR** (only needed while the package is private; a
[Personal Access Token](https://github.com/settings/tokens) with the `read:packages` scope):

```bash
echo "$GHCR_PAT" | docker login ghcr.io -u tozogabee --password-stdin
```

**Step 2 — pull the image:**

```bash
docker pull ghcr.io/tozogabee/paymentapplication:latest
```

> The image is published **multi-arch** (`linux/amd64` + `linux/arm64`), so it runs natively on both
> Intel/AMD and Apple-Silicon machines. (If you ever hit `no matching manifest for linux/arm64`, that
> image was built amd64-only — pull/run it with `--platform linux/amd64`, which Docker Desktop runs
> under emulation.)

**Step 3 — run it with a PostgreSQL** (on a shared Docker network so they can reach each other):

```bash
docker network create payment-net

docker run -d --name payment-postgres --network payment-net \
  -e POSTGRES_DB=payment -e POSTGRES_USER=payment -e POSTGRES_PASSWORD=payment \
  postgres:17-alpine

docker run -d --name payment-app --network payment-net -p 8080:8080 \
  -e DB_URL=jdbc:postgresql://payment-postgres:5432/payment \
  -e DB_USERNAME=payment -e DB_PASSWORD=payment \
  ghcr.io/tozogabee/paymentapplication:latest
```

**Step 4 — verify** (Flyway runs on startup):

```bash
curl http://localhost:8080/actuator/health          # -> {"status":"UP",...}
```

**Step 5 — stop and clean up:**

```bash
docker rm -f payment-app payment-postgres
docker network rm payment-net
```

> **Alternative — reuse compose.** Point the compose `app` service at the published image instead of
> building it locally, via an override file:
> ```yaml
> # docker-compose.ghcr.yml
> services:
>   app:
>     image: ghcr.io/tozogabee/paymentapplication:latest
> ```
> ```bash
> docker compose -f docker-compose.yaml -f docker-compose.ghcr.yml up -d
> ```
> The override's `image:` takes precedence over the base `build:`, so Compose pulls instead of building.

---

## Database migrations (Flyway)

Migrations live in `src/main/resources/db/migration` and run automatically on startup for **both**
profiles:

| Version | File                                    | Description                          |
|---------|-----------------------------------------|--------------------------------------|
| V1      | `V1__create_payments_table.sql`         | Creates the `payments` table + status index + `version` column (optimistic locking) |
| V2      | `V2__add_payment_auditing_columns.sql`  | Adds auditing columns                |

Migrations use portable SQL (e.g. `TIMESTAMP WITH TIME ZONE`) so the same scripts run on PostgreSQL
and H2. Because Flyway owns the schema, Hibernate is set to `validate` (test) / `none` (default) —
it never generates DDL.

---

## Health & monitoring

Spring Boot Actuator exposes a health endpoint with component details:

```bash
curl http://localhost:8080/actuator/health
```

```json
{
  "status": "UP",
  "components": {
    "db":        { "status": "UP", "details": { "database": "PostgreSQL" } },
    "diskSpace": { "status": "UP" },
    "ping":      { "status": "UP" }
  },
  "groups": ["liveness", "readiness"]
}
```

The Docker `app` service uses this endpoint for its container healthcheck.

---

## Testing

### Unit & integration tests (Maven)

```bash
./mvnw test        # run all tests
./mvnw verify      # run tests and build the jar
```

The suite contains:

- **`PaymentControllerTest`** — `@WebMvcTest` slice of the web layer with a mocked service: create
  `201`, duplicate `409`, validation `400`, update-when-completed `409`, optimistic-lock `409`,
  not-found `404`, unexpected `500`, delete `200`, and delete-of-completed `409`.
- **`PaymentIntegrationTest`** — `@SpringBootTest` full-stack test running the real Flyway migrations
  against a **PostgreSQL Testcontainer**: full lifecycle (create → read → update to `COMPLETED` →
  reject re-update → **reject delete-of-completed**), a `CREATED` payment being deleted successfully,
  duplicate rejected with `409`, update of a non-`CREATED` payment rejected, and a **stale-update
  optimistic-locking** conflict (no lost updates).
- **`PaymentMapperTest`** — entity ↔ response DTO mapping.
- **`PaymentApplicationTests`** — context load against the Testcontainer.

Coverage is enforced at **80% line coverage** by the JaCoCo `check` goal during `verify`.

> The integration tests require **Docker running** (Testcontainers starts a throwaway PostgreSQL).

---

## Bruno API collection

[Bruno](https://www.usebruno.com) is an open-source API client (like Postman/Insomnia) whose key
difference is that collections are **plain files stored in the repository** — so the requests are
version-controlled and reviewed alongside the code. This project's collection lives in
[`bruno/`](bruno) and doubles as an end-to-end test suite: it covers the happy path, all negative
cases, and the health endpoint.

- `Health/` — actuator health check
- `Payments/` — full lifecycle: create → get → list → update to `COMPLETED` → reject re-update `409`
  → reject delete-of-completed `409` → create a fresh payment → delete it `200` → verify deleted
- `Negative/` — validation `400`s (negative amount, invalid currency, blank field, missing fields,
  malformed JSON, invalid UUID) and `404`s (get/update/delete unknown id)
  - `Negative/Duplicate/` — the **duplicate-create flow** (create → same payment rejected `409` twice
    → clean up the created row)

The collection uses the **`Local`** environment ([`bruno/environments/Local.bru`](bruno/environments/Local.bru)),
which sets `baseUrl = http://localhost:8080`. **Start the app first** (see
[Running the application](#running-the-application-locally-step-by-step)).

### Option 1 — Bruno desktop app (interactive / GUI)

1. Install Bruno from <https://www.usebruno.com> (or `brew install --cask bruno`).
2. **Open Collection** → select the `bruno/` folder in this repo.
3. In the top-right **environment** dropdown, choose **Local**.
4. Click a request (e.g. `Payments / Create Payment`) and press **Send**.

> Requests share variables (e.g. `paymentId`, `dupFirstId`) that earlier requests set via scripts, so
> within a folder run the requests **top to bottom** — a request that reads `{{paymentId}}` needs the
> `Create Payment` request run first. "Run Folder" executes them in order automatically.

### Option 2 — Bruno CLI (headless / CI)

Requires Node.js. Runs the whole collection and exits non-zero on any failed assertion.

```bash
# 1. Start the app
docker compose up -d --build --wait
# or: ./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test

# 2. Run the collection (from the bruno/ folder)
cd bruno
npx @usebruno/cli run -r --env Local

# 3. Check the result (0 = all passed, non-zero = a failure)
echo $?
```

Useful options:

```bash
npx @usebruno/cli run -r --env Local --reporter-junit results.xml   # JUnit report (used in CI)
npx @usebruno/cli run -r --env Local --bail                          # stop on first failure
npx @usebruno/cli run Payments -r --env Local                        # run a single folder
```

> The `-r` (recursive) flag is required because the requests live in subfolders.

### Option 3 — Postman, Insomnia, or any other client

You don't need Bruno at all — because the API is **OpenAPI-first**, any client can consume the
contract directly:

- **Postman:** *Import* → *File* → select
  [`src/main/resources/openapi/payment-api.yaml`](src/main/resources/openapi/payment-api.yaml). Postman
  generates a collection with every endpoint and example bodies. Add a collection variable
  `baseUrl = http://localhost:8080` (or set it per request) and send.
- **Insomnia / Hoppscotch / anything supporting OpenAPI:** import the same `payment-api.yaml`.
- **Swagger UI (no import, zero setup):** with the app running, open
  <http://localhost:8080/swagger-ui.html> and use **Try it out** on each endpoint.
- **curl / HTTPie:** copy the ready-made commands from the [REST API](#rest-api) section above.

> Prefer to keep using Bruno's exact requests in Postman? The Bruno desktop app can **export** a
> collection to Postman format (collection menu → *Export*), or convert with the community
> `bruno-to-postman` tool. But importing `payment-api.yaml` is the simplest path.

---

## Concurrency check

Bruno runs requests **sequentially**, so it can't simulate concurrent load. For that,
[`scripts/concurrency-check.sh`](scripts/concurrency-check.sh) fires many parallel HTTP requests and
asserts the invariants that must hold under real-world concurrency.

```bash
# with the app running (e.g. docker compose up -d --build --wait)
./scripts/concurrency-check.sh
# or:  BASE=http://host:8080 THREADS=20 ./scripts/concurrency-check.sh
```

Requires `curl` and `jq`. It runs two scenarios:

- **Scenario 1 — concurrent updates (pass/fail gate).** Fires `THREADS` simultaneous `PUT`s at the
  same payment and asserts **exactly one** returns `200` and the rest `409` — i.e. optimistic locking
  (`@Version`) prevents lost updates. The script exits non-zero if this doesn't hold.
- **Scenario 2 — concurrent identical creates (informational).** Fires simultaneous identical
  `POST`s and reports how many `CREATED` rows result. Duplicate detection is a best-effort
  check-then-insert, so more than one can slip through under a race — a deliberate simplification for a
  non-required feature (see [`DESIGN.md`](DESIGN.md)).

> It's kept **out of the Bruno collection** (which stays sequential), but CI **does** run it as a
> dedicated step — only Scenario 1 governs the exit code, and that invariant is deterministic (exactly
> one update wins regardless of timing), so it won't flake the build. Scenario 2 is printed for
> visibility. Deterministic JUnit coverage also exists via
> `PaymentIntegrationTest#staleUpdateIsRejectedByOptimisticLocking`.

---

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push (any branch) and on pull
requests to `main`. A single job — **"Build, Run Bruno test collection, Docker image"** — does, in order:

1. Sets up JDK 21 (with Maven caching).
2. Runs `./mvnw verify` — compiles (including OpenAPI generation) and runs all unit/integration
   tests (Testcontainers works on GitHub runners, which have Docker).
3. Sets up Node.
4. Starts the full stack with `docker compose up -d --build --wait` (waits for healthchecks).
5. Runs the **Bruno collection** against the running app with the Bruno CLI.
6. Runs the **concurrency check** ([`scripts/concurrency-check.sh`](scripts/concurrency-check.sh)) —
   the optimistic-locking invariant is the pass/fail gate.
7. Tears the stack down.
8. **Builds and pushes the Docker image** to the GitHub Container Registry (GHCR), tagged by
   branch/PR/commit-sha — but **only** for pushes to `main` / PRs into `main`, and only if every
   step above succeeded.

**Any failure fails the build:** if the stack does not become healthy, or if any Bruno assertion or
the concurrency check exits non-zero, the workflow is marked red.