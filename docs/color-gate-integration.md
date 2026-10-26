# Integrating the Color Gate library into an existing service

How to take `color-gate-spring-boot-starter` from this POC and add it to an existing Spring Boot microservice
with existing tables. Written so that an engineer or an AI coding agent can follow it step by step.

Background, design decisions and the POC test scenarios: [color-gate-poc.md](color-gate-poc.md).

---

## 0. Context for an AI agent (read first)

**The ask:** the service runs blue/green on GKE (two namespaces, one shared database). Background work
(`@Scheduled`, cron, calendar/polling tasks, startup tasks, timers, the execution engine's "resubmit EXECUTING jobs on
startup") must run **only in the active color**. A pipeline step decides the active color by updating one DB row.

**Hard rules (already decided, do not redesign):**

1. The active color lives in the DB table `color_gate`; **only the pipeline writes it**.
2. Each pod's color comes from the env var `APP_COLOR` (`BLUE` or `GREEN`), set per namespace.
3. Release flow: deploy new color (passive) → smoke test → ALB switch → **stop old color and wait until its pods are
   gone** → `UPDATE color_gate`. Never flip the color while the old color is still running.
4. Job table gets **one** new column, `executed_by_color` (nullable, **no default**, no backfill).
5. Recovery: on `SWITCH` resubmit only `EXECUTING` jobs not started by this color; on `STARTUP` resubmit all
   `EXECUTING` jobs (unchanged behaviour).
6. Minimal code changes. Do **not** add per-job heartbeats, drain mode, fencing tokens/epochs, or ShedLock-style
   per-run tables (all considered and rejected; see section 10).

---

## 1. Prerequisites

- Spring Boot 3.x (POC uses 3.5.15), Java 21.
- A JDBC `DataSource` (the library reads `color_gate` with `JdbcTemplate`).
- `@EnableScheduling` if the service uses `@Scheduled` (unchanged).
- Flyway or Liquibase for migrations (examples below use Flyway and PostgreSQL syntax).

## 2. Bring the library into your organisation

### Option A (recommended): shared library repository

1. Copy the folder `color-gate-spring-boot-starter/` into your shared-libraries repository.
2. Rename the package and coordinates to your organisation's, e.g. `com.example.colorgate` →
   `com.yourcompany.platform.colorgate`. Update **all** of these:
   - the Java packages under `src/main/java` and `src/test/java`;
   - `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
     (fully qualified name of `ColorGateAutoConfiguration`);
   - **`ColorGateAspect`**: the pointcut strings contain fully qualified annotation names
     (`com.example.colorgate.RunInAllColors`, `com.example.colorgate.ColorGated`). If these are not updated, the
     gate silently stops matching;
   - `group` / `version` in the Gradle build.
3. Add your repository to the `publishing { repositories { ... } }` block in the starter's `build.gradle`
   (a commented example is there), then publish:
   ```bash
   ./gradlew :color-gate-spring-boot-starter:publish
   ```
4. Run the tests in the new location:
   ```bash
   ./gradlew :color-gate-spring-boot-starter:test
   ```

### Option B: quick local trial (one laptop only)

From this POC:
```bash
./gradlew :color-gate-spring-boot-starter:publishToMavenLocal
```
This installs `com.example.colorgate:color-gate-spring-boot-starter:0.0.1-SNAPSHOT` into `~/.m2/repository`.
The consuming project must then list `mavenLocal()` in its repositories (Gradle); Maven reads `~/.m2` by default.

## 3. Add the dependency

Gradle (Groovy):
```groovy
repositories {
    mavenCentral()
    // mavenLocal()   // only for Option B
}

dependencies {
    implementation 'com.example.colorgate:color-gate-spring-boot-starter:0.0.1-SNAPSHOT'
}
```

Maven:
```xml
<dependency>
    <groupId>com.example.colorgate</groupId>
    <artifactId>color-gate-spring-boot-starter</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

The starter brings `spring-boot-starter-jdbc` and `spring-boot-starter-aop`. If the service has actuator, the
endpoint `/actuator/colorgate` becomes available (expose it with `management.endpoints.web.exposure.include`).

## 4. Database changes: strategy for existing tables

### Summary

| Table | Change | Notes |
|---|---|---|
| `color_gate` | **New** | One row per service. Created by migration, seeded once, then only updated by the pipeline. |
| Job / execution table | **One new column** `executed_by_color VARCHAR(10)` | Nullable, **no default**, **no backfill**. NULL = started by code that predates the column. |
| Scheduler / calendar tables | None | Due rows simply wait while no color is active. |
| Status values | None | `EXECUTING` etc. stay the same. |
| Anything else (audit tables etc.) | None | The POC's `engine_job` and `audit_log` are demo tables; do not copy them. |

If each service has its own database, add `color_gate` to each. If services share a schema, one table is enough
(one row per service, keyed by `spring.application.name`).

### Migration 1: the `color_gate` table and its seed row

`src/main/resources/db/migration/V<next>__color_gate.sql`:
```sql
CREATE TABLE IF NOT EXISTS color_gate (
    service_name VARCHAR(100) PRIMARY KEY,   -- = spring.application.name
    active_color VARCHAR(10)  NOT NULL CHECK (active_color IN ('BLUE', 'GREEN')),
    switched_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    switched_by  VARCHAR(200)
);

-- No row means NOBODY is active and no background work runs.
-- Seed with the color you deploy this release into (see section 7, "first rollout").
INSERT INTO color_gate (service_name, active_color, switched_by)
VALUES ('service-a', 'BLUE', 'initial migration')
ON CONFLICT (service_name) DO NOTHING;
```

### Migration 2: the job table column

`V<next+1>__job_executed_by_color.sql`:
```sql
-- Which color started the job. Nullable, no default, no backfill.
ALTER TABLE job ADD COLUMN executed_by_color VARCHAR(10);
```

Replace `job` with your real table name. Do **not** give it a default such as `'BLUE'`: NULL must mean
"unknown / old code", and those jobs are treated as the other color's jobs at a switch.

### Permissions (recommended)

```sql
GRANT SELECT ON color_gate TO app_user;        -- the service only reads
GRANT UPDATE ON color_gate TO pipeline_user;   -- only the pipeline switches
```

### Other databases

The DDL above is PostgreSQL. For other databases: use the native timestamp type instead of `TIMESTAMPTZ`; replace
`ON CONFLICT DO NOTHING` with `MERGE` / `INSERT IGNORE`; and in queries use
`(executed_by_color IS NULL OR executed_by_color <> ?)` instead of `IS DISTINCT FROM`.

## 5. Configuration

`application.yml` (only `poll-interval` is optional; the rest has defaults):
```yaml
spring:
  application:
    name: service-a            # also the row key in color_gate
colorgate:
  poll-interval: 1s
```

Helm: set the color per namespace, e.g. in the blue values file:
```yaml
env:
  - name: APP_COLOR
    value: BLUE
```
and `GREEN` in the green values file. The app **fails at startup** if the gate is enabled and `APP_COLOR` is
missing or not `BLUE`/`GREEN`.

Local and dev environments without blue/green:
```yaml
colorgate:
  enabled: false               # always active; startup behaves exactly as before
```

| Property | Default | Meaning |
|---|---|---|
| `colorgate.enabled` | `true` | `false` = no blue/green, always active |
| `colorgate.my-color` | env `APP_COLOR` | This pod's color |
| `colorgate.service-name` | `spring.application.name` | Row key in `color_gate` |
| `colorgate.poll-interval` | `1s` | How often `color_gate` is read |

## 6. Code changes

### 6.1 Find all background work

Run from the service's root:
```bash
grep -rn --include='*.java' -E "@Scheduled|@Schedules" src/main
```
```bash
grep -rn --include='*.java' -E "ApplicationReadyEvent|ApplicationStartedEvent|ContextRefreshedEvent|implements .*(ApplicationRunner|CommandLineRunner|SmartLifecycle)|@PostConstruct" src/main
```
```bash
grep -rn --include='*.java' -E "new Timer\(|TimerTask|ScheduledExecutorService|newScheduledThreadPool|newSingleThreadScheduledExecutor|TaskScheduler|new Thread\(|while \(true\)" src/main
```
```bash
grep -rn --include='*.java' "EXECUTING" src/main
```

Classify each hit with the table below.

| What you find | Change |
|---|---|
| `@Scheduled` (fixedRate, fixedDelay, cron) | **None**, gated automatically. Add `@RunInAllColors` only for pod-local work (in-memory cache refresh, local metrics). |
| Startup work that touches shared state (DB, queues, external systems) | Replace the hook with `@OnColorActivated` (6.2) |
| Pure in-pod startup (warming a local cache, building an index) | Leave as it is |
| `TimerTask`, scheduled executor, custom thread/loop doing shared work | One-line guard or `@ColorGated` (6.3) |
| Execution engine status/recovery code | 6.4 |

### 6.2 Startup hooks → `@OnColorActivated`

Before:
```java
@EventListener(ApplicationReadyEvent.class)
public void loadPendingAndSubmit() { ... }
```
After:
```java
@OnColorActivated
public void loadPendingAndSubmit() { ... }
```

For `ApplicationRunner` / `CommandLineRunner`: move the body into an `@OnColorActivated` method and remove the
interface. The method may take no argument or a `ColorActivatedEvent` (`event.reason()` is `STARTUP` or `SWITCH`).
It runs at startup if this pod's color is already active, otherwise when the pipeline switches to it. All
`@OnColorActivated` methods finish **before** any gated work starts.

### 6.3 Timers, executors, own threads

Either guard each tick:
```java
if (!colorGate.isActive()) {
    return;
}
```
(inject `com.example.colorgate.ColorGate`), or put `@ColorGated` on the method the loop calls. `@ColorGated` works
through the Spring proxy: the call must come from **another bean**, and the method should return `void`.

### 6.4 Execution engine

**(a) Stamp the color wherever a job becomes `EXECUTING`.** Keep this in **one** method or repository so no path
(scheduled, manual, recovery, retry) can forget it.

Before:
```java
jdbc.update("UPDATE job SET status = 'EXECUTING', started_at = now() WHERE id = ?", id);
```
After:
```java
jdbc.update("UPDATE job SET status = 'EXECUTING', executed_by_color = ?, started_at = now() WHERE id = ?",
        myColor(), id);

private String myColor() {
    Color color = colorGate.getMyColor();   // null when colorgate.enabled=false
    return color == null ? null : color.name();
}
```
The resubmit path in recovery must stamp the color too (it should go through the same method).

**(b) Recovery: the existing "resubmit EXECUTING jobs on startup".**

Before:
```java
@EventListener(ApplicationReadyEvent.class)
public void resubmitExecutingJobs() {
    jobRepository.findExecuting().forEach(this::resubmit);
}
```
After:
```java
@OnColorActivated
public void resubmitExecutingJobs(ColorActivatedEvent event) {
    List<Job> orphans = event.reason() == ColorActivatedEvent.Reason.SWITCH
            ? jobRepository.findExecutingNotStartedBy(myColor())   // release day: only the stopped color's jobs
            : jobRepository.findExecuting();                       // pod restart: all, unchanged
    orphans.forEach(this::resubmit);                               // unchanged
}
```
New query:
```sql
SELECT ... FROM job
 WHERE status = 'EXECUTING'
   AND (executed_by_color IS NULL OR executed_by_color <> ?)
```
Reference implementation: `sample-service/.../engine/ExecutionEngine.java` and `JobRepository.java` in the POC.

**(c) Check graceful shutdown.** On SIGTERM, running jobs must **stay `EXECUTING`** (as after a crash). If the
engine sets them to `FAILED`/`STOPPED` on shutdown, recovery will not find them. Change that behaviour or include
those statuses in the recovery query.

**(d) Manual job endpoints: no change.** They may start jobs in a passive pod (smoke tests); the stamped color
keeps those jobs safe at the switch.

### 6.5 Services with leader/follower (sidecar) election

No change. The gate and the leader check compose: a task runs only if the pod's color is active **and** the pod is
the leader. The gate check happens first (in the aspect), the existing leader check inside the method.

## 7. Pipeline changes

### Normal release (blue → green)

```bash
APP=service-a; OLD_NS=<blue-namespace>; NEW_NS=<green-namespace>
```
```bash
kubectl -n "$NEW_NS" rollout status deploy/"$APP"
```
Run smoke tests against green (it is passive: no scheduled/background work, manual jobs work).
Switch the ALB to green (existing step). Optional: see how many old-color jobs will be restarted:
```sql
SELECT count(*) FROM job WHERE status = 'EXECUTING' AND executed_by_color = 'BLUE';
```
Stop blue and wait until its pods are really gone (use your deployment's pod label):
```bash
kubectl -n "$OLD_NS" scale deploy/"$APP" --replicas=0
```
```bash
while kubectl -n "$OLD_NS" get pods -l app="$APP" -o name | grep -q .; do sleep 2; done
```
Switch the color (however your pipeline reaches the DB: Cloud SQL Auth Proxy, a one-off Kubernetes Job, ...):
```sql
UPDATE color_gate
   SET active_color = 'GREEN', switched_at = now(), switched_by = 'pipeline-<build-id>'
 WHERE service_name = 'service-a';
```
Expected in green's logs within ~1 s:
```
Color gate: GREEN is ACTIVE (SWITCH), running @OnColorActivated handlers
Color gate: GREEN gate OPEN, background work enabled
```
Keep blue at 0 replicas (not deleted) until green is trusted, so rollback is a fast scale-up.

### Rollback (green → blue)

Scale blue up (it starts passive) → switch the ALB back → scale green to 0 and wait until its pods are gone →
`UPDATE color_gate SET active_color = 'BLUE' ...`. Blue resubmits only green's `EXECUTING` jobs.

**Data compatibility is the real rollback risk:** the old version must be able to read what the new version wrote.

### First rollout of the library

- **Release N** (first release containing the library): deploy the normal way into the current namespace, e.g.
  blue. Migrations create `color_gate` with the seed row `BLUE` and add `executed_by_color`. Helm sets
  `APP_COLOR=BLUE`. Pods start active (`STARTUP`), so behaviour is unchanged.
- **Release N+1**: the first real blue/green release, using the flow above.
- Alternative: ship release N with `colorgate.enabled=false` and enable it in N+1 (the seed row must exist by then).

If the seed row is missing, the service starts **passive** and **no background work runs**.

## 8. Verify in a dev/staging environment

1. Both colors deployed: `curl <pod>/actuator/colorgate` shows `"active": true` on one color only.
2. Passive color: no scheduled/background activity in logs. To see skipped ticks, set
   `logging.level.com.example.colorgate.ColorGateAspect=DEBUG` (logs `... is passive, skipping ...`).
3. Submit manual jobs to the passive color: they run, and `executed_by_color` is that color.
4. Do the switch flow: green logs `Recovery ... (SWITCH)` with exactly the count of blue's `EXECUTING` jobs; green's
   own jobs are not resubmitted.
5. Restart a pod of the active color: recovery runs with `STARTUP` and resubmits all `EXECUTING` jobs.
6. Check that no job ran twice:
   ```sql
   SELECT executed_by_color, status, count(*) FROM job GROUP BY 1, 2 ORDER BY 1, 2;
   ```

## 9. Rules and pitfalls (checklist)

- [ ] Old color fully stopped **before** `UPDATE color_gate`.
- [ ] Seed row exists before the first release with the gate enabled.
- [ ] `APP_COLOR` set in both namespaces.
- [ ] `executed_by_color` has **no default** and is set in **every** path that sets `EXECUTING`.
- [ ] Shutdown leaves running jobs `EXECUTING`.
- [ ] `@OnColorActivated` methods are reasonably short (gated work waits for them).
- [ ] `@ColorGated` only on `void` methods called from another bean.
- [ ] Package renamed everywhere, including the pointcut strings in `ColorGateAspect`.
- Known, accepted: a cron fire time inside the switch window (5–35 s) is missed; killed jobs restart from the
  beginning.
- DB unreachable: pods keep their current state and retry every second (no flip, no re-run of handlers).

## 10. Considered and rejected (do not reintroduce)

| Idea | Why not |
|---|---|
| Per-job heartbeat column | One update per running job every N seconds; too expensive with 3000+ jobs; framework change |
| Drain mode (old color finishes its jobs after the switch) | Needs coordination between live colors (ownership, fencing); much more complex |
| Fencing token / epoch | Only needed when two colors can be alive and active at once; the flow prevents that |
| Gating manual jobs in the passive color | Smoke tests could not exercise the new version's job execution |
| Stopping the whole scheduler in passive pods | All or nothing; `@RunInAllColors` impossible; does not cover timers |
| AspectJ weaving | Needs a build plugin or JVM agent in every service |

## 11. Files in the POC to look at

| File | Why |
|---|---|
| `color-gate-spring-boot-starter/` | The library to copy |
| `color-gate-spring-boot-starter/src/main/resources/colorgate/schema.sql` | `color_gate` DDL |
| `sample-service/.../engine/ExecutionEngine.java` | Recovery rule with `@OnColorActivated` |
| `sample-service/.../engine/JobRepository.java` | Stamping `executed_by_color` in one place; recovery queries |
| `sample-service/.../tasks/LegacyStyleTasks.java`, `AuditCleanup.java` | Timer guard and `@ColorGated` |
| `sample-service/.../tasks/StartupTask.java`, `DemoTasks.java` | `@OnColorActivated`, `@Scheduled`, `@RunInAllColors` |
| `scripts/switch.sh` | The switch SQL |
