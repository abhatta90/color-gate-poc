# Color Gate POC: blue/green-safe background work for Spring Boot

This document describes the proof of concept in this repository: the problem, the design decisions, what was
built, and how to verify it step by step. It is written so that an engineer or an AI coding agent can pick it up
without any other context.

For taking the library into an existing service, see [color-gate-integration.md](color-gate-integration.md).

---

## 1. The problem

- Several Spring Boot microservices are moving to **blue/green deployment** on GKE: the same cluster and project,
  with **two namespaces** (blue and green) running the same service at the same time during a release.
- Both colors use the **same database**.
- The load balancer (ALB) moves **user traffic** from one color to the other. **Background work does not go through
  the load balancer**, so without a guard it runs in **both** colors at once and duplicates work:
  - a **calendar task** that checks the DB every second for due jobs and triggers them;
  - **`@Scheduled`** tasks (fixed rate/delay, every N seconds) and **`@Scheduled(cron = ...)`** jobs;
  - **startup tasks** (`ApplicationReadyEvent`, `ApplicationRunner`, ...) that read the DB and submit work;
  - a custom **execution engine** whose startup logic resubmits every job left in `EXECUTING` status (it assumes
    "EXECUTING at startup means I crashed"). Batch jobs can run for 6–8 hours, and 3000+ can run at once.
  - `TimerTask`s and plain executor loops.
- Jobs are both **scheduled** and **manual** (a user submits a job and it starts immediately in the pod that received
  the request). Smoke tests submit manual jobs to the new color before the switch.

**Goal:** background work runs in **exactly one color**, the active one. A pipeline step decides which color is
active. Code changes in services should be minimal.

## 2. The solution in one picture

```
            deployment pipeline (or scripts/switch.sh in the POC)
            UPDATE color_gate SET active_color = 'GREEN'
                              │
                              ▼
   ┌────────────────────────── database ──────────────────────────┐
   │ color_gate   service-a | GREEN      ← the only switch        │
   │ job table    + executed_by_color    ← which color started it │
   └──────────────────────────────────────────────────────────────┘
               ▲ every pod reads color_gate every 1s  ▲
 ┌─────────────┴─────────────┐          ┌─────────────┴─────────────┐
 │ BLUE pod, APP_COLOR=BLUE  │          │ GREEN pod, APP_COLOR=GREEN│
 │ BLUE ≠ GREEN → PASSIVE    │          │ GREEN = GREEN → ACTIVE    │
 │ background work skipped   │          │ background work runs      │
 └───────────────────────────┘          └───────────────────────────┘
```

- Each pod knows its own color from the **`APP_COLOR`** env var (set per namespace in Helm).
- One table, **`color_gate`**, holds the active color per service. **Only the pipeline writes it.** No row means
  nobody is active.
- A small **Spring Boot starter** (the library) reads the row every second and opens or closes a gate. Background
  work only runs while the gate is open.

### The release (switch) flow

1. Deploy green. It starts **passive** (no scheduled/background work). Manual jobs and smoke tests can run on it.
2. Smoke test green.
3. Switch the ALB to green.
4. **Stop blue** (scale to 0) and wait until its pods are gone. Its running jobs stay `EXECUTING`, like after a crash.
5. `UPDATE color_gate SET active_color = 'GREEN'`. Green activates and **resubmits only blue's** `EXECUTING` jobs.

Rollback is the same flow in reverse. **Rule: the old color must be fully stopped before the color is flipped.**

### Job recovery rule

Every job records the color that started it (`executed_by_color`). When a pod becomes active, the library tells it
**why**:

| Reason | When | Resubmitted |
|---|---|---|
| `SWITCH` | The pipeline flipped the color while the pod was running (release day, rollback) | `EXECUTING` jobs **not** started by this color (NULL counts as "not this color") |
| `STARTUP` | The pod started and its color was already active (a pod restart in production) | **All** `EXECUTING` jobs, exactly as before blue/green |

## 3. Decisions and trade-offs (agreed; do not redesign without a reason)

| Topic | Decision | Why |
|---|---|---|
| Source of truth | DB table `color_gate`, written only by the pipeline | The DB is already shared by both colors; one SQL update switches |
| Switch style | **Stop the old color, then flip** (instant cutover). No drain. | Simplest; only one color is ever alive and active, so no coordination is needed |
| Long-running jobs | Killed with the old color, restarted from the beginning by the new one | Same as a crash today; the engine already resubmits `EXECUTING` jobs |
| Fencing token / epoch | **Not needed** | No stale writer can exist, because the old color is dead before the new one starts |
| Per-job heartbeat | **Rejected** | Too expensive with 3000+ concurrent jobs, and a framework-level change |
| `executed_by_color` column | **Kept** | Smoke-test and manual jobs run in green on every release; without it they would be resubmitted (run twice) at the switch. Also good for debugging. |
| Gating mechanism | **Spring AOP** around `@Scheduled` and `@ColorGated` | Covers both with one mechanism and can read per-method annotations; same limits as `@Transactional` |
| `@Scheduled` gating | Automatic (opt out with `@RunInAllColors`) | A forgotten annotation would cause duplicates; safe by default |
| DB unreachable | The pod **keeps its current state** | Flipping passive and back would re-run startup/recovery handlers |
| Cron fire inside the switch window (5–35 s) | **Missed** (accepted for v1) | Possible later fix: record `paused_at` and replay missed cron runs on activation |
| Within-color duplicates (several pods of one color) | Unchanged: existing leader/follower or claim logic still applies | The gate decides the color; existing logic decides the pod |

## 4. What was built

Tech: Java 21, Spring Boot 3.5.15, Gradle 8.14.3 (Groovy DSL, wrapper included), PostgreSQL 17.

```
color-gate-poc/
├── color-gate-spring-boot-starter/      the library
├── sample-service/                      demo service using the library
├── scripts/                             switch.sh, status.sh, reset.sh (simulate the pipeline)
├── .run/                                IntelliJ run configs: BLUE (port 8081), GREEN (port 8082)
├── docs/                                this document and the integration guide
├── build.gradle, settings.gradle        Gradle build
└── gradlew, gradle/wrapper/             Gradle wrapper
```

### Library: `color-gate-spring-boot-starter` (package `com.example.colorgate`)

| Class | Role |
|---|---|
| `ColorGate` | Holds this pod's color and the gate state. `isActive()` = may this pod run background work now? |
| `ColorGateWatcher` | Reads `color_gate` at startup and every `poll-interval`. On activation: publishes `ColorActivatedEvent`, waits for all handlers, **then** opens the gate. On a read error: keeps the current state. |
| `ColorGateAspect` | AOP: skips `@Scheduled` methods (unless `@RunInAllColors`) and `@ColorGated` methods while passive |
| `@OnColorActivated` | Runs a method once when the pod becomes active (`STARTUP` or `SWITCH`). Replaces startup hooks. It is Spring's `@EventListener(ColorActivatedEvent.class)` under a new name. |
| `@OnColorDeactivated` | Runs a method when the pod stops being active (only happens if the color is flipped while the pod is alive) |
| `@RunInAllColors` | Opts a `@Scheduled` method (or class) out of the gate, for pod-local work like cache refresh |
| `@ColorGated` | Gates any method called by your own timer/executor/thread (through the Spring proxy) |
| `ColorActivatedEvent` | Carries `reason()`: `STARTUP` or `SWITCH` |
| `ActiveColorSource`, `JdbcActiveColorSource` | Reads the active color: `SELECT active_color FROM color_gate WHERE service_name = ?` |
| `ColorGateEndpoint` | `GET /actuator/colorgate`: `{enabled, serviceName, myColor, activeColor, active}` |
| `ColorGateAutoConfiguration` | Wires everything; validates `APP_COLOR` at startup |
| `colorgate/schema.sql` | DDL for the `color_gate` table |

Configuration (`colorgate.*`): `enabled` (default `true`), `my-color` (default: env `APP_COLOR`),
`service-name` (default: `spring.application.name`), `poll-interval` (default `1s`).

Unit tests: `ColorGateTest` (8 tests: passive skips, startup vs switch activation, handler-before-gate ordering,
switching away, DB error keeps state, no row means nobody active, disabled mode, invalid color fails startup).

### Sample service: `sample-service` (package `com.example.colorgate.sample`)

A small copy of a real service. "Running" a job means sleeping for `duration_seconds`.

| Class | Role | Gate |
|---|---|---|
| `JobController` | `POST /jobs`: creates `SCHEDULED` jobs (never runs them). `POST /jobs/run-now`: manual job, starts immediately in the pod that receives the request. `GET /jobs`: latest jobs. | none (HTTP) |
| `CalendarTask` | Every second, claims due `SCHEDULED` jobs (`FOR UPDATE SKIP LOCKED`), marks them `EXECUTING`, hands them to the engine | `@Scheduled`, automatic |
| `ExecutionEngine` | Runs each job on a virtual thread. On shutdown the job is interrupted and **left `EXECUTING`** (like a crash). `recoverExecutingJobs` applies the recovery rule. | `@OnColorActivated` |
| `JobRepository` | All job SQL. Every statement that sets `EXECUTING` also stamps `executed_by_color` and `executed_by`. | — |
| `DemoTasks` | every-20s task, every-minute cron, cache refresh | automatic; cache refresh is `@RunInAllColors` |
| `StartupTask` | a startup task | `@OnColorActivated` |
| `LegacyStyleTasks` | a `java.util.Timer` (one-line guard) and a plain executor loop | guard / `@ColorGated` |
| `AuditCleanup` | method called by the executor loop | `@ColorGated` |
| `Audit`, `PodInfo` | write every action to `audit_log` with the color; pod identity | — |

Tables (created on startup by `spring.sql.init`; real services use Flyway/Liquibase):

- `color_gate (service_name PK, active_color, switched_at, switched_by)`
- `engine_job (id, name, status, duration_seconds, attempts, run_at, executed_by, executed_by_color, created_at, started_at, finished_at)`
  - status: `SCHEDULED` → `EXECUTING` → `COMPLETED`
  - `attempts`: how many times the job was started (demo only, shows restarts)
- `audit_log (id, at, color, instance, task, job_id, detail)`: demo only

## 5. Setup (macOS, once)

```bash
brew install postgresql@17
```
```bash
echo 'export PATH="/opt/homebrew/opt/postgresql@17/bin:$PATH"' >> ~/.zshrc && source ~/.zshrc
```
```bash
brew services start postgresql@17
```
```bash
createuser colorgate
```
```bash
createdb -O colorgate colorgate
```
```bash
psql -U colorgate -d colorgate -c "select 1"
```

Homebrew Postgres trusts local connections, so there is no password. Java 21 must be installed.

### Using a different database (e.g. Docker with a password)

The connection comes from environment variables; the defaults match the local Homebrew setup above.

| Variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/colorgate` |
| `DB_USER` | `colorgate` |
| `DB_PASSWORD` | *(empty)* |

Example with Docker:
```bash
docker run -d --name colorgate-pg -p 5432:5432 -e POSTGRES_USER=colorgate -e POSTGRES_DB=colorgate -e POSTGRES_PASSWORD=<choose-one> postgres:17
```
Then set `DB_PASSWORD=<choose-one>` next to `APP_COLOR` in both IntelliJ run configurations (Run → Edit
Configurations → Environment variables), or export it in the terminal. For the scripts, `psql` needs
`export PGHOST=localhost PGPASSWORD=<choose-one>`. **Never put a real password in `application.yml`**: the
repository is public.

## 6. Build and test

```bash
./gradlew build
```

Runs the 8 library tests and builds `sample-service/build/libs/sample-service-0.0.1-SNAPSHOT.jar`.

**IntelliJ:** File → Open → the `color-gate-poc` folder (imports as Gradle). The run configurations
**sample-service BLUE** (port 8081) and **sample-service GREEN** (port 8082) appear in the run dropdown.
Run the scripts from IntelliJ's terminal at the project root.

**Terminal instead of IntelliJ** (one terminal per color):
```bash
APP_COLOR=BLUE java -jar sample-service/build/libs/sample-service-0.0.1-SNAPSHOT.jar --server.port=8081
```
```bash
APP_COLOR=GREEN java -jar sample-service/build/libs/sample-service-0.0.1-SNAPSHOT.jar --server.port=8082
```

## 7. Verify it

Useful queries (run any time):

```bash
psql -U colorgate -d colorgate -c "SELECT id, name, status, attempts, executed_by_color AS color FROM engine_job ORDER BY id"
```
```bash
psql -U colorgate -d colorgate -c "SELECT executed_by_color AS color, status, string_agg(id::text, ',' ORDER BY id) AS job_ids FROM engine_job GROUP BY 1,2 ORDER BY 1,2"
```
```bash
scripts/status.sh
```

`status.sh` shows: active color, jobs, jobs running per color, recent background work with its color, and
checks for duplicates (a job finished twice, a cron run twice in a minute) and missed cron minutes.

### Scenario A: release-day switch with scheduled and manual jobs (the main test)

| Step | Do | Expect |
|---|---|---|
| A1 | Stop both apps, then `scripts/reset.sh` | `DROP TABLE` |
| A2 | Start **BLUE** | `Color gate: BLUE starts PASSIVE (active color: none)` |
| A3 | `scripts/switch.sh BLUE` | BLUE log: `BLUE is ACTIVE (SWITCH)`, `Recovery (SWITCH): 0 EXECUTING job(s) to resubmit`, `BLUE gate OPEN` |
| A4 | `curl -X POST "localhost:8081/jobs?name=long&count=3&durationSeconds=600"` | `[1,2,3]`; BLUE log `job-started` ×3 |
| A5 | `curl -X POST "localhost:8081/jobs?name=delayed&durationSeconds=30&delaySeconds=420"` | `[4]`, stays `SCHEDULED` for 7 minutes |
| A6 | Start **GREEN** | `Color gate: GREEN starts PASSIVE (active color: BLUE)` |
| A7 | `curl -X POST "localhost:8082/jobs/run-now?name=smoke&count=3&durationSeconds=300"` | `[5,6,7]`; GREEN log `job-started` ×3 although passive |
| A8 | Per-color query | BLUE `EXECUTING` 1,2,3 · GREEN `EXECUTING` 5,6,7 · `SCHEDULED` 4 |
| A9 | Stop **BLUE**; wait for `Process finished` | BLUE log: `Job N interrupted by shutdown, left EXECUTING` ×3 |
| A10 | `scripts/switch.sh GREEN` | GREEN log: `GREEN is ACTIVE (SWITCH)`, `Recovery (SWITCH): 3 EXECUTING job(s) to resubmit`, `job-recovered` for 1,2,3 only, `GREEN gate OPEN` |
| A11 | After all jobs finish, run the jobs query | 1–3: `COMPLETED`, attempts **2**, GREEN · 4: `COMPLETED`, attempts 1, GREEN · 5–7: `COMPLETED`, attempts **1**, GREEN |
| A12 | `scripts/status.sh` | Both duplicate checks empty |

Do A9–A10 within about 4 minutes of A5 so job 4 is still waiting; otherwise BLUE starts it, which is also fine.

**What this proves:** blue's killed jobs are resubmitted by green; green's own manual jobs are not touched; a job
that becomes due while nobody is active waits in the table and is picked up by the new active color; nothing runs
twice.

### Scenario B: pod restart in production (`STARTUP`)

With GREEN active:

| Step | Do | Expect |
|---|---|---|
| B1 | `curl -X POST "localhost:8082/jobs/run-now?name=prod&durationSeconds=120"` | new job id, GREEN `job-started` |
| B2 | Stop GREEN, start GREEN again | `GREEN is ACTIVE (STARTUP)`, `Recovery (STARTUP): 1 EXECUTING job(s) to resubmit` |
| B3 | After it finishes, jobs query | the job: `COMPLETED`, attempts 2; duplicate check empty |

On a restart **all** `EXECUTING` jobs are resubmitted, as before blue/green.

### Scenario C: rollback (GREEN back to BLUE)

| Step | Do | Expect |
|---|---|---|
| C1 | Start BLUE | `BLUE starts PASSIVE (active color: GREEN)` |
| C2 | Stop GREEN, wait for it to exit | GREEN jobs left `EXECUTING` |
| C3 | `scripts/switch.sh BLUE` | `BLUE is ACTIVE (SWITCH)`; only GREEN's jobs resubmitted |

### Scenario D: database outage

| Step | Do | Expect |
|---|---|---|
| D1 | `brew services stop postgresql@17` for ~10 s | Active pod logs `Color gate: cannot read color_gate, GREEN stays ACTIVE (...)` every second; no PASSIVE/ACTIVE change |
| D2 | `brew services start postgresql@17` | Warnings stop; work continues; no `@OnColorActivated` re-run |

### Scenario E: break the rule (to see why it exists)

Switch to GREEN **without** stopping BLUE first. GREEN resubmits jobs BLUE is still running, and the duplicate
check in `status.sh` shows jobs finished twice.

## 8. Gotchas

- **`scripts/reset.sh` only with both apps stopped.** It drops the tables, and the apps create tables only at
  startup; running apps then fail with `relation "engine_job" does not exist` until restarted.
- "Port 8081 already in use": an old run is still going (`lsof -ti tcp:8081` shows the PID).
- "Color gate: this pod's color is not set": the app was started without `APP_COLOR`. Use the BLUE/GREEN run configs.
- IntelliJ Community has no database browser; use `psql` or `scripts/status.sh`.
- If the project was imported in IntelliJ as Maven before: close it, delete `.idea`, open the folder again.

## 9. Possible next steps

- Replay cron runs missed in the switch window (`paused_at` column + catch-up on activation).
- Shorter `terminationGracePeriodSeconds` for the old color to shrink the window.
- Optional safety net: refuse the switch while pods of the old color are still alive (pipeline check or a small
  per-pod heartbeat row).
