# 0009. Trunk-Based Releases Pulled from a Container Registry

Status: accepted.

Stridehearth is a product that Operators self-host, the author's own Server being the first. Releases are SemVer tags on `main`, never a long-lived `prod` branch. Each tag publishes a multi-architecture (`linux/amd64`, `linux/arm64`) image to GitHub Container Registry (GHCR), and every merge to `main` publishes an Edge Build. Delivery ends at the registry: Servers pull a pinned Release themselves, and GitHub is never given a route into anyone's network. The repository is public because, on GitHub Free, that is what makes this affordable and enforceable. It gives unlimited Actions minutes, free arm64 runners, free public GHCR storage, and server-side branch protection.

## Considered Options

- **Separate `main` (dev) and `prod` branches**: Rejected. For one maintainer it adds a merge ritual that tags already cover. The branches drift, hotfixes have to land twice, and a branch says nothing about which version a Server runs. A short-lived `release/X.Y.x` branch is cut only when an old Release needs a fix.
- **Push-based CD into the Server (SSH deploy, or a self-hosted Actions runner)**: Rejected. It needs either an inbound route to a home network or a runner on home hardware. A self-hosted runner on a public repository runs code from fork pull requests, which is a known route in.
- **Private repository with private GHCR packages**: Rejected. The 500 MB package quota holds one or two images, private repos get 2,000 Actions minutes a month, there are no free arm64 runners, and branch protection is unavailable.
- **`bootBuildImage` (Cloud Native Buildpacks) or Jib instead of a Dockerfile**: Rejected. A multi-stage Dockerfile (Temurin 17 JRE, layered jar, non-root user, healthcheck) is explicit, and self-hosters expect to be able to read it.

## Consequences

- Image tags: a Release `vX.Y.Z` publishes `X.Y.Z`, `X.Y`, `X` and `latest`, and `main` publishes `edge`. The sample compose file pins an exact version, because a floating tag combined with an auto-updater can apply a One-way Release without anyone choosing to (see ADR-0010).
- Versioning starts at `0.1.0`. While on 0.x, upgrades are best effort. From 1.0, **major** means the Operator must change something (compose or config), **minor** brings features and may be a One-way Release, and **patch** is a fix with no migration.
- Releases are cut by pushing a tag by hand. Notes are GitHub-generated, plus a manual "One-way Release" section whenever migrations are included.
- The image contains no configuration secrets. Without an explicit dev profile there are no default credentials or `localhost` addresses, so a Server must supply its database and Redis settings through its environment or fail to start. Swagger UI and SQL logging are off outside dev.
- There is no hosted staging Server. Edge Builds are the pre-release channel.
- The sample compose file uses a multi-architecture PostGIS image, because `postgis/postgis` publishes only amd64.
