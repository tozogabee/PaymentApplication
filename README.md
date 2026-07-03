# PaymentApplication

A Spring Boot service for managing payments, built **contract-first** with OpenAPI. It exposes a
REST API to create, read, update, list, and delete payments, backed by PostgreSQL in production and
H2 for local testing, with database schema managed by Flyway.

---

## Table of contents

- [Tech stack](#tech-stack)
- [Domain model](#domain-model)
- [REST API](#rest-api)
- [Project layout](#project-layout)
- [Configuration & profiles](#configuration--profiles)
- [Running the application](#running-the-application)
- [Running with Docker](#running-with-docker)
- [Database migrations (Flyway)](#database-migrations-flyway)
- [Health & monitoring](#health--monitoring)
- [Testing](#testing)
- [Bruno API collection](#bruno-api-collection)
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
| `status`           | enum              | `CREATED`, `COMPLETED`, `FAILED` (new payments start as `CREATED`, or `FAILED` if a duplicate) |
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

| Method   | Path              | Description             | Success        |
|----------|-------------------|-------------------------|----------------|
| `POST`   | `/payments`       | Create a payment        | `201 Created`  |
| `GET`    | `/payments/{id}`  | Get a payment by id     | `200 OK`       |
| `GET`    | `/payments`       | List all payments       | `200 OK`       |
| `PUT`    | `/payments/{id}`  | Update a payment        | `200 OK`       |
| `DELETE` | `/payments/{id}`  | Delete a payment        | `200 OK` with `{message, id}` |

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

A new payment is considered a **duplicate** when its `debtorAccount`, `creditorAccount`, `amount`,
and `currency` all match an existing payment. A duplicate is still created (`201 Created`) but its
status is set to **`FAILED`** instead of `CREATED`.

### Example — delete a payment

```bash
curl -X DELETE http://localhost:8080/payments/{id}
```

Response (`200 OK`):

```json
{ "id": "25c89f74-9d75-45a5-82c2-0f8adb2ad61f", "message": "Payment deleted successfully" }
```

### Update rules (`PUT /payments/{id}`)

Updating a payment enforces a simple status state machine:

| Current status | Result of update                                        |
|----------------|---------------------------------------------------------|
| `CREATED`      | Fields updated **and status transitions to `COMPLETED`** → `200 OK` |
| `COMPLETED`    | Rejected → `409 Conflict` (already completed)           |
| `FAILED`       | Rejected → `409 Conflict` (only `CREATED` is updatable) |

Only a payment in `CREATED` status can be updated; a successful update marks it `COMPLETED`.
Any other status is rejected with a `409 Conflict` whose body reports the debtor/creditor accounts,
the current status, and the message `Payment is failed`:

```json
{
  "message": "Payment is failed",
  "debtorAccount": "DE123456789",
  "creditorAccount": "DE987654321",
  "status": "COMPLETED"
}
```

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
- `409 Conflict` — the payment cannot be updated because it is not in `CREATED` status.

---

## Project layout

```
src/main/java/com/example/payment
├── PaymentApplication.java
├── config/JpaAuditingConfig.java          # enables JPA auditing + AuditorAware bean
└── payment
    ├── controller/PaymentController.java   # implements generated PaymentsApi
    ├── controller/exceptionhandler/PaymentExceptionHandler.java
    ├── exception/PaymentNotFoundException.java
    ├── mapper/PaymentMapper.java           # entity -> generated response model
    ├── model/Payment.java                  # JPA entity (with auditing)
    ├── model/PaymentRepository.java
    └── service/PaymentService.java

src/main/resources
├── application.yaml                        # default profile (PostgreSQL)
├── application-test.yaml                   # test profile (H2)
├── db/migration/                           # Flyway migrations (V1, V2)
└── openapi/payment-api.yaml                # API contract

bruno/                                      # Bruno API test collection
Dockerfile                                  # multi-stage image build
docker-compose.yaml                         # app + PostgreSQL
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

## Running the application

### Prerequisites

- JDK 21
- Docker (for PostgreSQL, Testcontainers, and container runs)

### Option A — default profile against PostgreSQL

Start a PostgreSQL instance (via the provided compose file) and run the app:

```bash
docker compose up -d postgres          # start only the database
./mvnw spring-boot:run                 # run the app (default profile)
```

The app connects to `localhost:5432`, Flyway applies the migrations, and the API is available at
`http://localhost:8080`.

### Option B — test profile against H2 (no database needed)

H2 and its console are only on the **test** classpath, so use the `test-run` goal:

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test
```

- API: `http://localhost:8080`
- H2 console: `http://localhost:8080/h2-console`
  (JDBC URL `jdbc:h2:mem:payment;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`, user `sa`, no password)

> In IntelliJ, run the Maven goal `spring-boot:test-run` with active profile `test` (a plain
> Application run of `main()` uses the runtime classpath, where H2 is present via `runtime` scope).

---

## Running with Docker

Build the image and start the whole stack (app + PostgreSQL):

```bash
docker compose up -d --build
```

- The `app` service is built from the multi-stage [`Dockerfile`](Dockerfile) (Maven build → slim JRE).
- It waits for PostgreSQL to be healthy, connects via the compose network
  (`jdbc:postgresql://postgres:5432/payment`), and runs Flyway on startup.
- API available at `http://localhost:8080`.

Common commands:

```bash
docker compose ps                 # show container status/health
docker compose logs -f app        # follow application logs
docker compose down               # stop (keeps the data volume)
docker compose down -v            # stop and delete the data volume
```

---

## Database migrations (Flyway)

Migrations live in `src/main/resources/db/migration` and run automatically on startup for **both**
profiles:

| Version | File                                    | Description                          |
|---------|-----------------------------------------|--------------------------------------|
| V1      | `V1__create_payments_table.sql`         | Creates the `payments` table + index |
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

- **`PaymentControllerTest`** — `@WebMvcTest` slice test of the web layer with a mocked service
  (positive create, validation `400`, not-found `404`, delete response).
- **`PaymentIntegrationTest`** — `@SpringBootTest` full-stack test running the real Flyway
  migrations against a **PostgreSQL Testcontainer** (create → read → update → list → delete).
- **`PaymentApplicationTests`** — context load against the Testcontainer.

> The integration tests require **Docker running** (Testcontainers starts a throwaway PostgreSQL).

---

## Bruno API collection

End-to-end HTTP tests live in [`bruno/`](bruno) and cover the happy path, all negative cases, and the
health endpoint:

- `Health/` — actuator health check
- `Payments/` — full lifecycle (create → get → list → update to `COMPLETED` → reject re-update
  `409` → delete → verify deleted)
- `Duplicate/` — creates the same payment twice (`CREATED` then `FAILED`) and deletes both
  (self-cleaning, so it stays repeatable)
- `Negative/` — validation `400`s (negative amount, invalid currency, blank field, missing fields,
  malformed JSON, invalid UUID) and `404`s (get/update/delete unknown id)

### Run it locally

Requires Node.js (for the Bruno CLI). The collection targets `http://localhost:8080`, so the app
must be running first.

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
npx @usebruno/cli run -r --env Local --reporter-junit results.xml   # JUnit report
npx @usebruno/cli run -r --env Local --bail                          # stop on first failure
npx @usebruno/cli run Payments -r --env Local                        # run a single folder
```

> The `-r` (recursive) flag is required because the requests live in subfolders.

You can also open the `bruno/` folder in the **Bruno desktop app**, select the **Local** environment,
and run requests interactively.

---

## Continuous integration

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on every push / pull request to
`main`/`master` and:

1. Sets up JDK 21 (with Maven caching).
2. Runs `./mvnw verify` — compiles (including OpenAPI generation) and runs all unit/integration
   tests (Testcontainers works on GitHub runners, which have Docker).
3. Sets up Node.
4. Starts the full stack with `docker compose up -d --build --wait` (waits for healthchecks).
5. Runs the **Bruno collection** against the running app with the Bruno CLI.
6. Tears the stack down.

**Any failure fails the build:** if the stack does not become healthy, or if any Bruno assertion
fails (the Bruno CLI exits non-zero), the workflow is marked red.