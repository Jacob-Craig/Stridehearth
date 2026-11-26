# Self-hosting a Stridehearth Server

Stridehearth is published as a container image for `linux/amd64` and `linux/arm64` at
`ghcr.io/jacob-craig/stridehearth` (ADR-0009). Pulling it needs no login. A Server needs PostgreSQL with PostGIS
and Redis alongside it. [`deploy/compose.yaml`](../deploy/compose.yaml) runs all three.

## Quick start

```sh
cd deploy
cp .env.example .env      # set POSTGRES_PASSWORD
docker compose pull
docker compose up -d
```

Open <http://localhost:8080>. The first account to register becomes the Administrator. On a closed Server, the
Administrator adds anyone else with invite links (ADR-0011).

The sample binds the port to `127.0.0.1`, so the Server is reachable only from the host itself. For access from
other machines, see [Exposing the Server](#exposing-the-server).

The first start can take a few minutes, because database migrations run before the app answers. The container
shows `starting` until its first successful healthcheck. The healthcheck passes only once the app can reach both
PostgreSQL and Redis. Failed checks during the 3-minute start period don't count. After it, three failed checks in
a row (30 seconds apart) turn the container `unhealthy`. `docker compose ps` shows the health, and
`docker compose logs -f stridehearth` shows progress.

## Image tags

| Tag | What it is |
| --- | --- |
| `X.Y.Z` | One Release. Pin this on a Server. |
| `X.Y`, `X`, `latest` | Floating tags that move to the highest Release in their range. |
| `edge` | The latest build of `main`, replaced on every merge. It is the pre-release channel. |
| `@sha256:…` | One exact image, whatever tags later point at. |

Pin an exact version or digest. A floating tag combined with an auto-updater can apply a One-way Release (one with
irreversible migrations) without anyone choosing to (ADR-0010).

The sample pins every image by digest. Upgrading means editing the pin yourself:

- **Stridehearth.** Replace the digest, or switch the reference to a version tag such as `:X.Y.Z`, to move to the
  Release you want, after reading its notes. A One-way Release cannot be undone without a backup, and the Server
  takes one for you: see [Backups and going back](#backups-and-going-back).
- **PostgreSQL (PostGIS) and Redis.** Update the tag and digest when you choose to. You can copy them from the
  latest `deploy/compose.yaml` in this repository, which is kept current. Before copying, compare the PostgreSQL
  major version in the tag (for example `18` in `18-3.6`) with the one your Server runs, and copy only within the
  same major, unless you mean to do the dump and restore below. The Server's automatic backup covers only its own
  schema upgrades, not a PostGIS image change, so take a manual backup first (`docker compose exec -T postgres
  pg_dumpall -U "$POSTGRES_USER" > backup.sql`) before pulling a new PostGIS image. A major PostgreSQL version (for
  example 18 to 19) cannot read the old data directory without a dump and restore.

## Backups and going back

When a Stridehearth upgrade has pending database migrations, the Server first writes a `pg_dump` of its database to
`/app/backups` (the `backups` volume in the sample), named like
`stridehearth-v26-to-v28-20260304T050607Z.dump`: the schema versions it was taken between, then a UTC timestamp. It
keeps the latest three. If the dump fails, for example because the directory is not writable, the Server logs why,
applies no migration and does not start. A restart with nothing to migrate, and a first start on an empty database,
write no backup. The image bundles the PostgreSQL 18 client for this, which can dump PostgreSQL 18 and older
servers. A host directory mounted at `/app/backups` must be writable by uid 10001 (`chown 10001:10001 <dir>`).

To go back to the Release you ran before a One-way Release, restore the dump taken at that upgrade, then run the old
Release. The dump replaces the whole database, so anything recorded since the upgrade is lost.

```sh
docker compose stop stridehearth
docker compose cp stridehearth:/app/backups/stridehearth-v26-to-v28-20260304T050607Z.dump ./restore.dump
docker compose exec -T postgres dropdb -U "$POSTGRES_USER" --force "$POSTGRES_DB"
docker compose exec -T postgres createdb -U "$POSTGRES_USER" "$POSTGRES_DB"
docker compose exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error < restore.dump
# Set the Stridehearth image to the previous Release, then:
docker compose up -d
```

Use your own dump's file name, and your database user and name in place of the variables if they are not in your
shell.

## Environment variables

The image holds no configuration and no default credentials. Settings come from the container's environment. Any
Spring Boot property can be set this way: upper-case it and replace `.` and `-` with `_`. For example,
`stridehearth.import.concurrency` becomes `STRIDEHEARTH_IMPORT_CONCURRENCY`. The defaults are in
[`src/main/resources/application.yaml`](../src/main/resources/application.yaml).

### Required

The app refuses to start without these.

| Variable | Example |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/stridehearth` |
| `SPRING_DATASOURCE_USERNAME` | `stridehearth` |
| `SPRING_DATASOURCE_PASSWORD` | (secret) |
| `SPRING_DATA_REDIS_HOST` | `redis` |

### Commonly set

| Variable | Default | Notes |
| --- | --- | --- |
| `STRIDEHEARTH_REGISTRATION_MODE` | `closed` | `closed` lets only the first account register itself, and `open` lets anyone sign up (ADR-0011). Any other value stops the Server from starting. Open mode is not yet hardened for the public internet. |
| `SERVER_FORWARD_HEADERS_STRATEGY` | unset | Set it to `native` behind a reverse proxy. See below. |
| `SPRING_DATA_REDIS_PORT` | `6379` | |
| `SERVER_PORT` | `8080` | The image's healthcheck follows it. If you change it, change the container side of the compose `ports` mapping to match (for example `127.0.0.1:8080:9090`). |
| `FIT_REPAIR_TOOL_PATH` | empty | Optional path to Garmin's `ActivityRepairTool.jar`. Empty disables FIT repair. |

Never set `SPRING_PROFILES_ACTIVE=dev` on a Server. That profile has a known database password and opens
registration.

## Exposing the Server

The app serves plain HTTP. Sign-in passwords and session cookies cross that connection, so don't expose it to an
untrusted network without TLS.

- **Remote or internet access.** Keep the `127.0.0.1` binding and run a TLS-terminating reverse proxy on the same
  host (for example Caddy, Traefik or nginx) that forwards to `http://127.0.0.1:8080`. Also set
  `SERVER_FORWARD_HEADERS_STRATEGY=native` (see below).
- **Proxy in another container.** Put the proxy on the compose network and forward it to `http://stridehearth:8080`.
  Then the `ports` entry can be removed altogether.
- **Trusted LAN only.** Change the mapping to `'8080:8080'` to listen on every interface.

## Tips

- **Reverse proxy.** Behind a reverse proxy, set `SERVER_FORWARD_HEADERS_STRATEGY=native`. Sign-in and sign-up
  limits are then counted for each client's address from `X-Forwarded-For`, not for the proxy's address. Without
  it, every athlete shares one budget. With it, `X-Forwarded-For` is believed from any private-network address, so
  leave it unset when clients can reach the Server directly, or they can choose their own address.
- **Uploads volume.** The app runs as uid 10001 and writes only to `/app/temp-batches`, where import uploads are
  staged, and `/app/backups`. A named volume mounted there takes the right owner automatically. A host directory mounted there must be
  writable by uid 10001 (`chown 10001:10001 <dir>`).
- **Apple Silicon and other arm64 hosts.** The Stridehearth image runs natively. The sample uses
  `imresamu/postgis`, a multi-architecture PostGIS build, because `postgis/postgis` publishes only amd64. That image
  would run under emulation on arm64.
- **Health.** `/actuator/health` returns `UP` or `DOWN` with no details. It reports `DOWN` while PostgreSQL or Redis
  is unreachable. It is the only actuator endpoint exposed.
- **Building from source.** Run `docker build -t stridehearth .` at the repository root, then set `image:
  stridehearth` in the compose file.
