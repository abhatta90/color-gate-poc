#!/usr/bin/env bash
# Deletes all POC tables and data so you can start the scenarios again.
# Stop both apps first; they recreate their tables on the next start.
set -euo pipefail
export PGOPTIONS='-c client_min_messages=warning'
psql -U colorgate -d colorgate -c "DROP TABLE IF EXISTS engine_job, audit_log, color_gate"
