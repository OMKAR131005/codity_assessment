Distributed Job Scheduler
A production-inspired distributed job scheduling platform. Users create projects and
queues, submit background jobs (immediate / delayed / scheduled), and a worker engine
polls, atomically claims, and executes them concurrently — with retries, a dead-letter
queue, heartbeat-based crash recovery, and graceful shutdown.
Tech Stack
Backend: Java 17, Spring Boot 3.2 (Web, Data JPA, Security, Validation), MySQL 8,
JWT auth (jjwt), Lombok, springdoc-openapi (Swagger UI)
Frontend: React (Vite), axios, react-router — see `frontend/README.md` (in the
`job-scheduler-frontend` project) for its own setup steps
Setup
1. Prerequisites
   Java 17+
   Maven 3.8+
   MySQL 8 running locally
2. Configure the database
   Edit `src/main/resources/application.properties` if your MySQL credentials differ from
   the defaults (`root` / `root`). The database `jobscheduler` is auto-created on startup
   (`createDatabaseIfNotExist=true`), and tables are auto-created via
   `spring.jpa.hibernate.ddl-auto=update`.
3. Run
```bash
mvn clean install
mvn spring-boot:run
```
The app starts on `http://localhost:8090` (see `server.port` in
`application.properties`). A worker instance also starts automatically inside the same
process (see `WorkerService`) and begins polling every 2 seconds.
4. API documentation (Swagger)
   Once running, open `http://localhost:8090/swagger-ui.html` — every endpoint is
   listed with request/response schemas. Click Authorize, paste a JWT token (from
   `/api/auth/login` or `/api/auth/register`), and you can call any endpoint directly from
   the browser. See `API_DOCUMENTATION.md` for a static reference if Swagger isn't running.
5. Try it out (example flow)
   Register + login
```bash
curl -X POST localhost:8090/api/auth/register -H "Content-Type: application/json" \
  -d '{"name":"Omkar","email":"omkar@test.com","password":"password123"}'
# copy the returned "token"
```
Create a project
```bash
curl -X POST localhost:8090/api/projects -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" -d '{"name":"Notification System"}'
```
Create a queue
```bash
curl -X POST localhost:8090/api/projects/1/queues -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"name":"order-notifications","priority":8,"concurrencyLimit":5,"retryType":"EXPONENTIAL","baseDelaySeconds":10,"multiplier":2.0,"maxRetries":3}'
```
Submit an immediate job
```bash
curl -X POST localhost:8090/api/jobs -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"queueId":1,"jobType":"IMMEDIATE","payload":"{\"type\":\"email\",\"to\":\"user@x.com\",\"message\":\"Order confirmed\"}","priority":8}'
```
Submit a delayed job (runs 30 seconds from now)
```bash
curl -X POST localhost:8090/api/jobs -H "Authorization: Bearer <TOKEN>" \
  -H "Content-Type: application/json" \
  -d '{"queueId":1,"jobType":"DELAYED","payload":"{\"type\":\"email\",\"message\":\"reminder\"}","delaySeconds":30}'
```
Watch the console logs — you'll see the worker poll, claim, execute, and (roughly 30%
of the time, simulated) fail and retry with exponential backoff, eventually moving to
the dead-letter queue after 3 attempts.
Check status / history / paginated job list
```bash
curl localhost:8090/api/jobs/1 -H "Authorization: Bearer <TOKEN>"
curl localhost:8090/api/jobs/1/history -H "Authorization: Bearer <TOKEN>"
curl localhost:8090/api/queues/1/stats -H "Authorization: Bearer <TOKEN>"
curl "localhost:8090/api/jobs/queue/1?page=0&size=20" -H "Authorization: Bearer <TOKEN>"
```
6. Run the frontend
   See the separate `job-scheduler-frontend` project (`npm install && npm run dev`). Its
   dev server proxies `/api` calls to `localhost:8090`, so start the backend first.
7. Run tests
```bash
mvn test
```
This includes `JobClaimConcurrencyTest`, an integration test that fires two threads at
the same job simultaneously and asserts only one successfully claims it — see
`TESTING.md` for details. Requires the MySQL instance from step 2 to be running,
since it's an integration test, not a pure unit test.
Domain
Jobs simulate a notification system — the `payload` field carries notification
data (channel, recipient, message). `runJobLogic()` in `WorkerService` is where real
sending logic (email/SMS provider call) would go; it's currently simulated with a delay
and a random 30% failure rate to demonstrate the retry/DLQ paths.
Project Status / Scope
This implementation covers the core spec: auth, projects, queues (priority,
concurrency limit, retry policy, pause/resume), immediate/delayed/scheduled job
creation, atomic job claiming, concurrent execution, heartbeats + crash recovery,
graceful shutdown, configurable retry strategies, dead-letter queue, and full
execution history. See `DESIGN_DECISIONS.md` for trade-offs and known gaps
(e.g. no frontend dashboard / cron recurring jobs / batch jobs in this cut — noted
as explicit scope decisions given time constraints, not oversights).