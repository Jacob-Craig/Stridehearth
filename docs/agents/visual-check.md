# Visual check

Any change that can alter what the SPA renders gets a Playwright **visual check** before the branch is pushed for review. Unit tests run in jsdom, which has no canvas or WebGL, so they cannot see a map, a chart or a layout regression. A human reviewer should never be the first to look at the screen.

This covers UI changes, and also refactors that claim "no visual change": the screenshots are the evidence for that claim.

A visual check never touches the developer's own stack. It runs on its own throwaway databases and ports, so nothing it writes can reach the developer's data, and the developer's app on `:8080` and compose databases on `:5432`/`:6379` keep running.

## Steps

1. **Run a throwaway stack.**
   - Start throwaway databases:
     - `docker run -d --rm --name vc-postgres -p 55432:5432 -e POSTGRES_DB=run_db -e POSTGRES_USER=run_db_user -e POSTGRES_PASSWORD=secret postgis/postgis:16-3.4`
     - `docker run -d --rm --name vc-redis -p 56379:6379 redis:7`
   - Run the branch's backend against them on `:8091`: `./gradlew bootRun --args='--spring.profiles.active=dev --server.port=8091 --spring.datasource.url=jdbc:postgresql://localhost:55432/run_db --spring.data.redis.port=56379'`. The `dev` profile supplies the local credentials; without it the backend refuses to start (ADR-0009). Pass settings in `--args`, not as environment variables: a Gradle daemon that is already running may not pass your shell's environment on to the app. Flyway migrates the empty database on start.
   - The throwaway Server's Registration Mode is closed by default (ADR-0011), so only its first account can register. To register more than one athlete, or to screenshot the open sign-up screens, add `--stridehearth.registration-mode=open` to the backend's `--args`.
   - Serve the branch's SPA with `API_PROXY_TARGET=http://localhost:8091 npx vite --port 5173` in `frontend/`.
   - Done when `:8091` and `:5173` both answer.
2. **Serve a baseline.**
   - Add a detached worktree of `main` in the scratchpad: `git worktree add --detach <scratchpad>/main-wt main`.
   - Run `npm ci` in its `frontend/`. Never symlink another checkout's `node_modules` into it: any Gradle build in that worktree runs `npm ci`, which empties the `node_modules` the link points at.
   - Serve it with `API_PROXY_TARGET=http://localhost:8091 npx vite --port 5174`. Skip this step only for a brand-new screen with no "before".
   - Done when `:5174` answers.
3. **Seed a throwaway athlete.**
   - Never log in as an existing user: their data and password are not yours.
   - Register `visualcheck<timestamp>` through `POST /api/auth/register`.
   - Import `src/test/resources/runs/sample.fit` (the synthetic sample run: a loop of Hyde Park with GPS and sensors, made by `SampleRunGenerator`) through `POST /api/activities/import`, then poll `GET /api/activities` until the activity appears.
   - If the change needs more data than one run, such as overlapping routes for the Heatmap, you may add rows to the throwaway database with SQL, but they must keep derived data consistent:
     - Every `activity` you add that has a route needs an `activity_details` row.
     - Write its `heatmap_route` row through `heatmap_route_geometries(route, sport)`, the same way the application does.
     - Never write a `heatmap_route` row on its own. Migrations rely on every one having Activity Details (#124).
   - Done when you have an activity id.
4. **Screenshot every state the change touches.**
   - Use Playwright (`npm i playwright` and `npx playwright install chromium` in the scratchpad, never in `frontend/`).
   - Shoot the same states on `:5174` and `:5173`.
   - Cover both colour schemes (`page.emulateMedia({ colorScheme })`) and every mode the change touches, such as 2D and 3D.
   - Record page errors and console errors.
   - Done when each state has a before and an after screenshot and the error list is captured.
5. **Compare against noise.**
   - Pixel-diff each before/after pair (`pixelmatch` with `pngjs`).
   - WebGL anti-aliasing varies from run to run, so also diff two runs of `main` against each other. A branch diff within that main-vs-main noise counts as unchanged.
   - Look at the diff images and at least one screenshot of each state yourself: a blank or half-loaded map also diffs as 0 px.
   - On every screenshot with a map, check that its right-hand edge isn't plain page background. A map drawn narrower than its panel means the capture went wrong (see **Full-page screenshots** below), not the app.
   - Done when every state is either within noise or the difference is an intended change you can name.
6. **Report it.**
   - In the review handoff, list the states checked, the diff numbers next to the noise numbers, any console errors, and the screenshot paths.
   - Done when a reviewer could trust the result without re-running it.
7. **Clean up.**
   - Stop the backend and both dev servers.
   - `docker stop vc-postgres vc-redis` (the `--rm` removes them and their data).
   - Run `git worktree remove` on the baseline.
   - Done when nothing you started is still running.

## Gotchas

- **CSRF.** Mutating API calls need the `X-XSRF-TOKEN` header, copied from the `XSRF-TOKEN` cookie. A `GET /api/auth/me` first sets that cookie.
- **Map tiles.** OpenFreeMap tiles load over the network, so wait for `networkidle` plus a few seconds after each camera change before taking a screenshot.
- **3D buildings start at zoom 13.** The fitted Hyde Park loop of `sample.fit` shows almost none, so press "Zoom in" once to get the building layer into the screenshot with the route still in view. Three presses take the route out of view.
- **Full-page screenshots.** When `page.screenshot({ fullPage: true })` is the first capture after a navigation, headless Chromium briefly resizes the window to 1x1. MapLibre falls back to a 400 px canvas and corrects itself about 50 ms later, after the frame has been captured (#125). Take a viewport `page.screenshot()` first and discard it, then take the full-page shot you keep. Real users never see this.
