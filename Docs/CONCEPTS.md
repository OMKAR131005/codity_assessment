# Distributed Job Scheduler — Concepts Deep Dive

**How each core mechanism actually works, and why it was built that way.**

Companion to `PROJECT_DOCUMENTATION.md` (the deliverable index). That file tells you
*what* exists and *where*; this file tells you *how* it works and *why* it works that
way — written so you can explain any part of it in an interview without re-deriving it
on the spot.

---

## 1. Authentication — JWT flow

**What happens on register/login:**
1. Client sends `{name, email, password}` (register) or `{email, password}` (login).
2. Password is never stored in plain text — `PasswordEncoder.encode()` (BCrypt) hashes
   it before saving. BCrypt is deliberately slow (it's a work-factor hash, not a fast
   hash like SHA-256) specifically to make brute-forcing stolen hashes expensive.
3. On success, `JwtUtil.generateToken(email, userId)` signs a token containing the
   user's identity and an expiry, using a secret key only the server knows.
4. Client stores this token and sends it as `Authorization: Bearer <token>` on every
   subsequent request.

**What happens on every protected request:**
`JwtFilter` (a `OncePerRequestFilter`) runs before the request reaches any controller:
1. Reads the `Authorization` header.
2. Verifies the token's signature against the server's secret — if anyone tampered
   with the token (changed the user ID, for example), the signature check fails and the
   request is rejected. This is what makes JWT stateless-but-trustworthy: the server
   doesn't need to look up a session in a database to know the token is legitimate.
3. Extracts the user ID and sets it as the `Authentication` principal in Spring
   Security's context for the rest of that request's lifecycle.
4. Controllers then read `Authentication auth` and cast `auth.getPrincipal()` to get
   the calling user's ID — this is how `ProjectController.create()` knows *which* user
   is creating the project, without the client ever sending a user ID explicitly (which
   would let one user impersonate another just by changing a request body field).

**Why this matters for the assignment's security requirement:** every project/queue/job
create-and-list operation is scoped to the authenticated user's ID server-side, not
trusted from the request — `ProjectService.listForUser(userId)`, not
`ProjectService.list()`. A malicious client can't list another user's projects by
guessing IDs.

---

## 2. Atomic job claiming — the core reliability mechanism

**The problem:** with multiple worker threads (or multiple worker *instances*, in a
real distributed deployment) all polling the same `jobs` table every 2 seconds, what
stops two workers from both reading job #42 as `QUEUED`, both deciding to claim it, and
both executing it — i.e. double-processing?

**The naive (wrong) approach:** `SELECT` the queued jobs, then `UPDATE` them to
`CLAIMED` in a separate step. Between the SELECT and the UPDATE, another worker's
SELECT can also see the same rows as `QUEUED` — this is a classic
check-then-act race condition.

**The actual mechanism — one atomic statement:**
```sql
UPDATE jobs
SET status = 'CLAIMED', claimed_by_worker_id = :workerId, updated_at = NOW()
WHERE id IN (
  SELECT id FROM (
    SELECT id FROM jobs
    WHERE status = 'QUEUED' AND scheduled_at <= NOW()
    ORDER BY priority DESC, scheduled_at ASC
    LIMIT :limit
    FOR UPDATE SKIP LOCKED
  ) AS t
)
```
Read this from the inside out:
- `FOR UPDATE` tells MySQL: "lock every row this SELECT touches, for the rest of this
  transaction — no other transaction may modify or lock them until I commit."
- `SKIP LOCKED` tells MySQL: "if a row is already locked by another in-flight
  transaction (i.e. another worker is claiming it *right now*), don't wait for it and
  don't error — just skip it and move to the next candidate row."
- Because the lock-and-select happens as one indivisible step, and the `UPDATE` that
  flips the status happens in the *same* transaction, no other worker's `SELECT ...
  FOR UPDATE SKIP LOCKED` can see those rows as available between "select" and
  "claim" — there is no gap for a race to happen in.
- The derived-table wrapper (`SELECT id FROM (...) AS t`) exists purely because MySQL
  forbids selecting from the same table you're updating in a subquery
  (`ERROR 1093 (HY000): You can't specify target table 'jobs' for update in FROM
  clause`) — wrapping it in a derived table is the standard workaround; MySQL
  materializes the inner query as a temporary result set first, so it's no longer
  "the same table" from the query planner's point of view.

**Why `SKIP LOCKED` specifically (not just `FOR UPDATE`):** without it, a second
worker's claim query would *block* waiting for the first worker's lock to release,
turning concurrent polling into serialized, queued-up polling — defeating the purpose
of having multiple workers. `SKIP LOCKED` lets N workers all poll simultaneously and
each walks away with a *different* batch of jobs, with zero coordination needed between
them beyond what the database itself enforces.

**This is proven, not just claimed:** `JobClaimConcurrencyTest` seeds one `QUEUED` job,
then launches two threads that are held at a `CountDownLatch` until both are ready, then
released at the same instant so they call `claimJobs()` as close to simultaneously as
the JVM allows. The assertion — `totalClaimed == 1` — passed, meaning the database-level
locking held even under real concurrent access, not just in theory.

---

## 3. Worker lifecycle — polling, execution, heartbeat, recovery, shutdown

Five independent scheduled behaviors run inside `WorkerService`, each on its own timer:

**Poll + claim (every 2s):** calls the atomic claim query above, then for every job
that came back `CLAIMED`, submits it to a fixed-size thread pool
(`ExecutorService`) so multiple jobs execute *concurrently* within one worker process,
bounded by `worker.thread-pool.size`. This is what lets one worker instance process
several jobs in parallel instead of one at a time.

**Heartbeat (every 10s):** updates `workers.last_ping_at` to the current time. This is
the worker's way of saying "I'm still alive" without anyone having to ask it directly.

**Reaper (every 15s):** looks for *other* workers whose `last_ping_at` is older than
`staleThresholdSeconds` (default 30s — i.e. missed roughly 3 heartbeats). Any such
worker is marked `DEAD`, and every job it had `CLAIMED` or `RUNNING` is reset back to
`QUEUED` (with `claimed_by_worker_id` cleared) so a *different*, healthy worker can pick
it up. This is the crash-recovery mechanism: if a worker process is killed, hangs, or
loses network connectivity mid-job, its in-flight work doesn't vanish — it gets
reclaimed automatically within one reaper cycle (worst case ~45s: 30s staleness window
+ up to 15s until the next reaper tick).

**Execution (`executeJob`):** sets the job to `RUNNING`, records a new `JobExecution`
row (one per *attempt*, not per job — a job retried 3 times has 3 `JobExecution` rows),
runs the actual work, and on success marks `COMPLETED`; on failure, hands off to retry
logic (§4).

**Graceful shutdown (`@PreDestroy`):** on process termination (e.g. `Ctrl+C`, or a
deploy rolling the pod), the worker stops *claiming new* work immediately
(`shuttingDown = true`), but gives already-running jobs up to 30 seconds to finish
naturally before force-terminating the thread pool. This avoids the failure mode where
a routine restart mid-execution causes jobs to be killed mid-flight and then need the
30-45s reaper-recovery path — a clean shutdown finishes what it started when it
reasonably can.

---

## 4. Retry strategies — the backoff math

When a job's execution throws, `handleFailure()` computes how long to wait before the
job becomes eligible again, based on the queue's configured `RetryPolicy`:

| Strategy | Formula | Example (base=10s, attempt=3) |
|---|---|---|
| FIXED | `base` | 10s every time |
| LINEAR | `base × attemptNumber` | 10 × 3 = 30s |
| EXPONENTIAL | `base × multiplier^attemptNumber` | 10 × 2³ = 80s |

The job is set back to `QUEUED` with `scheduled_at = now() + delay` — it re-enters the
*same* claim query as any other queued job (the `scheduled_at <= NOW()` condition in
§2's query is what makes "wait before retrying" work at all; a job isn't eligible for
claiming again until its delay has actually elapsed).

**Why exponential backoff exists conceptually:** if a downstream dependency (e.g. an
email provider) is having an outage, retrying every job immediately at fixed intervals
just hammers it harder while it's down. Exponential backoff spaces retries out
increasingly, giving a struggling dependency room to recover, while still recovering
fast (via the 1st, short retry) if the failure was transient.

**Dead Letter Queue:** once `attemptCount >= maxRetries`, the job stops retrying
entirely — status becomes `DEAD_LETTER` and a row is written to the `dead_letter_queue`
table recording why (`"Exceeded max retries (3): <last error message>"`). This is a
deliberate circuit-breaker: without it, a permanently-broken job (bad payload, deleted
downstream account, etc.) would retry forever, consuming worker capacity indefinitely.
DLQ entries are visible via `job.getHistory()`/the dashboard and can be manually
re-queued via `POST /api/jobs/{id}/retry`.

---

## 5. Job lifecycle — the state machine

```
QUEUED ──(claim)──▶ CLAIMED ──(pickup)──▶ RUNNING ──(success)──▶ COMPLETED
   ▲                                          │
   │                                          │ (failure, attempts remain)
   └──────────────(backoff delay)─────────────┘
                                               │
                                               │ (failure, attempts exhausted)
                                               ▼
                                          DEAD_LETTER
```
Plus a side-channel: any job stuck in `CLAIMED`/`RUNNING` under a worker whose heartbeat
goes stale is force-transitioned back to `QUEUED` by the reaper, regardless of what
state it was actually in — this is what makes the lifecycle crash-safe, not just
happy-path safe.

`DELAYED` and `SCHEDULED` jobs enter the same state machine — the only difference is
what `scheduled_at` is set to at creation time (`now() + delaySeconds` vs. an
explicit timestamp). From the worker's perspective, "delayed" and "scheduled" are the
same mechanism (a future `scheduled_at`); the job type is really about *how the client
expressed intent*, not a different execution path.

---

## 6. Database design — why the schema looks the way it does

**Core entities:** `users`, `projects`, `queues`, `retry_policies`, `jobs`,
`job_executions`, `workers`, `dead_letter_queue`.

**Key relationships:**
- `User 1—N Project` (ownership) → `Project 1—N Queue` → `Queue 1—N Job`. This
  three-level hierarchy is what makes multi-tenancy work: every authorization check
  (`getOwned(userId, projectId)`) walks up this chain, so a user can never read or
  mutate a queue/job that isn't reachable from one of their own projects.
- `Queue N—1 RetryPolicy`: retry policy is per-*queue*, not per-*job* — every job in a
  queue inherits the same backoff strategy. This was a deliberate normalization choice:
  retry behavior is a queue-level operational concern (e.g. "the email-notifications
  queue always uses exponential backoff"), not something that varies job-to-job within
  one queue.
- `Job 1—N JobExecution`: one row per *attempt*, not per job. This is what makes retry
  history queryable — `GET /api/jobs/{id}/history` returns every attempt in order, each
  with its own worker, timestamps, and error message, so a job retried 3 times shows a
  clear timeline of what happened each time, not just the final outcome.
- `Job N—1 Worker` (via `claimed_by_worker_id`): nullable — a `QUEUED` job has no
  claiming worker yet; this FK is how the reaper finds "which jobs belong to this dead
  worker" in one indexed lookup.

**The one index that matters most:** a composite index on
`(status, scheduled_at, priority)` on the `jobs` table. Every claim query filters on
`status = 'QUEUED' AND scheduled_at <= NOW()` and orders by `priority DESC,
scheduled_at ASC` — without this index, that query does a full table scan on every
single poll cycle (every 2 seconds, forever), which is the one query in the whole
system that runs continuously regardless of load. This is the highest-leverage index
in the schema precisely because of how often it executes, not because of table size.

**Cascading:** deleting a `Project` cascades to its `Queue`s, which cascade to their
`Job`s and `JobExecution`s — a project is meaningless without its queues, so orphaned
queues/jobs left behind after a project delete would just be dead data with no owner to
reach them through the authorization chain anyway.

---

## 7. Pagination — how it actually works under the hood

`GET /api/jobs/queue/{id}?page=0&size=20&sort=updatedAt,desc` — Spring Data JPA's
`Pageable` parses those three query params automatically (via
`@PageableDefault`/argument resolution) into a `PageRequest` object, which
`JobRepository.findByQueueId(Long, Pageable)` uses to generate:
```sql
SELECT * FROM jobs WHERE queue_id = ? ORDER BY updated_at DESC LIMIT 20 OFFSET 0
```
plus a separate `COUNT(*)` query to compute total pages — this is why the response is
wrapped in a `Page<Job>` object (`content`, `totalElements`, `totalPages`, `number`),
not a bare array: the client needs the count to render "page 3 of 12", not just the 20
rows on the current page.

**Why `queue_id` list stayed non-paginated for stats:** `JobService.statsForQueue()`
needs *every* job in a queue to compute status counts — paginating that would mean
either running the aggregation in the database (a `GROUP BY` query, which would be the
more scalable version) or fetching all pages client-side, neither of which the current
implementation does. It's a known trade-off, not an oversight — see gap list.

---

## 8. Two real bugs hit during integration — and what they reveal conceptually

These aren't just "bugs that got fixed" — they're worth understanding because they're
common failure patterns in any Spring Boot + JPA system, not specific to this project.

**Bug: `LazyInitializationException` → jobs stuck at `RUNNING` forever.**
`executeJob()` runs inside an `ExecutorService` thread, submitted via
`executorService.submit(() -> executeJob(...))`. Two Spring/JPA facts combine badly
here:
1. Calling `this.executeJob()` from *within the same class* bypasses Spring's
   `@Transactional` proxy entirely (Spring AOP works by wrapping the *external* method
   call in a proxy — a self-invocation never goes through that proxy, so the
   `@Transactional` annotation on `executeJob` silently does nothing).
2. `jobRepository.findById(jobId)` opens and closes its own short-lived transaction
   just for that one call — by the time `handleFailure()` later tries to access
   `job.getQueue().getRetryPolicy()` (a lazy-loaded relation), the Hibernate session
   that could have fetched it on-demand is already closed, so Hibernate throws instead
   of silently fetching.

   That exception propagates out of `executeJob()` *before* the final
   `jobRepository.save(job)` line runs — so the job's status update (to `FAILED` or
   `DEAD_LETTER`) never gets persisted, leaving it visibly stuck at whatever status was
   saved last (`RUNNING`).

   **Fix:** a repository method using `LEFT JOIN FETCH` to eagerly load `queue` and
   `queue.retryPolicy` in the *same* query that fetches the job — so every field
   `handleFailure()` needs is already in memory, no lazy-loading (and therefore no
   session-closed exception) required.

**Bug: `500` on register — `ByteBuddyInterceptor` serialization error.**
Jackson (the JSON library Spring uses to serialize responses) doesn't know how to
serialize a Hibernate lazy-proxy object — `ByteBuddyInterceptor` is Hibernate's
internal proxy wrapper class, and by default Jackson tries to introspect it as if it
were a normal POJO and fails outright. **Fix:** registering a `Hibernate6Module` bean
teaches Jackson to recognize and correctly unwrap (or safely null out, per
`FORCE_LAZY_LOADING=false`) these proxies instead of crashing on them. This is a
one-time global fix — once the module is registered, *any* accidental entity-in-response
leak anywhere in the codebase degrades gracefully instead of 500ing.

**The conceptual thread connecting both bugs:** Hibernate's lazy loading is a
convenience that becomes a liability the moment an entity is accessed *outside* the
transactional/session boundary it was fetched inside — whether that's a
different thread (bug 1) or a JSON serialization step (bug 2). The general lesson:
either eagerly fetch what you know you'll need, or don't let entities escape their
originating transaction boundary at all (use DTOs).

---

## 9. Frontend architecture — how the live-updating dashboard works

**Auth persistence:** the JWT lives in `localStorage`, read once into React state via
`AuthContext` at app startup. Every Axios request goes through a request interceptor
that attaches `Authorization: Bearer <token>` automatically — no component needs to
know about the token directly.

**"Live" updates without WebSockets:** the Jobs table has an auto-refresh toggle that,
when on, calls `loadJobs()` every 3 seconds via `setInterval` inside a `useEffect`. This
is deliberately polling, not push — simpler to implement correctly than WebSockets, and
sufficient for a dashboard where a few seconds of staleness is acceptable (vs. e.g. a
chat app where it wouldn't be). The trade-off is explicit, not accidental (see gap
list — WebSocket live updates was a scoped-out bonus feature).

**Why the jobs table is in a scrollable fixed-height box, not the raw page flow:**
early in development, every auto-refresh cycle (new rows appearing/reordering by
`updatedAt DESC`) shifted the entire page's layout up and down, since everything below
the table in normal document flow gets pushed by however tall the table currently is.
Wrapping the table in `overflow-y: auto` with a fixed `max-height` contains that
reflow to inside the box — the rest of the page stays still regardless of how many rows
are currently rendered.

**Project/Queue selection persistence:** `activeProjectId`/`activeQueueId` are mirrored
into `localStorage` on every change and restored on mount — otherwise a page refresh
would silently reset the dashboard to "no project selected" even though the underlying
project/queue data is safely in the database; only the *UI's memory* of what you were
looking at would be lost.

---

## 10. What the concurrency test actually proves (and doesn't)

**Proves:** two threads racing to claim the *same single row* at the *same instant*
cannot both succeed — the database's row-level locking is doing real work, not just
"probably fine because it usually doesn't happen."

**Doesn't prove:** correctness under sustained high concurrency (many workers, many
jobs, over a long run), performance under lock contention at scale, or that the reaper
correctly interacts with in-flight claims (no test exercises "reaper fires while a claim
is mid-transaction"). These are the next tests worth adding if time allowed — see
`TESTING.md` for the prioritized list.

---

*This document is meant to be read alongside the code, not instead of it — every
mechanism described above corresponds to a specific method named in this doc
(`WorkerService.pollAndClaim`, `handleFailure`, `JwtFilter`, etc.) that you can open
directly to see the exact implementation.*