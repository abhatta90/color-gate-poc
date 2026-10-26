#!/usr/bin/env bash
# Shows the active color, the jobs, recent background work, and checks for duplicates and missed cron runs.
set -euo pipefail
source "$(dirname "$0")/db.sh"

q() {
  echo
  echo "== $1"
  psql_cmd -P pager=off -c "$2"
}

q "color_gate" \
  "SELECT service_name, active_color, switched_at FROM color_gate"

q "jobs (latest 20)" \
  "SELECT id, name, status, attempts, executed_by_color AS color, executed_by,
          to_char(started_at, 'HH24:MI:SS') AS started, to_char(finished_at, 'HH24:MI:SS') AS finished
     FROM engine_job ORDER BY id DESC LIMIT 20"

q "running right now, per color (before stopping a color: how many of its jobs will restart)" \
  "SELECT coalesce(executed_by_color, '(none)') AS color, count(*) AS executing
     FROM engine_job WHERE status = 'EXECUTING' GROUP BY 1 ORDER BY 1"

q "background work (latest 25)" \
  "SELECT to_char(at, 'HH24:MI:SS') AS at, color, task, job_id, detail
     FROM audit_log ORDER BY id DESC LIMIT 25"

q "DUPLICATE CHECK: jobs finished more than once (should be empty)" \
  "SELECT job_id, count(*) AS times_finished, string_agg(color, ', ') AS by_colors
     FROM audit_log WHERE task = 'job-finished' GROUP BY job_id HAVING count(*) > 1"

q "DUPLICATE CHECK: cron fired more than once in a minute (should be empty)" \
  "SELECT to_char(date_trunc('minute', at), 'HH24:MI') AS minute, string_agg(color, ', ') AS by_colors
     FROM audit_log WHERE task = 'cron-every-minute' GROUP BY 1 HAVING count(*) > 1"

q "cron runs per minute since the first one (missed = no active color at :00, e.g. during a switch)" \
  "SELECT to_char(m.minute, 'HH24:MI') AS minute, coalesce(string_agg(a.color, ', '), '-- missed --') AS ran_in
     FROM generate_series(
            (SELECT date_trunc('minute', min(at)) FROM audit_log WHERE task = 'cron-every-minute'),
            date_trunc('minute', now()) - interval '1 minute',
            interval '1 minute') AS m(minute)
     LEFT JOIN audit_log a ON a.task = 'cron-every-minute' AND date_trunc('minute', a.at) = m.minute
    GROUP BY m.minute ORDER BY m.minute DESC LIMIT 15"
