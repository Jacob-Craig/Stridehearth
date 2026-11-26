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

## Registration Mode and the Administrator

A Server's Registration Mode decides who can create an account (ADR-0011). It is set with
`STRIDEHEARTH_REGISTRATION_MODE` in `.env`, and the sample's default is `closed`.

- **`closed`** (default). Only the first account on an empty Server can register itself. Everyone else needs an
  Invite. The sign-in screen says the Server is closed.
- **`open`**. Anyone who can reach the Server can create an account. It is not yet hardened for the public internet,
  so use it only on a network you trust.

The first account ever registered becomes the **Administrator**. Register it yourself straight after the first start,
before anyone else can reach the Server: until it exists, anyone who reaches the sign-up page can claim it. A Server
has at most one Administrator.

On a closed Server the Administrator adds people from the **Invites** page (`/invites`). An Invite is a single-use
link that lets one person choose their own username and password. It expires after 7 days, can be revoked before it
is used, and is shown only once, when you create it, so copy it then. Anyone with the link can use it. Invites work
the same way on an open Server.

Changing the mode means editing `.env` and running `docker compose up -d`, which recreates the Stridehearth
container. Existing accounts are unaffected. Any value other than `closed` or `open` stops the Server from starting.

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

## Upgrading

Upgrade by hand, on a day you can spare a few minutes. Some Releases are One-way Releases: they contain database
migrations that the previous Release cannot read. The Server's automatic backup makes going back possible, but only
back to the state at the moment of the upgrade.

1. **Read the release notes** for every Release between yours and the one you want, and look for a One-way Release.
2. **Pick the version.** Edit the `image:` line of the `stridehearth` service to the exact version, for example
   `ghcr.io/jacob-craig/stridehearth:X.Y.Z`. Write down the previous value: you need it to go back.
3. **Pull and start.** From the directory holding your compose file:

   ```sh
   docker compose pull
   docker compose up -d
   docker compose logs -f stridehearth
   ```

   If the new Release has pending migrations, the Server first writes a `pg_dump` (see
   [Backups and going back](#backups-and-going-back)), then migrates. Wait for `docker compose ps` to show
   `healthy`.
4. **Check it.** Sign in and look at some activities. If something is wrong, go back as described below.

Your data lives in the named volumes, so `docker compose pull` and `docker compose up -d` never lose it.
`docker compose down -v` does delete the volumes, so never add `-v` unless you mean to wipe the Server.

### Auto-updaters and `latest`

Do not point an auto-updater (Watchtower, Diun in auto-apply mode, Renovate or Dependabot with auto-merge, or
anything that runs `docker compose pull` on a schedule) at a floating tag such as `latest`, `X` or `X.Y`. The updater
would pull a new Release and start it without you reading its notes, and if that Release is a One-way Release its
migrations run on your data, and the only way back is the restore described below, which loses what you recorded
since. If you use an auto-updater anyway, pin an exact `X.Y.Z` or digest, which it will leave alone (and exclude the
`stridehearth` container from it, for example with Watchtower's `com.centurylinklabs.watchtower.enable=false`
label), and upgrade by hand as above.

## Backups and going back

When a Stridehearth upgrade has pending database migrations, the Server first writes a `pg_dump` of its database to
`/app/backups` (the `backups` volume in the sample), named like
`stridehearth-v26-to-v28-20260304T050607Z.dump`: the schema versions it was taken between, then a UTC timestamp. It
keeps the latest three. If the dump fails, for example because the directory is not writable, the Server logs why,
applies no migration and does not start. A restart with nothing to migrate, and a first start on an empty database,
write no backup. The image bundles the PostgreSQL 18 client for this, which can dump PostgreSQL 18 and older
servers. A host directory mounted at `/app/backups` must be writable by uid 10001 (`chown 10001:10001 <dir>`).

To go back to the Release you ran before a One-way Release, restore the dump taken at that upgrade, then run the old
Release. The dump replaces the whole database, so anything recorded since the upgrade is lost. Redis is not backed up
or restored: its state (sessions and rate-limit counters) starts over, so people may have to sign in again.

`POSTGRES_USER` and `POSTGRES_DB` default to `stridehearth` in the sample compose file. Run these from the directory
holding your compose file, with the variables set in your shell (or typed in place):

```sh
# 1. List the dumps, and pick the one named for the upgrade you are undoing.
docker compose exec stridehearth ls -l /app/backups

# 2. Stop the Server and copy that dump out.
docker compose stop stridehearth
docker compose cp stridehearth:/app/backups/stridehearth-v26-to-v28-20260304T050607Z.dump ./restore.dump

# 3. Replace the database with it.
docker compose exec -T postgres dropdb -U "$POSTGRES_USER" --force "$POSTGRES_DB"
docker compose exec -T postgres createdb -U "$POSTGRES_USER" --template=template0 "$POSTGRES_DB"
docker compose exec -T postgres pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --exit-on-error < restore.dump
```

Create the database from `template0` so it is empty: the dump creates the PostGIS extension itself, and a template
that already has it would make `pg_restore --exit-on-error` stop. Then pin the Stridehearth image in your compose file
to the previous Release (the one you ran before the upgrade), and only then run `docker compose up -d`. Starting the
new image again would migrate the restored database forward once more.

If the Server container is gone (after `docker compose down`, for example), the dumps are still in the `backups`
volume, named `<project>_backups` (the project is the compose directory's name; `docker volume ls` shows it). Start
PostgreSQL with `docker compose up -d postgres`, replace steps 1 and 2 with the following, and carry on with step 3:

```sh
docker run --rm -v <project>_backups:/b:ro alpine ls -l /b
docker run --rm -v <project>_backups:/b:ro -v "$PWD":/out alpine cp /b/<dump-file-name> /out/restore.dump
```

Use your own dump's file name. The dumps are unencrypted copies of every athlete's data, kept on the same disk as the
database, so they do not protect against losing that disk. Copy them off the host (to a backup you trust) on a
schedule you choose.

## Repairing damaged FIT files (optional)

Stridehearth can try to repair a damaged FIT file on import with Garmin's Activity Repair Tool. The tool is not in the
image, because it is Garmin's separately licensed tool, so you download it yourself. To turn it on:

1. Download `ActivityRepairTool.jar` from Garmin's [fit-sdk-tools](https://github.com/garmin/fit-sdk-tools)
   repository (or the FIT SDK) on the host, for example to `./fit/ActivityRepairTool.jar`.
2. Mount it into the Stridehearth container, read-only, and point the Server at it. In `compose.yaml`, under the
   `stridehearth` service:

   ```yaml
       environment:
         FIT_REPAIR_TOOL_PATH: '/opt/fit/ActivityRepairTool.jar'
       volumes:
         - './fit/ActivityRepairTool.jar:/opt/fit/ActivityRepairTool.jar:ro'
   ```

   (Add the `FIT_REPAIR_TOOL_PATH` line to the existing `environment:` block and the mount to the existing `volumes:`
   list. The sample has both as comments.)
3. Run `docker compose up -d`.

The Server runs the tool with the container's own Java. If the path does not point to a file, the Server logs
`FIT repair tool not found` and carries on with repair turned off, so check the logs if repair does not seem to
work. A repair that fails or takes more than 30 seconds is skipped, and the file is reported as damaged.

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

## External weather (optional)

By default Stridehearth makes no outbound network call. The only temperature it knows for an Activity is the one the
device recorded. An Activity without one has no Ambient Temperature, so it gets no Heat Derating and counts as no heat
exposure. As the Operator you can opt in to look it up from the [Open-Meteo](https://open-meteo.com/) historical (ERA5)
archive. Athletes cannot turn it on for themselves.

| Variable | Default | Notes |
| --- | --- | --- |
| `STRIDEHEARTH_WEATHER_PROVIDER` | `none` | `none` sends nothing. `open-meteo` enables the lookup. |

What it sends, to `archive-api.open-meteo.com`, for each Activity without a device temperature that has a GPS start
point: its start point rounded to 0.1 degrees (about 11 km) and the UTC date it started. It never sends the route, the
athlete or anything else about the Activity. The Server's IP address is visible to Open-Meteo, as with any request.
Check Open-Meteo's terms for your use (the free API is for non-commercial use).

Lookups run in the background after an import has committed, about once a minute and up to 100 Activities at a time,
so a slow or unreachable provider never delays or fails an import. An Activity is looked up again later until it
succeeds, and only once after that. The archive trails real time by about five days, so recent Activities wait. The
result (temperature, dew point, humidity and wind) is kept with the Activity and deleted with it. Switching the setting
back to `none` stops new lookups and keeps what was cached.

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
  staged, and to `/app/backups` (below). A named volume mounted at either takes the right owner automatically. A host
  directory mounted there must be writable by uid 10001 (`chown 10001:10001 <dir>`).
- **Backups volume.** `/app/backups` holds the pre-migration `pg_dump`s; see
  [Backups and going back](#backups-and-going-back). Keep it on a volume, or the dumps go with the container.
- **Apple Silicon and other arm64 hosts.** The Stridehearth image runs natively. The sample uses
  `imresamu/postgis`, a multi-architecture PostGIS build, because `postgis/postgis` publishes only amd64. That image
  would run under emulation on arm64.
- **Health.** `/actuator/health` returns `UP` or `DOWN` with no details. It reports `DOWN` while PostgreSQL or Redis
  is unreachable. It is the only actuator endpoint exposed.
- **Building from source.** Run `docker build -t stridehearth .` at the repository root, then set `image:
  stridehearth` in the compose file.
