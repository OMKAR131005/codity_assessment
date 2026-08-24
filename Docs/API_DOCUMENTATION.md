API Documentation
Interactive docs (recommended): run the app and open
`http://localhost:8090/swagger-ui.html`. This file is a static reference covering
the same endpoints, for when Swagger isn't running.
All endpoints except `/api/auth/**` require `Authorization: Bearer <token>`.
---
Auth
`POST /api/auth/register`
Create a user account.
Body
```json
{ "name": "Omkar", "email": "omkar@test.com", "password": "password123" }
```
Response `200`
```json
{ "token": "<jwt>", "email": "omkar@test.com", "userId": 1 }
```
Errors: `400` if the email is already registered.
`POST /api/auth/login`
Body
```json
{ "email": "omkar@test.com", "password": "password123" }
```
Response `200`: same shape as register. Errors: `400` on invalid credentials.
---
Projects
`POST /api/projects`
Body: `{ "name": "Notification System" }`
Response `200`: the created `Project`.
`GET /api/projects`
Lists all projects owned by the authenticated user.
`GET /api/projects/{id}`
Errors: `403` if the project isn't owned by the caller, `400` if it doesn't exist.
`DELETE /api/projects/{id}`
Cascades: deletes the project's queues, their jobs, and those jobs' execution history.
Response: `204 No Content`.
---
Queues
`POST /api/projects/{projectId}/queues`
Body
```json
{
  "name": "order-notifications",
  "priority": 8,
  "concurrencyLimit": 5,
  "retryType": "EXPONENTIAL",
  "baseDelaySeconds": 10,
  "multiplier": 2.0,
  "maxRetries": 3
}
```
`retryType` is one of `FIXED` / `LINEAR` / `EXPONENTIAL`. This call also creates a new
`RetryPolicy` row for the queue.
`GET /api/projects/{projectId}/queues`
Lists queues for a project (ownership-checked).
`PATCH /api/queues/{queueId}/pause`
Sets the queue's status to `PAUSED` — the worker's polling query filters on
`status='QUEUED'` at the job level, so in the current implementation pausing is
enforced by not routing new jobs into a paused queue at the application layer; jobs
already `QUEUED` in a paused queue are still eligible for polling (see
`DESIGN_DECISIONS.md` for the noted follow-up: filtering the poll query by queue status
directly would make pause fully authoritative).
`PATCH /api/queues/{queueId}/resume`
Sets status back to `ACTIVE`.
`GET /api/queues/{queueId}/stats`
Response
```json
{ "QUEUED": 3, "CLAIMED": 1, "RUNNING": 0, "COMPLETED": 12, "FAILED": 2, "DEAD_LETTER": 1, "TOTAL": 19 }
```
---
Jobs
`POST /api/jobs`
Body (IMMEDIATE)
```json
{ "queueId": 1, "jobType": "IMMEDIATE", "payload": "{\"type\":\"email\",\"to\":\"a@b.com\"}", "priority": 8 }
```
Body (DELAYED) — `delaySeconds` required
```json
{ "queueId": 1, "jobType": "DELAYED", "payload": "...", "delaySeconds": 30 }
```
Body (SCHEDULED) — `scheduledAt` required, ISO-8601
```json
{ "queueId": 1, "jobType": "SCHEDULED", "payload": "...", "scheduledAt": "2026-08-25T10:00:00" }
```
Errors: `400` if the type-specific required field is missing, or `jobType` isn't
one of the three supported values (`RECURRING`/`BATCH` are not implemented — see
`DESIGN_DECISIONS.md`).
`GET /api/jobs/{id}`
Returns the job's current state (status, attemptCount, timestamps, etc).
`GET /api/jobs/{id}/history`
Returns every `JobExecution` attempt for this job (worker, attempt number, status,
start/end time, error message if failed) — newest first.
`GET /api/jobs/queue/{queueId}?page=0&size=20&sort=createdAt,desc`
Paginated list of jobs in a queue. Standard Spring `Pageable` query params
(`page`, `size`, `sort`). Default: `size=20`, sorted by `createdAt` descending.
Response: a Spring `Page<Job>` envelope (`content`, `totalElements`,
`totalPages`, `number`, `size`, ...).
`POST /api/jobs/{id}/retry`
Manually re-queues a job (sets `status=QUEUED`, `scheduledAt=now()`) regardless of its
current status — intended for retrying jobs sitting in `FAILED` or `DEAD_LETTER`.
---
Error format
All errors (via `GlobalExceptionHandler`) return:
```json
{ "timestamp": "...", "status": 400, "error": "Bad Request", "message": "..." }
```
`400` — validation / bad input. `403` — ownership violation. `500` — unhandled.
Known gaps (see DESIGN_DECISIONS.md for full reasoning)
No filtering by job status on the paginated list endpoint (pagination only, no
`?status=FAILED` style filter yet).
No `RECURRING` (cron) or `BATCH` job type endpoints.
Queue `pause` doesn't yet filter the worker's poll query directly (see note above).