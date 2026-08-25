# Distributed Job Scheduler

A production-inspired distributed job scheduling platform: multi-tenant projects and
queues, atomic job claiming across concurrent workers, configurable retry strategies,
dead-letter handling, full execution history, and a live dashboard.

Built for the Codity.ai (SDE-1 Graduate Trainee) technical assessment.

**Tech stack** — Backend: Java 17, Spring Boot 3.2, MySQL 8, Spring Security + JWT,
Spring Data JPA (Hibernate), Lombok, springdoc-openapi (Swagger UI).
Frontend: React 18 + Vite, React Router, Axios.

---

## Documentation map

This README covers setup and a quick orientation. For anything deeper, jump straight
to the relevant file — each one is focused on a single concern rather than repeating
what's here:

| Topic | File | What's in it |
|---|---|---|
| **System design** | [`Docs/ARCHITECTURE.md`](Docs/ARCHITECTURE.md) | Component diagram, how the API/worker/DB fit together, why the worker runs embedded but scales independently |
| **Database schema** | [`Docs/ER_DIAGRAM.md`](Docs/ER_DIAGRAM.md) | Full entity-relationship diagram, every table's fields, PK/FK/index reasoning |
| **API reference** | [`Docs/API_DOCUMENTATION.md`](Docs/API_DOCUMENTATION.md) | Every endpoint, request/response shapes, curl examples, error format |
| **How things work internally** | [`Docs/CONCEPTS.md`](Docs/CONCEPTS.md) | Deep-dive on the atomic claim mechanism, retry backoff math, worker lifecycle (poll/heartbeat/reaper/shutdown), JWT flow, and the real bugs hit + fixed during development |
| **Why things were built this way** | [`Docs/DESIGN_DECISIONS.md`](Docs/DESIGN_DECISIONS.md) | Trade-offs and reasoning behind major choices (atomic claim approach, queue-level retry policy, polling vs. WebSockets, scoped-out job types) |
| **Test coverage** | [`Docs/TESTING.md`](Docs/TESTING.md) | What's tested, what the concurrency test actually proves, what's not covered yet |
| **Full project index** | [`Docs/DOCUMENTATION.md`](Docs/DOCUMENTATION.md) | Feature coverage vs. the assignment spec, evaluation-criteria self-assessment, known gaps consolidated |

---

## Quick start

### Prerequisites
Java 17+, Maven 3.8+, Node.js 18+, MySQL 8 running locally.

### Backend
```bash
mysql -u root -p -e "CREATE DATABASE jobscheduler;"

cd job-scheduler-backend
# Set DB_PASSWORD as an environment variable before running (see Docs/DESIGN_DECISIONS.md
# for why credentials are externalized rather than hardcoded).
mvn clean install
mvn spring-boot:run
```
Runs on `http://localhost:8090` by default. A worker instance starts automatically in
the same process and begins polling every 2 seconds — nothing else to deploy to see the
full system run end-to-end.

**API docs (Swagger UI):** `http://localhost:8090/swagger-ui.html` — click
**Authorize**, paste a JWT (from `/api/auth/login` or `/api/auth/register`), then try
any endpoint directly from the browser. Full written reference:
[`Docs/API_DOCUMENTATION.md`](Docs/API_DOCUMENTATION.md).

### Frontend
```bash
cd job-scheduler-frontend
npm install
npm run dev
```
Opens on `http://localhost:5173`. The dev server proxies `/api/*` to the backend
(`vite.config.js`) — confirm the proxy target matches your backend's actual port.

### Using the dashboard
1. Register or log in.
2. Select/create a project, then select/create a queue inside it.
3. Submit a job (Immediate / Delayed / Scheduled).
4. Toggle **Auto-refresh** on the Jobs table to watch status move through its lifecycle
   live — see [`Docs/CONCEPTS.md` §5](Docs/CONCEPTS.md#5-job-lifecycle--the-state-machine)
   for the full state diagram.
5. Click **history** on any job for its full attempt-by-attempt execution log.

### Tests
```bash
cd job-scheduler-backend
mvn test -Dtest=JobClaimConcurrencyTest   # proves atomic claim is race-safe
mvn test -Dtest=RetryPolicyTest           # proves backoff-delay math is correct
```
What each test actually proves (and doesn't): [`Docs/TESTING.md`](Docs/TESTING.md).

---

## Repository layout

```
.
├── README.md                     ← you are here
├── Docs/
│   ├── ARCHITECTURE.md
│   ├── ER_DIAGRAM.md
│   ├── API_DOCUMENTATION.md
│   ├── CONCEPTS.md
│   ├── DESIGN_DECISIONS.md
│   ├── TESTING.md
│   └── DOCUMENTATION.md
├── job-scheduler-backend/        Spring Boot API + worker service
└── job-scheduler-frontend/       React dashboard
```

## At a glance

- Auth, multi-tenant Projects → Queues, Immediate/Delayed/Scheduled jobs
- Atomic job claiming (`SELECT ... FOR UPDATE SKIP LOCKED`) — proven safe under
  concurrency by an integration test, not just implemented and assumed correct
- Configurable retry strategies (fixed / linear / exponential) + Dead Letter Queue
- Worker heartbeat, crash recovery (reaper), graceful shutdown
- Full execution history per job, paginated job listing, Swagger API docs
- Responsive React dashboard with live (polling-based) status updates

For what's deliberately out of scope and why, see
[`Docs/DESIGN_DECISIONS.md`](Docs/DESIGN_DECISIONS.md) and the gap list in
[`Docs/DOCUMENTATION.md`](Docs/DOCUMENTATION.md) — nothing missing here is an
oversight; each is a documented trade-off given the assessment's timeline.
