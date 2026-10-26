#!/usr/bin/env bash
# Deletes all POC tables and data so you can start the scenarios again.
# Stop both apps first; they recreate their tables on the next start.
set -euo pipefail
source "$(dirname "$0")/db.sh"
psql_cmd -c "DROP TABLE IF EXISTS engine_job, audit_log, color_gate"
