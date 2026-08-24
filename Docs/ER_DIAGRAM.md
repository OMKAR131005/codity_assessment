# ER Diagram


Paste this into [mermaid.live](https://mermaid.live) or draw.io (with the Mermaid
import option) to get a rendered/exportable diagram image for your submission.

```mermaid

erDiagram
    USERS ||--o{ PROJECTS : owns
    PROJECTS ||--o{ QUEUES : contains
    QUEUES ||--o{ JOBS : contains
    QUEUES }o--|| RETRY_POLICIES : uses
    JOBS ||--o{ JOB_EXECUTIONS : "has attempts"
    JOBS ||--o| DEAD_LETTER_QUEUE : "may enter"
    WORKERS ||--o{ JOB_EXECUTIONS : performs
    WORKERS ||--o{ JOBS : claims

    USERS {
        bigint id PK
        varchar name
        varchar email UK
        varchar password_hash
        timestamp created_at
    }

    PROJECTS {
        bigint id PK
        varchar name
        bigint owner_id FK
        timestamp created_at
    }

    QUEUES {
        bigint id PK
        bigint project_id FK
        varchar name
        int priority
        int concurrency_limit
        bigint retry_policy_id FK
        enum status
        timestamp created_at
    }

    RETRY_POLICIES {
        bigint id PK
        enum type
        int base_delay_seconds
        decimal multiplier
        int max_retries
    }

    JOBS {
        bigint id PK
        bigint queue_id FK
        enum job_type
        text payload
        enum status
        int priority
        timestamp scheduled_at
        bigint claimed_by_worker_id FK
        int attempt_count
        timestamp created_at
        timestamp updated_at
    }

    JOB_EXECUTIONS {
        bigint id PK
        bigint job_id FK
        bigint worker_id FK
        int attempt_number
        enum status
        timestamp started_at
        timestamp completed_at
        text error_message
    }

    WORKERS {
        bigint id PK
        varchar worker_name UK
        enum status
        timestamp started_at
        timestamp last_ping_at
    }

    DEAD_LETTER_QUEUE {
        bigint id PK
        bigint job_id FK
        text reason
        timestamp moved_at
    }
```

## Key indexes
- `jobs(status, scheduled_at, priority)` — composite, supports the worker polling query
  (`WHERE status='QUEUED' AND scheduled_at<=NOW() ORDER BY priority DESC, scheduled_at ASC`)
- `users(email)` — unique, login lookup
- `projects(owner_id)` — "list my projects"
- `job_executions(job_id)` — fetch full attempt history for a job
- `workers(status, last_ping_at)` — stale-worker ("reaper") query
