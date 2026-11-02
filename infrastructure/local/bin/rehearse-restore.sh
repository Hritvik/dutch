#!/usr/bin/env bash
# Restore rehearsal for the local topology. It dumps each service database,
# restores it into a sibling database, and reconciles Ledger projections and
# outbox counts before dropping the restored copies.
set -euo pipefail

here="$(cd "$(dirname "$0")/.." && pwd)"
cd "$here"
if [[ ! -f .env ]]; then
  echo "Copy .env.example to .env first." >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
source ./.env
set +a

compose=(docker compose --env-file .env -f compose.yaml)
stamp="$(date +%s)"
workdir="$(mktemp -d)"
superuser="${DUTCH_LOCAL_POSTGRES_USER:-dutch_local_admin}"
created=()
cleanup() {
  for database in "${created[@]:-}"; do
    psql -d postgres -c "DROP DATABASE IF EXISTS \"${database}\"" >/dev/null 2>&1 || true
  done
  rm -rf "$workdir"
}
trap cleanup EXIT
services=(iam ums ledger notification)

psql() {
  "${compose[@]}" exec -T postgres psql -v ON_ERROR_STOP=1 -U "$superuser" "$@"
}

for service in "${services[@]}"; do
  database="dutch_${service}"
  restored="dutch_${service}_rehearsal_${stamp}"
  dump="$workdir/${service}.dump"

  echo "==> ${service}: dump"
  "${compose[@]}" exec -T postgres pg_dump -Fc -U "$superuser" "$database" > "$dump"
  before="$(psql -d "$database" -Atc "SELECT count(*) FROM flyway_schema_history WHERE success")"

  echo "==> ${service}: restore into ${restored}"
  psql -d postgres -c "CREATE DATABASE \"${restored}\""
  created+=("$restored")
  "${compose[@]}" exec -T postgres pg_restore -U "$superuser" -d "$restored" < "$dump"
  after="$(psql -d "$restored" -Atc "SELECT count(*) FROM flyway_schema_history WHERE success")"
  if [[ "$before" != "$after" || "$after" -eq 0 ]]; then
    echo "${service}: migration history mismatch (${before} -> ${after})" >&2
    exit 1
  fi
  failures="$(psql -d "$restored" -Atc "SELECT count(*) FROM flyway_schema_history WHERE success = false")"
  if [[ "$failures" != "0" ]]; then
    echo "${service}: restored history contains failed migrations" >&2
    exit 1
  fi

  if [[ "$service" == "ledger" ]]; then
    entries_before="$(psql -d "$database" -Atc "SELECT count(*) FROM obligation_entry")"
    entries_after="$(psql -d "$restored" -Atc "SELECT count(*) FROM obligation_entry")"
    outbox_before="$(psql -d "$database" -Atc "SELECT count(*) FROM outbox_event")"
    outbox_after="$(psql -d "$restored" -Atc "SELECT count(*) FROM outbox_event")"
    if [[ "$entries_before" != "$entries_after" || "$outbox_before" != "$outbox_after" ]]; then
      echo "ledger: entry/outbox counts changed across restore" >&2
      exit 1
    fi
    drift="$(psql -d "$restored" -Atc "
      WITH expected AS (
        SELECT stream_id, member_id, SUM(amount) AS balance FROM (
          SELECT stream_id, creditor_member_id AS member_id, minor_units AS amount FROM obligation_entry
          UNION ALL
          SELECT stream_id, debtor_member_id, -minor_units FROM obligation_entry
        ) entries GROUP BY stream_id, member_id
      ), actual AS (
        SELECT stream_id, member_id, balance_minor_units FROM member_balance_projection
      )
      SELECT count(*) FROM (
        SELECT * FROM expected EXCEPT SELECT * FROM actual
        UNION ALL
        SELECT * FROM actual EXCEPT SELECT * FROM expected
      ) diff")"
    if [[ "$drift" != "0" ]]; then
      echo "ledger: projection reconciliation found ${drift} differences" >&2
      exit 1
    fi
    echo "ledger: entries=${entries_after} outbox=${outbox_after} projection-drift=0"
  fi

  echo "==> ${service}: rehearsal passed"
done

echo "Restore rehearsal passed for ${services[*]}"
