#!/usr/bin/env sh
set -eu

directory=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

if [ ! -f "$directory/.env" ]; then
  echo "Create $directory/.env from .env.example and set local-only passwords." >&2
  exit 1
fi

echo "Resetting Dutch's named local PostgreSQL volume only."
docker compose --env-file "$directory/.env" -f "$directory/compose.yaml" down --volumes --remove-orphans
"$directory/bin/up.sh"
