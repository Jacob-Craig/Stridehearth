#!/usr/bin/env bash
# Fails unless the PostGIS major pinned in deploy/compose.yaml equals the Dockerfile's POSTGRES_CLIENT_MAJOR
# (ADR-0010): pg_dump can dump an older server but not a newer one, so a Dependabot bump of the compose pin
# would otherwise silently break the pre-migration backup. Run it from the repository root.
set -euo pipefail

client=$(sed -nE 's/^ARG POSTGRES_CLIENT_MAJOR=([0-9]+)[[:space:]]*$/\1/p' Dockerfile)
server=$(sed -nE "s/^[[:space:]]*image:[[:space:]]*'?[^[:space:]'@]*postgis:([0-9]+)[-@'].*$/\1/p" deploy/compose.yaml)

[ -n "$client" ] || { echo "::error::No 'ARG POSTGRES_CLIENT_MAJOR=<n>' line found in Dockerfile." >&2; exit 1; }
[ -n "$server" ] || { echo "::error::No 'postgis:<major>-...' image found in deploy/compose.yaml." >&2; exit 1; }
[ "$(printf '%s\n' "$server" | wc -l)" -eq 1 ] || { echo "::error::More than one postgis image in deploy/compose.yaml." >&2; exit 1; }

if [ "$client" != "$server" ]; then
  echo "::error::Dockerfile POSTGRES_CLIENT_MAJOR is $client but deploy/compose.yaml pins PostGIS $server. Bump them together (ADR-0010)." >&2
  exit 1
fi
echo "PostgreSQL client and compose PostGIS majors agree: $client"
