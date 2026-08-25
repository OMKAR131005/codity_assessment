# Design Decisions

Trade-offs behind the major engineering choices in this project — what was chosen, what
the alternative was, and why.

---

## 1. Atomic job claiming: `SELECT ... FOR UPDATE SKIP LOCKED` over a distributed lock

Two standard approaches exist for preventing duplicate job execution across concurrent
workers:

| Approach | How it works |
|---|---|
| **Pessimistic row locking** *(chosen)* | `SELECT ... FOR UPDATE SKIP LOCKED` |
| Optimistic locking | A `version` column + conditional `UPDATE ... WHERE version = ?` |

**Why `SKIP LOCKED` was chosen:**
- **One atomic statement** — no read-then-write race window to reason about.
- **Never blocks** — `SKIP LOCKED` means a worker never waits on a row another worker
  is claiming; it just moves to the next available row, keeping throughput high under
  contention.
- **No external coordinator needed** — scales to multiple worker instances/machines
  without Redis, ZooKeeper, or any other coordination service. The database itself is
  the coordinator, which keeps the system simpler for this scope.

**Trade-off:** this ties the design to a database that supports `SKIP LOCKED` (MySQL
8+, PostgreSQL). Optimistic locking would be more portable, but requires a retry loop
in application code whenever a claim attempt loses the race — and doesn't reduce
contention on which rows get looked at, so a busy queue means more failed claim
attempts needing retries.

**MySQL-specific workaround:** MySQL disallows referencing the same table in an
`UPDATE ... WHERE ... (SELECT FROM table)` subquery directly, so the claim query wraps
the inner `SELECT ... FOR UPDATE SKIP LOCKED` in a derived table:
```sql
UPDATE jobs SET status = 'CLAIMED', ...
WHERE id IN (SELECT id FROM (SELECT id FROM jobs WHERE ... FOR UPDATE SKIP LOCKED) AS t)
```
— a standard workaround, not a design compromise.

---

## 2. Job creation is "eager"; recurring jobs (if implemented) would be "lazy"

Immediate/Delayed/Scheduled jobs create a `jobs` row directly with a computed
`scheduled_at`. A recurring (cron) job, by contrast, would need:
- a separate `scheduled_jobs` template table storing the cron expression, plus
- a periodic task generating a fresh `jobs` row each time the cron fires.

This keeps the `jobs` table's polling query simple — it only ever deals with concrete,
already-materialized job instances, rather than needing to interpret cron expressions
on every 2-second poll cycle.

---

## 3. Heartbeat merged into `workers`, not a separate `worker_heartbeats` table

The original schema sketch had heartbeats as their own table, to preserve ping history.
In this implementation, `last_ping_at` is a column directly on `workers`:

- Simpler — one less join for the "is this worker alive" check.
- Ping history/audit wasn't a requirement anywhere else in the spec.
- If ping history is needed later, splitting it back into its own table is a small,
  backward-compatible migration — not a rearchitecture.

---

## 4. Dead Letter Queue as its own table, not just a job status

A job could dead-letter simply by setting `status = DEAD_LETTER`. A dedicated
`dead_letter_queue` table (one row per dead-lettered job, with a `reason`) exists
anyway, so that:
- DLQ entries carry DLQ-specific metadata (failure reason, `moved_at`) without
  overloading the `jobs` table.
- A DLQ listing/review screen can query one focused table instead of filtering the
  full jobs table by status.

---

## 5. Composite index on `jobs(status, scheduled_at, priority)`

**This is the single most performance-critical index in the schema.**

The worker's polling query runs every 2 seconds and filters on
`status = 'QUEUED' AND scheduled_at <= NOW()`, sorted by
`priority DESC, scheduled_at ASC`.

| Without this index | With this index |
|---|---|
| Full table scan every poll cycle — gets progressively worse as job history accumulates | Query hits the index directly, regardless of table size |

---

## 6. Real-time stats via aggregation query, not a pre-computed stats table

`GET /queues/{id}/stats` runs a `GROUP BY status`-style aggregation over the `jobs`
table on demand, rather than maintaining a separately-updated counters table.

- **At this scale** (assignment/demo scope): simpler, and always accurate.
- **At real production scale** (millions of jobs): this would need to move to either a
  periodically-refreshed materialized view, or counters incremented transactionally
  alongside status changes. Noted here as the scaling path — not implemented, given the
  time budget.

---

## 7. Cascade behavior

`Project → Queue → Job → JobExecution` cascades on delete — deleting a project removes
its queues, jobs, and execution history.

This is a **destructive but predictable** choice for this scope. A production system
would more likely prefer soft-delete/archival instead of hard cascade delete, to
preserve audit history even after a project is "removed."

---

## 8. Known gaps / explicit scope cuts

Given the assignment's timeline, the following were deliberately deprioritized:

- **No recurring (cron) or batch job types.** Schema and job-type enum are designed to
  accommodate them (see §2), but implementation was deprioritized in favor of getting
  the core reliability mechanism — atomic claim, retry, DLQ, heartbeat, graceful
  shutdown — fully working *and tested*.
- **No rate limiting, distributed tracing, or RBAC.** Listed as bonus features in the
  spec; deprioritized in favor of the weighted-heavier core requirements (Architecture,
  DB Design, Backend, Reliability = 75% of evaluation weight).
- **Job execution logic is simulated** (a delay + random failure) rather than calling a
  real notification provider, since the assignment evaluates the scheduling mechanism,
  not a specific third-party integration.
- **Queue `pause` doesn't yet filter the worker's poll query.** Pausing sets
  `queue.status = PAUSED`, but `WorkerService`'s claim query currently only checks
  `jobs.status = 'QUEUED'`, not the parent queue's status — jobs already sitting
  `QUEUED` in a paused queue can still be claimed. The correct fix is a join against
  `queues` in the native claim query
  (`... WHERE j.status='QUEUED' AND q.status='ACTIVE' ...`). Flagged here rather than
  fixed given remaining time, since it's a real functional gap someone testing "pause"
  would notice.

---

## 9. Fixes applied after initial implementation

Found during **real end-to-end testing** (register → create data → submit jobs → watch
them execute), not by code review alone — documented because they reflect genuine
engineering iteration, not a first-draft-is-final submission.

### a) Jackson vs. Hibernate lazy-proxy serialization crash on `/api/auth/register`

Returning an entity with an un-fetched `@ManyToOne`/`@OneToOne` lazy relationship
(e.g. `Project.owner`, `Queue.retryPolicy`) causes Jackson to try serializing
Hibernate's runtime proxy object directly — fails with a `ByteBuddyInterceptor`-related
error instead of serializing the actual field.

**Fix:** registered Jackson's `Hibernate6Module` (`JacksonConfig.java`) with
`FORCE_LAZY_LOADING=false`, so proxies are recognized and written as their loaded value
or `null`, instead of crashing.

**Why not `FetchType.EAGER` everywhere:** that would silently increase query cost on
*every* read. This fix only changes serialization behavior, not fetch strategy.

### b) Jobs permanently stuck at `RUNNING` status (~30% of jobs, matching the simulated failure rate)

**Root cause:** `WorkerService.executeJob()` runs inside an `ExecutorService` thread
pool task, and fetched the job via a plain `jobRepository.findById(id)`. With
`spring.jpa.open-in-view=false` (deliberately set — see §c), there's no lingering open
Hibernate session once the fetching transaction completes.

When `handleFailure()` later called `job.getQueue().getRetryPolicy()` — both lazy
relationships — Hibernate tried to lazily initialize them outside any active session
and threw `LazyInitializationException`, aborting the method **after** the job had
already been flipped to `RUNNING` and saved, but **before** the final save that would
move it to `QUEUED`/`FAILED`/`DEAD_LETTER`.

**Net effect:** any job whose simulated execution failed got stuck at `RUNNING`
forever — invisible until someone checked job status and noticed it never changed.

**Fix:** `JobRepository.findByIdWithQueueAndRetryPolicy()` — a `LEFT JOIN FETCH` query
that eagerly loads `job.queue` and `queue.retryPolicy` in the same query used to fetch
the job at the start of `executeJob()`, so every field the method needs is already
resident before any lazy-loading boundary could be crossed.

### c) `spring.jpa.open-in-view=false`

Added deliberately — not a bug fix on its own, but directly related to (b).

The Spring Boot default (`open-in-view=true`) keeps a Hibernate session open for the
entire HTTP request lifecycle, which papers over lazy-loading issues in typical
request/response code paths *by accident* — at the cost of connections being held open
longer than necessary, and lazy-loading exceptions surfacing unpredictably far from
their actual cause.

Turning it off is the generally recommended production setting — and doing so is
exactly what surfaced bug (b) during testing rather than in production. That's the
argument for keeping it off rather than reverting it as a quick fix: it traded a
silent, deferred failure mode for a loud, immediate, fixable one.
