# Where the active color comes from: mounted ConfigMap vs Kubernetes API

**Audience:** architects reviewing the blue/green color gate.
**Purpose:** compare the two Kubernetes-based ways for each pod to learn which color (BLUE or GREEN) is active,
and record a recommendation.
**Status:** proposal. The POC in this repository currently reads the active color from a database table; this
document proposes replacing that with a ConfigMap.

---

## 1. Context

- Services run on GKE. Each **tier has its own cluster** (3 clusters). Each cluster has **two namespaces, `blue` and
  `green`**, shared by all services of that tier. About 400 pods in total.
- During a release both colors run. User traffic is moved by the load balancer, but **background work** (`@Scheduled`,
  cron, polling/calendar tasks, startup tasks, the execution engine's job recovery) is started by each pod itself and
  must run **only in the active color**.
- The color gate library (Spring Boot starter) makes each pod check "is my color the active one?" and only then runs
  background work. Each pod knows **its own** color from the env var `APP_COLOR` (set per namespace by Helm).
- **All tiers switch together**, ordered as: deploy new color (passive) → smoke test → switch load balancers → **stop
  the old color in all clusters** → set the active color. The old color is always stopped before the flip, so at most
  one color is alive and active.
- Requests between tiers are unaffected: a passive service still serves requests; only its self-started background
  work waits.

### Requirements for the "active color" source

| # | Requirement |
|---|---|
| R1 | Works for **every** service, including services **without a database** |
| R2 | Kubernetes only (no DB, no hybrid) |
| R3 | A running pod notices a change **without restarting**; **≤ 2 minutes** is acceptable, faster is better |
| R4 | Negligible load at ~400 pods |
| R5 | Safe failure behaviour: if the source cannot be read, pods **keep their current state** |
| R6 | Simple to operate across 3 clusters; changed only by the deployment pipeline |
| R7 | Minimal change per service (there is **one shared base Helm chart**) |

---

## 2. Background: what a ConfigMap is

A ConfigMap is a small Kubernetes object holding plain `key: value` configuration, stored in the cluster's own
database (etcd, managed and replicated by GKE). It lives in one namespace of one cluster.

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: color-gate
  namespace: green
data:
  active-color: GREEN
```

The pipeline creates or updates it with `kubectl`. Pods can consume it in three ways:

| Way | Updates while the pod runs? | Used here |
|---|---|---|
| As environment variables | No (only on restart) | ❌ unsuitable |
| **Mounted as a file** | Yes, refreshed by the kubelet | **Option B** |
| **Read through the Kubernetes API** | Yes, instantly (watch) | **Option C** |

(Option letters follow the earlier comparison, where A was the database table.)

---

## 3. Option B: ConfigMap mounted as a file

### How it works

```
 pipeline ── kubectl apply (active-color: GREEN) ──► API server ──► ConfigMap stored in etcd
                                                                       │
                     kubelet on each node watches it and refreshes ────┘
                     the mounted copy for each pod (typically < 1 min)
                                        │
                                        ▼
 inside the pod:  /etc/colorgate/active-color  →  "GREEN"
                                        │
                     library reads the local file every second (no network)
                                        ▼
                     my color is active → @OnColorActivated → gate opens
```

The mounted copy is a few bytes in a per-pod folder on the node's existing disk
(`/var/lib/kubelet/pods/<pod-id>/volumes/kubernetes.io~configmap/...`), exposed inside the container at
`/etc/colorgate`. No persistent volume or extra disk is involved. The kubelet updates it atomically: it writes a new
version folder, then switches a `..data` symlink in one step, so the application only ever sees a complete old or a
complete new value.

### What has to be built or changed

| Where | Change | Effort |
|---|---|---|
| Base Helm chart (once) | Add a volume from ConfigMap `color-gate` (`optional: true`) and mount it read-only at `/etc/colorgate`. **No `subPath`** (subPath mounts are never refreshed). | Low |
| Library | `FileActiveColorSource`: read `/etc/colorgate/active-color` (path configurable), trim, parse `BLUE`/`GREEN`; missing file = nobody active. Plain Java, no new dependency. Remove the JDBC source and dependency. | Low |
| Pipeline | Write the same value to the ConfigMap in **both namespaces of every cluster** (6 writes), then read them back to verify | Low |
| Platform/DevOps | Confirm the pipeline may update ConfigMaps in `blue`/`green` (very likely already true) | Very low |

Base chart snippet:
```yaml
      containers:
        - name: app
          volumeMounts:
            - name: color-gate
              mountPath: /etc/colorgate
              readOnly: true
      volumes:
        - name: color-gate
          configMap:
            name: color-gate
            optional: true        # pod starts even if missing → passive
```

Pipeline step (per cluster, per namespace):
```bash
kubectl --context "$CLUSTER" -n "$NS" create configmap color-gate \
  --from-literal=active-color=GREEN --dry-run=client -o yaml | kubectl --context "$CLUSTER" apply -f -
```

### Behaviour

| Aspect | Behaviour |
|---|---|
| Switch latency | kubelet sync period (default 1 min) plus a small cache delay: **usually under a minute, worst case about 1–2 min** |
| Optional speed-up | Annotating the pods after the switch (`kubectl annotate pods --all ...`) usually makes the kubelet refresh immediately. **Not yet verified on GKE.** |
| Runtime load | None: a local file read per pod per second |
| App permissions | **None** (no API calls; pods can keep `automountServiceAccountToken: false`) |
| API server outage / GKE control-plane upgrade | Files keep their last value → pods keep their state |
| Node failure | Pod rescheduled, gets a fresh copy from etcd |
| Local development | The "ConfigMap" is just a file on disk; the POC scripts can write it |

---

## 4. Option C: ConfigMap read through the Kubernetes API

### How it works

```
 pipeline ── kubectl apply (active-color: GREEN) ──► API server ──► ConfigMap stored in etcd
                                                        │
                    each pod keeps one open "watch" ────┘  (pushed to the pod immediately)
                                        │
                                        ▼
 library: in-memory value "GREEN"  →  my color is active → @OnColorActivated → gate opens
```

The library uses a Kubernetes client (e.g. the fabric8 client's informer, which handles watch reconnects) to watch a
single ConfigMap and keeps the latest value in memory.

### What has to be built or changed

| Where | Change | Effort |
|---|---|---|
| Library | `KubernetesActiveColorSource`: Kubernetes client dependency, an informer on ConfigMap `color-gate`, reconnect handling, tests. Remove the JDBC source and dependency. | Medium |
| RBAC (once per cluster) | A Role allowing `get`, `list`, `watch` on ConfigMap `color-gate`, and a RoleBinding to the groups `system:serviceaccounts:blue` and `system:serviceaccounts:green` (all pods in those namespaces). **4 objects per cluster, 12 in total.** Ideally in a platform chart or GitOps repo. | Low (but needs DevOps/cluster-admin) |
| Base Helm chart | Only if pods currently disable `automountServiceAccountToken`: re-enable it (pods need their token to call the API) | None to low |
| Network | If NetworkPolicies restrict egress, allow pods to reach the API server | None to low |
| Pipeline | Same as B. **Optionally one ConfigMap per cluster** (e.g. in a `platform` namespace, readable from both namespaces through RBAC): **3 writes instead of 6** | Low |

RBAC sketch:
```yaml
kind: Role
metadata: { name: color-gate-reader, namespace: green }
rules:
  - apiGroups: [""]
    resources: ["configmaps"]
    resourceNames: ["color-gate"]
    verbs: ["get", "list", "watch"]
---
kind: RoleBinding
metadata: { name: color-gate-reader, namespace: green }
roleRef: { apiGroup: rbac.authorization.k8s.io, kind: Role, name: color-gate-reader }
subjects:
  - { kind: Group, name: "system:serviceaccounts:green", apiGroup: rbac.authorization.k8s.io }
```

### Behaviour

| Aspect | Behaviour |
|---|---|
| Switch latency | **About 1 second** (push) |
| Runtime load | ~400 long-lived watch connections across 3 clusters; light for the API servers (watches are how Kubernetes components themselves work) |
| App permissions | Read access to one ConfigMap |
| API server outage / GKE control-plane upgrade | Watch drops; the library keeps the last value and reconnects |
| Node failure | Pod rescheduled; it lists the ConfigMap and opens a new watch |
| Local development | Needs a cluster (or the gate disabled) |

---

## 5. Side-by-side comparison

| Criterion | B. Mounted file | C. Kubernetes API |
|---|---|---|
| R1 Works without a DB | ✅ | ✅ |
| R2 Kubernetes only | ✅ | ✅ |
| R3 Switch latency | ⚠️ usually < 1 min, up to ~2 min | ✅ ~1 s |
| R4 Runtime load | ✅ none | ✅ light (watches) |
| R5 Source unreachable | ✅ last value kept on disk | ✅ last value kept in memory |
| R6 Writes per switch | 6 (2 namespaces × 3 clusters) | 6, or 3 with one ConfigMap per cluster |
| R7 Per-service change | ✅ none (base chart, once) | ✅ none |
| Library complexity | ✅ low (read a file, no dependency) | ⚠️ medium (client library, informer, reconnects) |
| New runtime dependency | ✅ none | ⚠️ Kubernetes client (several MB, transitive dependencies to align with Spring Boot) |
| App needs API access / token | ✅ no | ⚠️ yes |
| Cluster-admin work | ✅ none expected | ⚠️ 12 RBAC objects (once) |
| Network policy impact | ✅ none | ⚠️ egress to API server must be allowed |
| Security surface | ✅ smallest | ✅ small (read one ConfigMap) |
| Local dev / POC | ✅ a file | ⚠️ needs a cluster |
| Debugging | `kubectl get configmap`, `kubectl exec ... cat /etc/colorgate/active-color` | `kubectl get configmap`, library logs |
| Cost | none | none |

---

## 6. Risks shared by both options, and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| **The ConfigMap is owned by a Helm release** | Every `helm upgrade` would reset `active-color` to the chart's value and could flip the color mid-release | **Do not template the ConfigMap in the service chart.** Only the pipeline's switch step creates and updates it. (The chart only mounts it, with `optional: true`.) |
| Pipeline writes an invalid value (e.g. `GREN`) | Treated as "nobody active", so background work stops everywhere | Validate the value in the pipeline before writing; the library logs a warning for unknown values |
| Pipeline fails after updating some of the ConfigMaps | Clusters disagree | Make the step idempotent (re-run writes all), then read back all ConfigMaps and fail the pipeline on any mismatch |
| Rollback: old color scaled up while its namespace's ConfigMap is stale | Old color could start active while the new one still runs | Always write the **same value to both namespaces** on every switch; on rollback write the new value **before** scaling the old color up |
| Color flipped while the old color is still running (ordering bug) | Duplicate background work | Pipeline waits until no old-color pods remain before writing the ConfigMaps |
| ConfigMap missing when a pod starts | Pod starts passive and does no background work | Create the ConfigMaps before the first release with the gate enabled; `optional: true` keeps pods starting |

---

## 7. Recommendation

**Option B (mounted ConfigMap file).**

- It meets every requirement, including R3: the expected delay (usually under a minute) is inside the accepted
  2 minutes.
- It is the simplest to build and run: no new library dependency, no API access or token for applications, no RBAC,
  no network-policy changes, zero runtime load, and it works locally as a plain file.
- With one base Helm chart, the per-service change is made once.

**Choose Option C instead if** switch latency must be near-instant, or if a single ConfigMap per cluster (3 writes
instead of 6) is worth the client library and the one-time RBAC setup.

**Moving from B to C later is cheap:** the library reads the color through one interface (`ActiveColorSource`), so
only that implementation changes. Annotations, recovery rules and service code stay the same.

## 8. Open points

1. Confirm the pipeline's service account may `create`/`update` ConfigMaps in `blue` and `green` in all three
   clusters (`kubectl auth can-i update configmaps -n green`).
2. Measure the real refresh delay in a GKE dev cluster, and test the optional pod-annotation speed-up.
3. Agree the ConfigMap name, key and mount path: proposed `color-gate`, `active-color`, `/etc/colorgate`.
4. Decide who owns creating the ConfigMaps for a new cluster (pipeline bootstrap or platform GitOps).

---

## Appendix: other sources considered

| Option | Why not chosen |
|---|---|
| Database table (current POC) | Services without a DB cannot use it; the pipeline needs write access to every service database; one query per pod per second |
| Environment variable + restart | Changing it restarts pods, which kills running jobs; cannot change while a pod runs |
| Pod annotations via the Downward API | Annotations put on running pods are lost when a pod is recreated, so a restarted pod would start passive |
| Pipeline calls an admin endpoint on each pod | No stored state: a pod that restarts later does not know the color; must reach every pod in every cluster |
| Custom resource (CRD) or Lease object | Same mechanics as Option C with more setup (a CRD needs cluster-admin) and no benefit for a single value |
| External store or feature-flag service (Consul, Redis, Spring Cloud Config, LaunchDarkly/Unleash, GCP Parameter/Secret Manager, Firestore) | New infrastructure and network dependency for every service; credentials in every pod; more moving parts than a ConfigMap for one value |
| Message broker event (Kafka, Pub/Sub) | Events are not state: a pod that starts after the event misses it, so a stored value is still needed |
