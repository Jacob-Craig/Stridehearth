# Stridehearth's release image (ADR-0009). CI publishes it to ghcr.io/jacob-craig/stridehearth for linux/amd64 and
# linux/arm64; anyone can also build it from source with `docker build .`.
#
# It holds no configuration: a Server supplies SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME,
# SPRING_DATASOURCE_PASSWORD and SPRING_DATA_REDIS_HOST through its environment, or the app refuses to start.
#
# Base images are pinned by tag and digest; Dependabot (.github/dependabot.yml) updates both.

# ---- Node, borrowed by the build stage to bundle the SPA (major version as in frontend/.nvmrc) ----
FROM node:22-bookworm-slim@sha256:e9f565d32859f0cbfb52125eafe8be39390f24dac302dc3cdf566e8ba3ab21ee AS node

# ---- Build: the Spring Boot jar with the SPA inside, split into its layers ----
FROM eclipse-temurin:17-jdk-noble@sha256:1bedb04434bd275ef072355e9caec2a22be5240fbe82ad51a59323f2bbcab4a6 AS build
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm
WORKDIR /workspace
COPY . .
# CI has already run the checks that bootJar depends on (frontendCheck), so only the production bundle is built here.
# The copy fails, naming what it found, unless build/libs holds exactly one jar besides the plain one.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon bootJar -x frontendCheck \
    && set -- $(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar') \
    && if [ "$#" -ne 1 ]; then echo "Expected one Boot jar in build/libs, found $#: $*" >&2; exit 1; fi \
    && cp "$1" application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ---- Runtime ----
FROM eclipse-temurin:17-jre-noble@sha256:042c05a77eb915684fc8c92cfbbef4fd6f8c24ca7d934d1a7db3543453aaa077
# CI's labels override version (and add revision and created); a local build is "dev", not the base image's 24.04.
LABEL org.opencontainers.image.title="Stridehearth" \
      org.opencontainers.image.version="dev" \
      org.opencontainers.image.description="Self-hosted running activity tracker" \
      org.opencontainers.image.source="https://github.com/Jacob-Craig/Stridehearth" \
      org.opencontainers.image.licenses="Apache-2.0 AND LicenseRef-Garmin-FIT-Protocol-License"

RUN groupadd --system --gid 10001 stridehearth \
    && useradd --system --uid 10001 --gid stridehearth --home-dir /app --no-create-home --shell /usr/sbin/nologin stridehearth
WORKDIR /app

# Apache-2.0 covers Stridehearth's own code. NOTICE explains that the bundled, unmodified Garmin FIT SDK
# (lib/fit-*.jar) is under Garmin's FIT Protocol License Agreement instead.
COPY LICENSE NOTICE /app/

COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

# The app's files stay root-owned and read-only to it. Import uploads are staged in /app/temp-batches
# (stridehearth.storage.temp-dir), the only directory it writes to; a named volume mounted there inherits its owner.
RUN mkdir /app/temp-batches && chown stridehearth:stridehearth /app/temp-batches
USER stridehearth:stridehearth

EXPOSE 8080
# DOWN (and so unhealthy) while PostgreSQL or Redis is unreachable. Flyway migrations run before the app starts
# answering, so the start period is generous.
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
    CMD curl --fail --silent --output /dev/null "http://localhost:${SERVER_PORT:-8080}/actuator/health" || exit 1
ENTRYPOINT ["java", "-jar", "application.jar"]
