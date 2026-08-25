# Distributed Job Scheduler

A production-inspired distributed job scheduling platform for creating, queuing,
executing, retrying, monitoring, and recovering asynchronous background jobs across
multiple concurrent workers.

The system provides authentication, project-level resource ownership, queue
management, priority scheduling, configurable retry policies, worker heartbeat
monitoring with automatic crash recovery, full execution history, Dead Letter Queue
(DLQ) handling with requeue support, and a React dashboard.

Built for the Codity.ai (SDE-1 Graduate Trainee) technical assessment.

---

## Features

- JWT authentication (register, login)
- User-owned Projects → Queues hierarchy (project-level resource ownership)
- Project management
- Queue management — priority, concurrency limit, retry policy
- Queue pause/resume (see [Known Gaps](#known-gaps) — status toggle implemented,
  enforcement in the claim query is a documented gap)
- Configurable retry policies — Fixed, Linear, and Exponential backoff
- Job types: Immediate, Delayed, Scheduled
- Atomic job claiming across concurrent workers — proven race-safe by integration test
- Worker heartbeat monitoring
- Stale-worker / stale-job recovery ("reaper")
- Graceful worker shutdown
- Job lifecycle tracking
- Job execution history (per-attempt, not just per-job)
- Retry tracking
- Dead Letter Queue
- DLQ requeue
- Queue statistics
- React dashboard with live (polling-based) status updates
- MySQL persistence
- REST APIs with Swagger/OpenAPI documentation

---

## System Architecture

```
                          ┌───────────────────────┐
                          │        Browser         │
                          │    React Frontend      │
                          │        :5173           │
                          └───────────┬────────────┘
                                      │
                                 HTTP + JWT
                                      │
                                      ▼
                          ┌───────────────────────┐
                          │      Spring Boot       │
                          │        Backend         │
                          │        :8090           │
                          │  ┌──────────────────┐  │
                          │  │  REST API layer  │  │
                          │  ├──────────────────┤  │
                          │  │  Worker Service   │  │
                          │  │  (embedded,       │  │
                          │  │   polls every 2s) │  │
                          │  └──────────────────┘  │
                          └───────────┬────────────┘
                                      │
                                      ▼
                              ┌────────────┐
                              │   MySQL    │
                              │   :3306    │
                              │            │
                              │ Scheduler  │
                              │ Database   │
                              └────────────┘
```

**Note on the worker:** unlike a setup with separately-deployed worker processes, the
worker service here runs *embedded* inside the same Spring Boot process as the API.
Coordination between concurrent workers happens entirely through MySQL's row-level
locking (`SELECT ... FOR UPDATE SKIP LOCKED`) rather than an external coordinator like
Redis or ZooKeeper — see
[`Docs/DESIGN_DECISIONS.md` §1](Docs/DESIGN_DECISIONS.md#1-atomic-job-claiming-select--for-update-skip-locked-over-a-distributed-lock)
for the reasoning. Running two instances of this same JAR against the same database
already behaves as two independent, safely-coordinating workers — nothing in the
design requires them to be co-located.

---

## Technology Stack

**Backend**
- Java 17
- Spring Boot 3.2
- Spring Security
- JWT (jjwt)
- Spring Data JPA / Hibernate
- Lombok
- springdoc-openapi (Swagger UI)
- Maven
- MySQL 8

**Frontend**
- React 18
- Vite
- Axios
- React Router
- CSS (custom, no framework)

---

## Project Structure

```
codity_assessment/
│
├── Docs/
│   ├── ARCHITECTURE.md
│   ├── ER_DIAGRAM.md
│   ├── API_DOCUMENTATION.md
│   ├── CONCEPTS.md
│   ├── DESIGN_DECISIONS.md
│   ├── TESTING.md
│   └── DOCUMENTATION.md
│
├── job-scheduler-backend/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/jobscheduler/
│   │   │   │   ├── config/
│   │   │   │   ├── controller/
│   │   │   │   ├── dto/
│   │   │   │   ├── entity/
│   │   │   │   ├── exception/
│   │   │   │   ├── repository/
│   │   │   │   ├── service/
│   │   │   │   └── util/
│   │   │   └── resources/
│   │   │       └── application.properties
│   │   └── test/java/com/jobscheduler/
│   └── pom.xml
│
├── job-scheduler-frontend/
│   ├── src/
│   │   ├── api/
│   │   ├── context/
│   │   ├── components/
│   │   ├── pages/
│   │   └── styles/
│   ├── vite.config.js
│   └── package.json
│
└── README.md
```

---

## Database Structure

Main entities:
- `users`
- `projects`
- `queues`
- `retry_policies`
- `jobs`
- `job_executions`
- `workers`
- `dead_letter_queue`

Full entity-relationship diagram, field-by-field, plus indexing/normalization
reasoning: [`Docs/ER_DIAGRAM.md`](Docs/ER_DIAGRAM.md)

---

## Authorization Model

Protected resources follow this ownership chain:

```
JWT User
   │
   ▼
Project
   │
   ▼
Queue
   │
   ▼
Job
   │
   ├── Execution History
   │
   └── Dead Letter Queue
```

Every read/write on a Project, Queue, or Job is scoped server-side to the
authenticated user's ID (`getOwned(userId, resourceId)`) — never trusted from the
request body — so one user cannot reach another user's resources by guessing IDs.

---

## Authentication Flow

```
Login
  │
  ▼
Email + Password
  │
  ▼
Spring Security + BCrypt verification
  │
  ▼
JWT Token
  │
  ▼
React localStorage
  │
  ▼
Axios Authorization header on every request
```

Example header:
```
Authorization: Bearer <JWT_TOKEN>
```

Login/register response:
```json
{
  "token": "...",
  "email": "test@example.com",
  "id": 2
}
```

---

## Job Lifecycle

**Successful job**
```
QUEUED
   │
   ▼
CLAIMED
   │
   ▼
RUNNING
   │
   ▼
COMPLETED
```

**Failed job → retry → DLQ**
```
QUEUED
   │
   ▼
CLAIMED
   │
   ▼
RUNNING
   │
   ▼
FAILED
   │
   ├──── Retries remain ─────► QUEUED (after backoff delay)
   │
   └──── Max retries reached ─► DEAD_LETTER
```

Any job stuck in `CLAIMED`/`RUNNING` under a worker whose heartbeat has gone stale is
force-reset to `QUEUED` by the reaper — this is what makes the lifecycle crash-safe,
not just happy-path safe. Full mechanism explained in
[`Docs/CONCEPTS.md`](Docs/CONCEPTS.md).

---

## Worker Architecture

The worker runs embedded inside the Spring Boot process and starts automatically on
application boot — no separate deployment needed to see the full system run
end-to-end.

**Worker responsibilities (each on its own timer):**
| Behavior | Frequency |
|---|---|
| Poll + atomically claim queued jobs | every 2s |
| Send heartbeat | every 10s |
| Reap stale workers (staleness threshold 30s) | every 15s |
| Graceful shutdown (finish in-flight jobs, up to 30s) | on process termination |

Worker record fields: `workerName`, `status` (ACTIVE/DEAD), `startedAt`,
`lastPingAt`.

---

## Queue Scheduling

Each queue supports:
- Priority
- Concurrency limit (configurable per queue)
- Retry policy
- Pause / Resume (status flag — see [Known Gaps](#known-gaps))

Example:
```
Queue:          order-notifications
Priority:       8
Concurrency:    5
Retry Policy:   EXPONENTIAL, base 10s, ×2, max 3 retries
Status:         ACTIVE
```

Jobs are selected for claiming according to:
- `status = QUEUED`
- `scheduled_at <= NOW()` (handles Delayed/Scheduled jobs)
- Ordered by `priority DESC, scheduled_at ASC`

---

## Retry Strategy

Retry policies contain:
- `maxRetries`
- `type` (backoff strategy)
- `baseDelaySeconds`
- `multiplier` (for exponential)

Supported backoff types: **FIXED**, **LINEAR**, **EXPONENTIAL**

| Strategy | Formula | Example (base=10s, attempt=3) |
|---|---|---|
| FIXED | `base` | 10s |
| LINEAR | `base × attempt` | 30s |
| EXPONENTIAL | `base × multiplier^attempt` | 80s (×2) |

Retry flow:
```
Attempt 1 → FAILED
    │
    ▼
Retry Delay (backoff)
    │
    ▼
Attempt 2 → FAILED
    │
    ▼
Retry Delay (backoff)
    │
    ▼
Attempt 3 → FAILED
    │
    ▼
DEAD_LETTER
```

---

## Execution History

Every execution *attempt* — not just the final outcome — is stored in
`job_executions`.

Example:
```
Job #37

Attempt 1
Status: FAILED
Worker: worker-a1b2c3d4
Error:  Simulated downstream failure

Attempt 2
Status: FAILED
Worker: worker-a1b2c3d4
Error:  Simulated downstream failure

Attempt 3
Status: FAILED
Worker: worker-e5f6a7b8
Error:  Simulated downstream failure
```

The dashboard displays, per attempt: attempt number, worker, status, started/completed
timestamps, error message.

---

## Dead Letter Queue

When a job exceeds its `maxRetries`, it moves to `DEAD_LETTER` and a row is written to
`dead_letter_queue`:

```
Job #37
Status:      DEAD_LETTER
Retry Count: 3/3

Reason: Exceeded max retries (3): Simulated downstream failure
```

**Requeue:**
```
DEAD_LETTER
   │
   ▼
POST /api/jobs/{id}/retry
   │
   ▼
Job status = QUEUED
   │
   ▼
Worker claims job
   │
   ▼
Execution starts again
```

---

## Frontend Pages

**Dashboard** (single page, sectioned):
- Project panel — list existing / create new
- Queue panel — list existing (for selected project) / create new
- Submit job panel — Immediate / Delayed / Scheduled, JSON payload
- Queue stats panel — live counts by status
- Jobs table — color-coded status, auto-refresh toggle (3s polling)
- Execution history panel — per-job attempt timeline

**Login page** — register or log in.

---

## API Endpoints

**Authentication**
```
POST /api/auth/register
POST /api/auth/login
```

**Projects**
```
POST   /api/projects
GET    /api/projects
GET    /api/projects/{id}
DELETE /api/projects/{id}
```

**Queues**
```
POST /api/projects/{id}/queues
GET  /api/projects/{id}/queues
GET  /api/queues/{id}/stats
```

**Jobs**
```
POST /api/jobs
GET  /api/jobs/{id}
GET  /api/jobs/{id}/history
GET  /api/jobs/queue/{queueId}?page=&size=&sort=
POST /api/jobs/{id}/retry
```

Full request/response shapes and curl examples:
[`Docs/API_DOCUMENTATION.md`](Docs/API_DOCUMENTATION.md)

Live interactive docs (once running): `http://localhost:8090/swagger-ui.html`

---

## Running Locally

### Prerequisites
- Java 17+, Maven 3.8+
- Node.js 18+
- MySQL 8 running locally

### Backend
```bash
mysql -u root -p -e "CREATE DATABASE jobscheduler;"

cd job-scheduler-backend
# set DB_PASSWORD as an environment variable first — see application-example.properties
mvn clean install
mvn spring-boot:run
```
Runs on `http://localhost:8090`. The worker starts automatically in the same process.

### Frontend
```bash
cd job-scheduler-frontend
npm install
npm run dev
```
Runs on `http://localhost:5173`, proxying `/api/*` to the backend
(`vite.config.js`).

---

## Testing

```bash
cd job-scheduler-backend
mvn test -Dtest=JobClaimConcurrencyTest   # proves atomic claim is race-safe
mvn test -Dtest=RetryPolicyTest           # proves backoff-delay math is correct
mvn test                                  # run everything
```
What each test proves (and what's not yet covered):
[`Docs/TESTING.md`](Docs/TESTING.md)

---

## Verified Functionality

**Authentication**
- Register — ✅
- Login — ✅
- JWT authentication on protected routes — ✅

**Scheduler**
- Job creation (Immediate / Delayed / Scheduled) — ✅
- Atomic job claiming under concurrency — ✅ *(proven via integration test, not just implemented)*
- Job execution — ✅
- Priority-ordered claiming — ✅
- Retry policies (Fixed / Linear / Exponential) — ✅
- Execution history — ✅

**Reliability**
- Worker heartbeat — ✅
- Stale-worker job recovery (reaper) — ✅
- Graceful shutdown — ✅
- Dead Letter Queue — ✅
- DLQ requeue — ✅

**Frontend**
- Dashboard (project/queue/job/stats/history) — ✅
- Live status updates (polling) — ✅
- Responsive layout — ✅

**API**
- Pagination — ✅
- Swagger/OpenAPI docs — ✅
- Structured error responses — ✅

---

## Known Gaps

Deliberate scope cuts given the assessment's timeline — documented, not hidden:

- **Queue pause is not yet enforced in the claim query.** Pausing sets
  `queue.status = PAUSED`, but the worker's claim query currently only checks
  `jobs.status = 'QUEUED'`, not the parent queue's status — a job already `QUEUED` in a
  paused queue can still be claimed.
- **Recurring (cron) and Batch job types** are not implemented.
- **No filtering** on list endpoints beyond pagination.
- **No explicit idempotency handling.**
- **No WebSocket live updates** — dashboard uses polling instead.
- **No Docker / containerization** — run directly via Maven and npm.
- **No Redis or external coordination service** — atomic claiming is handled entirely
  by MySQL's `SELECT ... FOR UPDATE SKIP LOCKED`, by design (see
  [`Docs/DESIGN_DECISIONS.md`](Docs/DESIGN_DECISIONS.md)).
- **No organization-level tenancy** — ownership stops at the User → Project level.
- **Thin automated test coverage** beyond the two tests above — see
  [`Docs/TESTING.md`](Docs/TESTING.md) for the prioritized list of what to add next.

Full reasoning behind every trade-off: [`Docs/DESIGN_DECISIONS.md`](Docs/DESIGN_DECISIONS.md)

---

## Documentation Map

| Topic | File |
|---|---|
| System design | [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) |
| Database schema | [`Docs/ER_DIAGRAM.md`](Docs/ER_DIAGRAM.md) |
| API reference | [`Docs/API_DOCUMENTATION.md`](Docs/API_DOCUMENTATION.md) |
| How things work internally | [`Docs/CONCEPTS.md`](Docs/CONCEPTS.md) |
| Why things were built this way | [`Docs/DESIGN_DECISIONS.md`](Docs/DESIGN_DECISIONS.md) |
| Test coverage | [`Docs/TESTING.md`](Docs/TESTING.md) |
| Full project index / evaluation self-assessment | [`Docs/DOCUMENTATION.md`](Docs/DOCUMENTATION.md) |

---

## Project Goal

The system provides a reliable distributed background-job platform where jobs can be:

```
created
  ↓
queued
  ↓
prioritized
  ↓
claimed (atomically, safe under concurrency)
  ↓
executed
  ↓
retried on failure (configurable backoff)
  ↓
tracked through execution history
  ↓
moved to DLQ after retry exhaustion
  ↓
requeued for recovery
```

The project demonstrates: distributed scheduling, concurrency control, asynchronous
job execution, retry mechanisms with exponential backoff, worker health monitoring,
failure recovery, dead letter queues, JWT authentication, resource-level
authorization, database design, REST API development, and full-stack implementation.

---

## Author

Distributed Job Scheduler, built by **Omkar Gawande** (SGGSIE&T, Nanded) for the
Codity.ai SDE-1 Graduate Trainee technical assessment — demonstrating backend
engineering, reliability/concurrency handling, database design, API design, and
full-stack implementation.
