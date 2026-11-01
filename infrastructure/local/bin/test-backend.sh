#!/usr/bin/env sh
set -eu

repository_root=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
context=$(docker context show 2>/dev/null || true)

if [ "$context" = "colima" ]; then
  export DOCKER_HOST="$(docker context inspect colima --format '{{.Endpoints.docker.Host}}')"
  # Colima cannot mount its macOS socket into Testcontainers' cleanup sidecar.
  export TESTCONTAINERS_RYUK_DISABLED=true
fi

exec "$repository_root/gradlew" test verifyServiceBoundaries verifyContracts
