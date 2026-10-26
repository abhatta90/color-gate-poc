#!/usr/bin/env bash
# Simulates the pipeline's switch step: makes BLUE or GREEN the active color for sample-service.
#
# Rule: stop the old color BEFORE running this. Otherwise the new color's recovery resubmits
# jobs the old color is still running.
#
# Usage: scripts/switch.sh GREEN
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/db.sh

color=$(echo "${1:-}" | tr '[:lower:]' '[:upper:]')
if [[ "$color" != "BLUE" && "$color" != "GREEN" ]]; then
  echo "usage: $0 BLUE|GREEN" >&2
  exit 1
fi

# Creates the table if no app has started yet.
psql_cmd -q -v ON_ERROR_STOP=1 < color-gate-spring-boot-starter/src/main/resources/colorgate/schema.sql

psql_cmd -q -v ON_ERROR_STOP=1 -v color="$color" <<'SQL'
INSERT INTO color_gate (service_name, active_color, switched_at, switched_by)
VALUES ('sample-service', :'color', now(), 'scripts/switch.sh')
ON CONFLICT (service_name) DO UPDATE
   SET active_color = EXCLUDED.active_color, switched_at = now(), switched_by = EXCLUDED.switched_by;
SQL

psql_cmd -P pager=off -c "SELECT service_name, active_color, switched_at FROM color_gate"
