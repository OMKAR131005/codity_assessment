# Distributed Job Scheduler

A production-inspired distributed job scheduling platform: multi-tenant projects and
queues, atomic job claiming across concurrent workers, configurable retry strategies,
dead-letter handling, full execution history, and a live dashboard.

Built for the Codity.ai technical assessment.

## Tech stack

**Backend:** Java 17, Spring Boot 3.2, MySQL 8, Spring Security + JWT, Spring Data JPA
(Hibernate), Lombok, springdoc-openapi (Swagger UI)

**Frontend:** React 18 + Vite, React Router, Axios

## Project structure

```
job-scheduler-platform/
├── backend/     Spring Boot API + worker service
├── frontend/    React dashboard
└── docs/        Architecture, ER diagram, design decisions
```

## Setup

### 1. Database

```sql
CREATE DATABASE jobscheduler;
```
(Or let it auto-create — `createDatabaseIfNotExist=true` is set in `application.properties`.)

### 2. Backend

```
cd backend
mvn clean install
mvn spring-boot:run
```

Edit `src/main/resources/application.properties` if your MySQL credentials or port
differ from the defaults. The app runs on `server.port` as configured there (default
example: `8090`). Tables are auto-created/updated via `spring.jpa.hibernate.ddl-auto=update`.

A worker instance starts automatically in the same process and begins polling every 2
seconds — no separate worker deployment needed to see the system run end-to-end.

### 3. Frontend

```
cd frontend
npm install
npm run dev
```

Opens on `http://localhost:5173`. The Vite dev server proxies `/api/*` requests to the
backend (configured in `vite.config.js`), so no CORS setup is needed in development —
just make sure the proxy target matches your backend's actual port.

### 4. API docs (Swagger)

Once the backend is running: `http://localhost:<port>/swagger-ui.html`. Click
**Authorize** and paste a JWT (obtained from `/api/auth/login` or `/api/auth/register`)
to try protected endpoints directly from the browser.

## Using the dashboard

1. **Register** or **log in**.
2. **Create a project**, then **create a queue** inside it (set priority, concurrency
   limit, and retry policy).
3. **Submit a job** — Immediate, Delayed (runs after N seconds), or Scheduled (runs at
   a specific date/time).
4. Watch the **Jobs table** — toggle **Auto-refresh** to see status move through
   `QUEUED → CLAIMED → RUNNING → COMPLETED` (or `FAILED → QUEUED` on retry, eventually
   `DEAD_LETTER` after max retries).
5. Click **history** on any job to see every attempt, which worker ran it, and any
   error message.
6. **Queue stats** panel shows a live count of jobs by status.

## Core API reference

All endpoints except `/api/auth/**` require `Authorization: Bearer <token>`.

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/api/auth/register` | Create account, returns JWT |
| POST | `/api/auth/login` | Returns JWT |
| POST | `/api/projects` | Create a project |
| GET | `/api/projects` | List your projects |
| POST | `/api/projects/{id}/queues` | Create a queue in a project |
| GET | `/api/projects/{id}/queues` | List queues in a project |
| POST | `/api/jobs` | Submit a job (Immediate/Delayed/Scheduled) |
| GET | `/api/jobs/{id}` | Get a job |
| GET | `/api/jobs/{id}/history` | Execution attempt history for a job |
| GET | `/api/jobs/queue/{queueId}?page=&size=&sort=` | Paginated job list for a queue |
| POST | `/api/jobs/{id}/retry` | Manually re-queue a job (e.g. from DLQ) |
| GET | `/api/queues/{id}/stats` | Job counts by status for a queue |

### Example: submit an immediate job

```bash
curl -X POST localhost:8090/api/jobs \
  -H "Authorization: Bearer <TOKEN>" -H "Content-Type: application/json" \
  -d '{"queueId":1,"jobType":"IMMEDIATE","payload":"{\"type\":\"email\",\"to\":\"user@x.com\"}","priority":8}'
```

## Domain

Jobs simulate a notification system — `payload` carries notification data (channel,
recipient, message). `WorkerService.runJobLogic()` is where a real send call (email/SMS
provider) would go; it currently simulates ~500ms of work with a ~30% random failure
rate so the retry and dead-letter paths are visibly exercised end-to-end.

## Testing

`JobClaimConcurrencyTest` proves the atomic claim mechanism: two threads race to claim
the same queued job at the same instant via `CountDownLatch`-synchronized release;
exactly one succeeds. Run with:

```
cd backend
mvn test -Dtest=JobClaimConcurrencyTest
```

## Known scope decisions

Given the assessment's time constraints, the following were deliberately deprioritized
in favor of a defensible, fully-working core (see `docs/DESIGN_DECISIONS.md` for the
full reasoning):

- Recurring (cron) and Batch job types — Immediate, Delayed, and Scheduled are
  implemented; the job-type model is designed to extend to these without a schema
  rework (see ER diagram notes).
- Bonus features (rate limiting, RBAC, WebSocket push updates, workflow dependencies,
  queue sharding, event-driven execution, AI failure summaries) — not implemented;
  polling-based auto-refresh is used instead of WebSockets for live updates.
- Filtering beyond pagination on list endpoints.

See `docs/ARCHITECTURE.md` and `docs/ER_DIAGRAM.md` for system design, and
`docs/DESIGN_DECISIONS.md` for trade-offs.
