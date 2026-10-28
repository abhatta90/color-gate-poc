# Color gate POC

Blue/green deployments run two copies of a service at once. HTTP traffic is moved by the load balancer, but
background work (scheduled tasks, the calendar task, startup tasks, the execution engine) would run in both
colors. The **color gate** library makes background work run only in the **active** color.

| Folder | What it is |
|---|---|
| `color-gate-spring-boot-starter/` | The library (Spring Boot 3.5 starter, Java 21) |
| `sample-service/` | Demo service: mini execution engine, calendar task, `@Scheduled`, cron, `TimerTask`, startup task |
| `scripts/` | Simulate the pipeline: `switch.sh`, `status.sh`, `reset.sh` |
| `.run/` | IntelliJ run configurations: `sample-service BLUE` (port 8081), `sample-service GREEN` (port 8082) |

Build: Gradle (Groovy DSL) with the wrapper, so no Gradle install is needed.

**Documents:**
- [docs/color-gate-poc.md](docs/color-gate-poc.md): the problem, decisions, what was built, and how to verify it.
- [docs/color-gate-integration.md](docs/color-gate-integration.md): how to add the library to an existing service
  and its tables.
- [docs/active-color-source-options.md](docs/active-color-source-options.md): for architects, mounted ConfigMap
  vs Kubernetes API as the source of the active color (proposal to replace the DB table).

## How it works

- Each pod knows its own color from the `APP_COLOR` env var (per namespace in Helm).
- The `color_gate` table has one row per service with the `active_color`. **Only the pipeline writes it.**
  No row means nobody is active.
- Every pod reads its row every second. The pod whose color is active runs background work; the other is passive.
- When a pod becomes active, its `@OnColorActivated` methods run first, and only then does gated work start.
  The event says why it became active: `STARTUP` (the pod started while its color was already active) or
  `SWITCH` (the pipeline switched to its color while it was running).

The switch, in pipeline order:

1. Deploy green. It starts **passive**. Manual jobs and smoke tests can already run on it.
2. Smoke test green.
3. Switch the ALB to green.
4. **Stop blue** and wait until its pods are gone. Its running jobs stay `EXECUTING`, like after a crash.
5. `UPDATE color_gate SET active_color = 'GREEN'`. Green activates (`SWITCH`) and resubmits **only blue's**
   `EXECUTING` jobs. Green's own jobs keep running.

Step 4 must finish before step 5, otherwise green resubmits jobs blue is still running.

### Job recovery rule

Every job records which color started it (`engine_job.executed_by_color`). On activation:

| Why the pod became active | Resubmitted |
|---|---|
| `SWITCH` (release day) | `EXECUTING` jobs **not** started by this color: the stopped color's jobs |
| `STARTUP` (a pod restarts in production) | **All** `EXECUTING` jobs, exactly as before blue/green |

## Run it

Prerequisites: Java 21 and Postgres running locally (`brew services start postgresql@17`) with the
`colorgate` user and database.

The database connection can be changed with the env vars `DB_URL`, `DB_USER` and `DB_PASSWORD` (defaults:
`jdbc:postgresql://localhost:5432/colorgate`, `colorgate`, empty). Never commit a real password; see
[docs/color-gate-poc.md](docs/color-gate-poc.md), section 5.

The scripts connect to `localhost:5432` over TCP, use `DB_PASSWORD` if set, and fall back to running `psql`
inside the Docker container `colorgate-pg` when the `psql` client is not installed.
`scripts/sql.sh "<sql>"` runs any query the same way.

### IntelliJ

File → Open → select the `color-gate-poc` folder. IntelliJ imports it as a Gradle project, and the run
configurations `sample-service BLUE` and `sample-service GREEN` appear in the run dropdown (top right).
Run the scripts from IntelliJ's terminal at the project root.

If you opened this project earlier when it was a Maven project: File → Close Project, delete the `.idea`
folder, then open the folder again so it is imported fresh as Gradle.

### Terminal

```bash
./gradlew build
```
```bash
APP_COLOR=BLUE java -jar sample-service/build/libs/sample-service-0.0.1-SNAPSHOT.jar --server.port=8081
```

Tables are created automatically on startup. `scripts/reset.sh` drops them to start over (stop both apps first).

## Walkthrough

**1. Start BLUE.** There is no `color_gate` row yet, so it starts passive:

```
Color gate: BLUE starts PASSIVE (active color: none)
```

**2. Make BLUE active,** as the very first deployment would:

```bash
scripts/switch.sh BLUE
```
```
Color gate: BLUE is ACTIVE (SWITCH), running @OnColorActivated handlers
Recovery (SWITCH): 0 EXECUTING job(s) to resubmit
Color gate: BLUE gate OPEN, background work enabled
```

**3. Create scheduled jobs in BLUE.** Three long jobs and one that only becomes due in 60 seconds:

```bash
curl -X POST "localhost:8081/jobs?name=long&count=3&durationSeconds=120"
```
```bash
curl -X POST "localhost:8081/jobs?name=delayed&durationSeconds=30&delaySeconds=60"
```

**4. Start GREEN.** It starts passive: no scheduled or background work, except the `@RunInAllColors` cache refresh.

```
Color gate: GREEN starts PASSIVE (active color: BLUE)
```

**5. Smoke-test GREEN with manual jobs.** `run-now` starts a job immediately in the pod that receives the
request, like a manual job in your system, even though GREEN is passive:

```bash
curl -X POST "localhost:8082/jobs/run-now?name=smoke&count=2&durationSeconds=120"
```

`scripts/status.sh` → "running right now, per color" shows BLUE 3 and GREEN 2.

**6. Switch.** Stop BLUE (the red stop button in IntelliJ), wait until it has exited, then:

```bash
scripts/switch.sh GREEN
```
```
Color gate: GREEN is ACTIVE (SWITCH), running @OnColorActivated handlers
Recovery (SWITCH): 3 EXECUTING job(s) to resubmit
Color gate: GREEN gate OPEN, background work enabled
```

Only BLUE's 3 long jobs are resubmitted; the 2 smoke jobs keep running untouched.

**7. Check** with `scripts/status.sh` once the jobs are done:

- The long jobs show `attempts = 2` and color GREEN: they restarted from the beginning.
- The smoke jobs show `attempts = 1`: never resubmitted.
- The delayed job was started by GREEN when it became due.
- Both duplicate checks are empty.
- If the switch window covered second `:00` of a minute, that minute's cron run shows as `-- missed --`
  (the accepted trade-off).

**8. A pod restart in production.** GREEN is now production. Start a manual job, then restart GREEN
(stop and run it again in IntelliJ) while the job is still running:

```bash
curl -X POST "localhost:8082/jobs/run-now?name=prod&durationSeconds=60"
```
```
Color gate: GREEN is ACTIVE (STARTUP), running @OnColorActivated handlers
Recovery (STARTUP): 1 EXECUTING job(s) to resubmit
```

On a restart every `EXECUTING` job is resubmitted, as before blue/green.

**9. Roll back** the same way in reverse: start BLUE (passive), stop GREEN, `scripts/switch.sh BLUE`.
BLUE resubmits only GREEN's jobs.

### Things to try

- **Break the rule:** switch to GREEN *without* stopping BLUE. The duplicate check then shows jobs finished twice.
- **Hard crash:** `kill -9 $(lsof -ti tcp:8081)` instead of a normal stop. The result is the same.
- **DB blip:** `brew services stop postgresql@17` for a few seconds. The active pod logs
  `cannot read color_gate, ... stays ACTIVE` and keeps its state.
- **No blue/green** (local dev): set `colorgate.enabled=false`. The pod is always active and startup behaves as before.
- See a pod's state: `curl localhost:8081/actuator/colorgate`.

## Using the library in a real service

1. Add the dependency `com.example.colorgate:color-gate-spring-boot-starter`.
2. Database changes, through your Flyway/Liquibase migrations:
   - The `color_gate` table (DDL in `color-gate-spring-boot-starter/src/main/resources/colorgate/schema.sql`).
   - A seed row with the color you deploy into first. **No row means nobody is active.**
   - One column on your job table: `executed_by_color VARCHAR(10)`, set wherever a job becomes `EXECUTING`
     (manual and scheduled paths). Keep that in one method so no path can forget it.
3. Set `APP_COLOR=BLUE` or `GREEN` per namespace in Helm.
4. Make these code changes:

| What you have today | Change |
|---|---|
| `@Scheduled` (fixedRate, fixedDelay, cron) | **None.** Gated automatically. Add `@RunInAllColors` for pod-local work such as cache refresh. |
| Startup work: `@EventListener(ApplicationReadyEvent.class)`, `ApplicationRunner`, `CommandLineRunner` | Use `@OnColorActivated` instead. |
| Execution engine "resubmit EXECUTING jobs on startup" | `@OnColorActivated`, with the recovery rule above (see `ExecutionEngine.recoverExecutingJobs`). |
| `TimerTask`, executor loops, custom threads | First line of each tick: `if (!colorGate.isActive()) return;`, or put `@ColorGated` on the method the loop calls. |

5. Add the pipeline steps from **How it works**.

### Configuration

| Property | Default | Meaning |
|---|---|---|
| `colorgate.enabled` | `true` | `false` where there is no blue/green: always active |
| `colorgate.my-color` | `APP_COLOR` env var | This pod's color, `BLUE` or `GREEN`. Startup fails if missing |
| `colorgate.service-name` | `spring.application.name` | Row key in `color_gate` |
| `colorgate.poll-interval` | `1s` | How often `color_gate` is read |

### Behaviour to know

- **Activation order:** `@OnColorActivated` methods finish before any gated work starts, so the calendar task
  cannot claim a job that recovery would then treat as orphaned. Keep these methods short.
- **DB unreachable:** the pod keeps its current state. Flipping passive and back would re-run recovery while
  the pod's own jobs are still running.
- **`@ColorGated`** works through a Spring proxy: use it on `void` methods, and it does not apply to calls from
  inside the same class.
- **A green pod that crashes before the switch** while running manual jobs: those jobs are not recovered at the
  switch (they carry GREEN's color). The next pod restart or a manual resubmit picks them up, as for any crash today.

### Accepted trade-offs in v1

- A cron fire time inside the switch window (5–35 s, mostly blue's shutdown time) is missed.
- Jobs killed with blue restart from the beginning in green.

Possible later additions: record `paused_at` before stopping blue and replay missed cron runs on activation;
a shorter `terminationGracePeriodSeconds` to shrink the window.
