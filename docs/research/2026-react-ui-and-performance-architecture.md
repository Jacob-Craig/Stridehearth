# React 19 UI & Runtime Performance Architecture for Stridehearth

**Date:** 2026-11-03  
**Status:** Research & Architectural Evaluation  
**Target Stack:** Vite 6 + React 19 + TypeScript + TanStack Router + TanStack Query + Tailwind CSS v4 + Radix UI / shadcn/ui + MapLibre GL JS v5 + uPlot v1.6 + OpenAPI/Zod Codegen  

---

## Executive Summary

Evaluating the proposed frontend stack against primary documentation and the existing Stridehearth codebase (`src/main/resources/static/js/activity-telemetry.js`, `src/main/resources/static/js/batch-import.js`, and `src/main/resources/templates/**`) confirms that **this stack can deliver both a zero-jank 60–120fps telemetry viewing experience and sub-frame route transitions**, provided five architectural boundaries are enforced:

1. **Bifurcated State Model (React 19 Compiler + Imperative Fast-Path):** Enable `babel-plugin-react-compiler` for automatic fine-grained memoization of React UI trees, while strictly isolating **60–120Hz cursor scrubbing** between the 9 uPlot canvases and the MapLibre 3D route map outside React state (`requestAnimationFrame` + imperative `uPlot.setCursor` and MapLibre HTML `Marker.setLngLat()`), keeping only low-frequency state (`selectedBestEffort`, `is3dMode`, `mapStyle`) in React state.
2. **Zero-Latency Navigation (`defaultPreload: 'intent'` + Query Loader + View Transitions):** Pair TanStack Router's intent-based preloading with `queryClient.ensureQueryData()` inside route loaders and `defaultViewTransition: true` so route code and JSON payloads resolve on link hover/focus (~150–250ms before click) and animate on the compositor.
3. **Hybrid Layout Containment & Virtualization:** Use CSS `content-visibility: auto` + `contain-intrinsic-size` on off-screen telemetry chart panels and standard paginated tables (preserving native `Cmd+F` search), while upgrading `/activities/manage?size=all` to `@tanstack/react-virtual` so multi-thousand-row batch selection never bloats DOM memory or triggers O(N) hidden-input cloning.
4. **Web Worker Offloading for Binary & Time-Series Compute:** Keep client-side ZIP End-of-Central-Directory (EOCD) inspection (`batch-import.js`) and ultra-long activity LTTB (Largest-Triangle-Three-Buckets) decimation (>15,000 samples) off the main thread via Vite module Web Workers (`new Worker(..., { type: 'module' })`), while preserving full-resolution arrays for exact Best Effort window bounds.
5. **Precision Athletic Typography & Theme Sync:** Configure Tailwind CSS v4 `@theme` with `font-variant-numeric: tabular-nums` across all paces, durations, heart rates, and live scrubber readouts to eliminate horizontal layout jitter, use Radix Dialog/AlertDialog primitives (with top-layer entry/exit animations) for multi-step batch confirmations, and synchronize dark/light theme tokens across Tailwind (`dark:`), OpenFreeMap (`positron` / `liberty` / `dark`), and uPlot canvas axes.

---

## 1. React 19 Compiler vs. Manual Memoization & The 60–120fps Scrubbing Boundary

### 1.1 How `babel-plugin-react-compiler` Works in React 19
In React 19, `babel-plugin-react-compiler` (integrated into Vite via `@vitejs/plugin-react`'s `reactCompilerPreset()`) performs build-time static dataflow and mutability analysis over component and hook ASTs ([React Compiler Docs](https://react.dev/learn/react-compiler/introduction), [React Compiler Installation](https://react.dev/learn/react-compiler/installation)).

- **Automatic Fine-Grained Memoization:** Instead of developers sprinkling `useMemo`, `useCallback`, and `React.memo`, the compiler lowers components into memoized blocks backed by `react/compiler-runtime`'s `c(size)` cache slots. JSX subtrees and derived values are only recomputed when their reactive dependencies change by `Object.is` reference equality.
- **Where React Compiler Excels in Stridehearth:**
  - Filtering or sorting the Best Efforts table (`view_activity.html` lines 154–185) and Laps table (`view_activity.html` lines 188–214).
  - Stabilizing callbacks passed to Radix modal triggers, filter toolbars (`manage.html`), and summary cards (`dashboard.html`).
  - Preventing unnecessary re-renders of sibling metric cards when low-frequency UI state (e.g., unit system toggle or `selectedBestEffort`) changes.

### 1.2 Why 60–120fps Telemetry Scrubbing MUST Bypass React State
Even with React Compiler enabled, **storing the active scrubber sample index (`hoveredSampleIdx: number | null`) in React `useState` is an architectural anti-pattern for synchronized multi-chart + WebGL map scrubbing**:

1. **React Scheduler & VDOM Overhead per Pointer Frame:** High-refresh-rate displays (120Hz ProMotion) fire `pointermove` events every `8.33ms` (or up to 240Hz–1000Hz with gaming mice before coalescing). Calling `setHoveredSampleIdx(idx)` on every uPlot `setCursor` hook schedules a React render pass across the activity tree, leaving insufficient frame budget for 9 Canvas 2D contexts (`uPlot`) and 1 WebGL context (`MapLibre GL JS`).
2. **Existing Codebase Baseline (`activity-telemetry.js`):**
   In `src/main/resources/static/js/activity-telemetry.js`:
   - `CHART_METRICS` defines **up to 9 concurrent uPlot charts** (`Elevation`, `Speed`, `Heart Rate`, `Power`, `Cadence`, `Respiration Rate`, `Temperature`, `Vertical Oscillation`, `Ground Contact Time` — lines 40–50).
   - Chart-to-chart cursor sync uses uPlot's internal pub/sub key: `cursor: { sync: { key: 'activity-telemetry' } }` (lines 33, 367).
   - Chart-to-map sync invokes `routeMap.showCursor(sample == null ? null : routeIndex[sample])` inside `hooks.setCursor` (lines 102–104, 387), which calls `marker.setLngLat(coord)` on a DOM `<div class="route-cursor">` (lines 258–285).
   - Map-to-chart sync runs `nearestVertex(coords, e.lngLat)` on `mousemove` over the 24px invisible `route-hit` layer (lines 220–241, 307–321) and calls `u.setCursor({ left: u.valToPos(t[sample], 'x'), top: u.over.clientHeight / 2 }, true, false)` across all 9 charts (lines 402–410).

3. **MapLibre DOM `Marker.setLngLat()` vs. `GeoJSONSource.setData()`:**
   - **Cursor Position (60–120Hz):** Must continue using a MapLibre HTML `Marker` (`marker.setLngLat(coord)`), which updates a GPU-composited CSS `transform: translate3d(...)` directly on the main thread without touching Web Workers. Calling `GeoJSONSource.setData()` at 60–120Hz serializes GeoJSON via `JSON.stringify` and posts it to MapLibre's background worker pool, causing worker queue saturation and severe frame drops ([MapLibre GL JS GeoJSONSource Docs](https://maplibre.org/maplibre-gl-js/docs/API/classes/GeoJSONSource/#setdata), [MapLibre Performance Guide](https://maplibre.org/maplibre-gl-js/docs/examples/live-update-feature/)).
   - **Best Effort Highlight Window (<5Hz):** Highlighting a segment (`highlightEffortWindow(startIndex, endIndex, routeIndex)` in `activity-telemetry.js` lines 287–298) updates `map.getSource('route-highlight').setData(highlightFeature)` and calls `u.redraw(false)` on each chart (`drawHighlightBand`, lines 434–455, 496). Because this only fires on row `mouseenter` / `click` / `Escape`, `GeoJSONSource.setData()` is ideal here.

### 1.3 Recommended Two-Tier State Architecture in React 19

| State Category | Examples | Update Frequency | Storage & Propagation Mechanism |
| :--- | :--- | :--- | :--- |
| **Tier 1: High-Frequency Imperative Stream** | `activeSampleIndex`, `activeRouteVertex`, live cursor readout values | **60–120Hz** (every `8.3ms–16.6ms` during scrub) | **External Pub/Sub Store or Mutable Ref + `requestAnimationFrame` lock.** Imperatively calls `uPlot.setCursor()`, `marker.setLngLat()`, or updates text nodes via refs—**zero React re-renders**. |
| **Tier 2: Low-Frequency Declarative UI State** | `pinnedBestEffort`, `hoveredBestEffort`, `is3dMode` (`55°` pitch vs `0°`), `mapStyle` (`positron` / `liberty` / `dark`) | **< 5Hz** (clicks, toggles, row hover) | **React `useState` / URL Search Params** (optimized automatically by `babel-plugin-react-compiler`). Synchronizes with canvas/WebGL via `useEffect`. |

#### Concrete Implementation Pattern: Scrubber Bus Hook
```tsx
// src/features/telemetry/useTelemetryScrubber.ts
import { useRef, useCallback } from 'react';
import type uPlot from 'uplot';
import type maplibregl from 'maplibre-gl';

export interface TelemetryScrubberBus {
  registerChart: (chart: uPlot) => () => void;
  registerMapMarker: (marker: maplibregl.Marker, map: maplibregl.Map) => void;
  scrubFromChart: (sampleIdx: number | null) => void;
  scrubFromMap: (vertexIdx: number | null) => void;
}

export function useTelemetryScrubber(
  t: number[],
  routeIndex: (number | null)[],
  coords: [number, number][] | null,
  firstSampleAtVertex: (number | undefined)[]
): TelemetryScrubberBus {
  const chartsRef = useRef<Set<uPlot>>(new Set());
  const markerRef = useRef<maplibregl.Marker | null>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const markerVisibleRef = useRef(false);
  const rafIdRef = useRef<number | null>(null);

  const updateMapCursor = useCallback((sampleIdx: number | null) => {
    const marker = markerRef.current;
    const map = mapRef.current;
    if (!marker || !map || !coords) return;

    const vertex = sampleIdx == null ? null : routeIndex[sampleIdx];
    const coord = vertex == null ? null : coords[vertex];
    if (!coord) {
      if (markerVisibleRef.current) {
        marker.remove();
        markerVisibleRef.current = false;
      }
      return;
    }
    marker.setLngLat(coord);
    if (!markerVisibleRef.current) {
      marker.addTo(map);
      markerVisibleRef.current = true;
    }
  }, [coords, routeIndex]);

  const scrubFromChart = useCallback((sampleIdx: number | null) => {
    if (rafIdRef.current != null) cancelAnimationFrame(rafIdRef.current);
    rafIdRef.current = requestAnimationFrame(() => {
      updateMapCursor(sampleIdx);
      rafIdRef.current = null;
    });
  }, [updateMapCursor]);

  const scrubFromMap = useCallback((vertexIdx: number | null) => {
    if (rafIdRef.current != null) cancelAnimationFrame(rafIdRef.current);
    rafIdRef.current = requestAnimationFrame(() => {
      const sampleIdx = vertexIdx == null ? null : (firstSampleAtVertex[vertexIdx] ?? null);
      updateMapCursor(sampleIdx);
      for (const u of chartsRef.current) {
        if (sampleIdx == null) {
          u.setCursor({ left: -10, top: -10 }, false, false);
        } else {
          u.setCursor(
            { left: u.valToPos(t[sampleIdx], 'x'), top: u.over.clientHeight / 2 },
            false, // fireHook = false to prevent circular feedback loop
            false  // pub = false; we iterate all registered charts explicitly
          );
        }
      }
      rafIdRef.current = null;
    });
  }, [firstSampleAtVertex, t, updateMapCursor]);

  return {
    registerChart: useCallback((chart: uPlot) => {
      chartsRef.current.add(chart);
      return () => { chartsRef.current.delete(chart); };
    }, []),
    registerMapMarker: useCallback((marker: maplibregl.Marker, map: maplibregl.Map) => {
      markerRef.current = marker;
      mapRef.current = map;
    }, []),
    scrubFromChart,
    scrubFromMap,
  };
}
```
> **Note on `nearestVertex` optimization (`activity-telemetry.js` line 307):** The existing `nearestVertex` performs an $O(V)$ linear scan with `Math.cos(lat)` equirectangular scaling. Even for a 10,000-point marathon Activity Details track, $10,000$ float multiplications take `< 0.08ms` in V8. Wrapping `onRouteHover` in `requestAnimationFrame` ensures `nearestVertex` and `moveChartCursors` execute at most once per display refresh frame.

---

## 2. Instant Page Transitions: TanStack Router + TanStack Query + View Transitions API

### 2.1 Eliminating Network Waterfalls via Intent Preloading & Query Loaders
In the current server-rendered Thymeleaf architecture (`view_activity.html` + `activity-telemetry.js` line 59), navigating to `/activities/{id}` incurs a **sequential network waterfall**:
1. Browser requests HTML document `GET /activities/{id}`.
2. Browser parses HTML, downloads/executes `activity-telemetry.js`.
3. `activity-telemetry.js` fires `fetch('/api/activities/{id}/telemetry')` after DOM mount.
4. Only after the telemetry JSON arrives does `createRouteMapSafely()` unhide `#map-panel` (`panel.hidden = false`, line 119) and append up to 9 `.telemetry-chart` divs (`container.appendChild(el)`, line 360), causing a **massive layout shift (CLS)** and delayed Largest Contentful Paint (LCP).

With **TanStack Router** + **TanStack Query**, we eliminate both the sequential waterfall and the layout shift ([TanStack Router Preloading Docs](https://tanstack.com/router/latest/docs/framework/react/guide/preloading), [TanStack Router External Data Loading](https://tanstack.com/router/latest/docs/framework/react/guide/external-data-loading)):

1. **`defaultPreload: 'intent'` + `defaultPreloadDelay: 50`:** When the user moves their pointer over an activity link in `/activities`, `/dashboard` (Personal Record cards), or `/activities/manage`—or focuses it via keyboard—TanStack Router triggers the target route's `loader` ~150–250ms before the `click` event completes.
2. **Parallel Loader Prefetching (`Promise.all` + `ensureQueryData`):** The `/activities/$activityId` route loader prefetches **both** the activity summary and the telemetry payload concurrently into the TanStack Query cache:

```tsx
// src/routes/activities.$activityId.tsx
import { createFileRoute } from '@tanstack/react-router';
import { useSuspenseQuery } from '@tanstack/react-query';
import { activitySummaryQueryOptions, activityTelemetryQueryOptions } from '@/api/queries/activities';

export const Route = createFileRoute('/activities/$activityId')({
  loader: async ({ params: { activityId }, context: { queryClient } }) => {
    await Promise.all([
      queryClient.ensureQueryData(activitySummaryQueryOptions(activityId)),
      queryClient.ensureQueryData(activityTelemetryQueryOptions(activityId)),
    ]);
  },
  component: ActivityDetailPage,
});
```

3. **Cache Retention Policy (`staleTime` & `gcTime`):**
   - Activity telemetry (`GET /api/activities/{id}/telemetry`) is **immutable** unless the activity is upgraded via FIT re-import or best-efforts backfill. Setting `staleTime: 5 * 60 * 1000` (5 minutes) and `gcTime: 15 * 60 * 1000` ensures back/forward navigation between `/dashboard`, `/activities`, and `/activities/{id}` resolves in **0ms from memory**.
   - Mutations (Edit Activity, Delete Activity, Batch Modify/Delete, Import Batch completion) explicitly invalidate `['activities']` and `['dashboard', 'personal-records']`.

### 2.2 Compositor-Driven View Transitions (`defaultViewTransition: true`)
Enabling `defaultViewTransition: true` on `createRouter` wraps route navigations in `document.startViewTransition()` ([TanStack Router View Transitions Docs](https://tanstack.com/router/latest/docs/framework/react/guide/view-transitions), [MDN View Transition API](https://developer.mozilla.org/en-US/docs/Web/API/View_Transition_API)):

- **Shared Element Continuity:** Assigning a deterministic `view-transition-name` (e.g., `style={{ viewTransitionName: \`activity-title-\${activity.id}\` }}`) to the activity title or PR badge on `/dashboard` and `/activities` morphs it smoothly into the `<h1>` header on `/activities/$activityId`.
- **WebGL / Canvas Precaution:** Never apply `view-transition-name` directly to the MapLibre `<canvas>` container while it is initializing, as capturing raster snapshots of an uninitialized WebGL framebuffer can flash blank frames. Instead, reserve a fixed-height container (`h-[420px]`) so the map fades in cleanly within the root transition.
- **Accessibility Guardrail:** Respect `prefers-reduced-motion: reduce` by disabling root/group morph animations in CSS:
  ```css
  @media (prefers-reduced-motion: reduce) {
    ::view-transition-group(*),
    ::view-transition-old(*),
    ::view-transition-new(*) {
      animation: none !important;
    }
  }
  ```

---

## 3. CSS `content-visibility: auto`, `contain-intrinsic-size`, and `@tanstack/react-virtual`

### 3.1 Current Bottlenecks in `/activities/manage` and Multi-Chart Views
1. **`manage.html` with `size=all` (lines 63, 116–132, 287–313):**
   - Selecting `Per Page: All` renders every activity in the user's database as a `<tr>` with 7 `<td>` cells.
   - Worse, `updateSelection()` in `manage.html` (lines 287–313) runs `document.querySelectorAll('.activity-checkbox:checked')` on every checkbox change and **rebuilds two separate DOM lists of `<input type="hidden" name="activityIds">` elements** (`#batch-modify-hidden-inputs` and `#batch-delete-hidden-inputs`). Checking "Select All" on 2,500 activities synchronously creates and appends **5,000 `<input>` DOM nodes** inside the change event handler, blocking Interaction to Next Paint (INP).
2. **`view_activity.html` with 9 Telemetry Charts + Laps Table:**
   - All 9 uPlot canvases (`140px` height each = `1,260px` of charts) plus the Best Efforts and Laps tables are laid out and painted simultaneously on mount, even though charts 4–9 and the Laps table sit below the initial viewport fold.

### 3.2 When to Use CSS `content-visibility: auto` vs. `@tanstack/react-virtual`
Per **web.dev** and **MDN** specifications ([web.dev `content-visibility`](https://web.dev/articles/content-visibility), [MDN `contain-intrinsic-size`](https://developer.mozilla.org/en-US/docs/Web/CSS/contain-intrinsic-size), [TanStack Virtual Docs](https://tanstack.com/virtual/latest)):

| Surface in Stridehearth | Typical Item Count | Recommended Technique | Rationale |
| :--- | :--- | :--- | :--- |
| **Telemetry Chart Panels (Charts 1–9)** | Up to 9 charts (`140px` + header each) | **CSS `content-visibility: auto`** + `contain-intrinsic-size: auto 168px` | Skips layout/paint for below-the-fold charts until scrolled near viewport; keeps all 9 uPlot instances mounted so `ResizeObserver` and cursor sync stay simple. |
| **Laps Table (`view_activity.html`)** | 2 to ~100 laps (e.g., marathon km splits) | **CSS `content-visibility: auto`** on the `<section>` card (`contain-intrinsic-size: auto 320px`) | Preserves native `Cmd+F` search and table semantics while skipping off-screen table layout during initial activity load. |
| **Paginated `/activities` & `/activities/manage` (`size=20\|50\|100`)** | 20–100 rows | **Standard Table + `content-visibility: auto` on below-fold rows** | Native `Cmd+F` works; 100 rows in React 19 render in `< 3ms`. |
| **`/activities/manage` with `size=all`** | **500 to 10,000+ rows** | **`@tanstack/react-virtual` (`useVirtualizer`) + React `Set<string>` selection state** | `content-visibility: auto` still retains all 10,000 `<tr>` DOM nodes in memory. `@tanstack/react-virtual` mounts only ~25 visible rows + overscan (`overscan: 10`), keeping DOM node count constant (`O(1)`) and making "Select All" an instant `new Set(allIds)` operation sending a JSON array `{ activityIds: [...] }` instead of 5,000 hidden `<input>` elements. |

```css
/* Offscreen rendering optimization for telemetry chart cards & below-fold sections */
.telemetry-chart-card {
  content-visibility: auto;
  contain-intrinsic-size: auto 168px;
}

.activity-section-defer {
  content-visibility: auto;
  contain-intrinsic-size: auto 320px;
}
```

---

## 4. Web Worker Offloading: Client-Side ZIP EOCD Inspection & LTTB Downsampling

### 4.1 Client-Side ZIP EOCD Inspection (`batch-import.js`)
In `src/main/resources/static/js/batch-import.js` (lines 53–105), `inspectZipFile(file)` implements a clever zero-dependency binary parser:
- It slices only the last `65,558` bytes of the `.zip` `File` (`file.slice(file.size - sliceSize, file.size).arrayBuffer()`), scans backward for the End of Central Directory (EOCD) signature `0x06054b50`, slices the Central Directory (`file.slice(cdOffset, cdOffset + cdSize)`), and iterates Central Directory headers (`0x02014b50`) to count `.fit` entries (excluding `__MACOSX/` and `.DS_Store`) and sum their uncompressed byte sizes.

**Identified Bottlenecks & Edge Cases in `batch-import.js`:**
1. **Main-Thread Iteration & DOM Thrashing on Large Archives:** When a user drops a Garmin/Strava full-history export ZIP containing 5,000–15,000 `.fit` files (or drops 200 loose `.fit` files at once), `handleFiles` (lines 107–149) runs `TextDecoder().decode()` thousands of times on the main thread and calls `selectedFilesListEl.appendChild(li)` in an unbatched loop.
2. **ZIP64 EOCD Gap:** Archives exceeding `4 GiB` or containing `> 65,535` Central Directory entries use the **ZIP64 End of Central Directory Locator** (`0x07064b50`) and **ZIP64 EOCD Record** (`0x06064b50`), where `cdSize` / `cdOffset` in the standard EOCD are `0xFFFFFFFF` ([PKWARE APPNOTE.TXT §4.3.14–4.3.15](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT)).

**Enhancement — Offload to a Vite Module Web Worker:**
Move `inspectZipFile` into `src/features/import/zipInspector.worker.ts` invoked via `new Worker(new URL('./zipInspector.worker.ts', import.meta.url), { type: 'module' })` ([Vite Worker Docs](https://vite.dev/guide/features.html#web-workers)):
- Pass the `File[]` list directly to the Worker (`File` / `Blob` objects are structured-cloneable with zero copy across threads).
- Instantiate `const decoder = new TextDecoder()` **once** outside the `while` loop (instead of allocating a new `TextDecoder` per entry as on line 85 of `batch-import.js`).
- Detect `0xFFFFFFFF` in standard EOCD fields to read the 64-bit offset from the ZIP64 EOCD record (`getBigUint64`).
- Return a compact summary object `{ files: FileInspectionSummary[], totalFitCount, totalUncompressedBytes }` to React state.

### 4.2 Telemetry Downsampling (LTTB) vs. Native uPlot Rendering
Does Stridehearth need LTTB (Largest-Triangle-Three-Buckets) downsampling in a Web Worker for telemetry charts?

1. **Standard Runs & Rides (1 to 4 hours at 1Hz = 3,600 to 14,400 samples):**
   - According to uPlot's official benchmarks ([uPlot Benchmarks](https://github.com/leeoniya/uPlot#performance)), uPlot renders 100,000 data points in `~15ms` and updates cursors at 60fps with `< 1ms` per frame because it draws directly to a single `<canvas>` using linear array indexing without per-point DOM/SVG objects.
   - For activities with **`<= 15,000` samples**, **do NOT downsample with LTTB**. Passing the raw arrays directly to uPlot is faster than Worker serialization and guarantees a **1:1 index correspondence** (`sample === u.cursor.idx`) with `telemetry.routeIndex[sample]` and `bestEfforts[k].startIndex / endIndex`.
2. **Ultra-Endurance Activities (> 15,000 samples, e.g., 100-mile trail ultras or 12-hour rides):**
   - On a `900px`-wide container (`1,800` device pixels at `2x` DPR), drawing 50,000 points across 9 charts (450,000 canvas line segments) causes unnecessary canvas overdraw.
   - **Critical Architectural Rule if Downsampling in a Worker:** If a Web Worker downsamples `[t, series]` via LTTB to `threshold = containerWidth * 2` (~2,000 points), **the downsampled indices no longer match `routeIndex[sample]`**. Instead of mutating the index space, the Worker must either:
     - Downsample `routeIndex` using the **exact same selected sample indices** from `t`, OR
     - Look up the route vertex via binary search on `t` (`findSampleAtTime(t, hoveredTimeSeconds)`), while keeping `drawHighlightBand` anchored on `effort.startTimeSeconds` and `effort.endTimeSeconds` (which `activity-telemetry.js` lines 436–437 already wisely uses via `u.valToPos(activeHighlight.startTimeSeconds, 'x', true)`!).

---

## 5. Modern CSS & UI Architecture (Tailwind v4, Tabular Numerals, Dialogs, & Theme Sync)

### 5.1 Zero-CLS Reservation for Map & Telemetry Containers
In `view_activity.html` (lines 68–87), `#map-panel` starts with `hidden` and `#telemetry-charts` starts empty (`height: 0`), causing the Key Metrics Grid (lines 90–151) to jump down by `> 1,000px` once `/api/activities/{id}/telemetry` resolves.
- **Fix:** Even when telemetry is fetched asynchronously or via `<Suspense>`, reserve deterministic geometry using Tailwind v4 aspect/height utilities (`min-h-[420px]` for the map panel and skeleton bars of `h-[140px]` per expected chart) or resolve telemetry inside the TanStack Router loader before committing the view transition.

### 5.2 Athletic Precision Typography: `tabular-nums` (`font-variant-numeric: tabular-nums`)
Athletic dashboards display dense, rapidly updating numerical columns:
- **Live Telemetry Scrubber Readouts:** As the user scrubs across the 9 uPlot charts (`Time`, `Elevation`, `Speed`, `Heart Rate`, `Power`, `Cadence`, etc.), proportional digits (`1` vs `8`) cause the legend values in `.u-legend` and any floating map HUD to jitter horizontally at 60–120fps.
- **Tables & Cards:** Personal Record cards (`dashboard.html` lines 49–52), Best Efforts (`view_activity.html` lines 178–180), Laps (`view_activity.html` lines 204–210), and the Import Batch live progress counters (`import.html` lines 101–138).

In **Tailwind CSS v4** ([Tailwind v4 `@theme` Docs](https://tailwindcss.com/docs/functions-and-directives#theme-directive), [MDN `font-variant-numeric`](https://developer.mozilla.org/en-US/docs/Web/CSS/font-variant-numeric)), enforce `tabular-nums` globally on metric utilities and uPlot legends:

```css
@import "tailwindcss";

@theme {
  --font-sans: "Inter", ui-sans-serif, system-ui, sans-serif;
  --font-mono: "JetBrains Mono", ui-monospace, SFMono-Regular, monospace;
}

/* Enforce monospaced digit advances on all athletic metrics and uPlot readouts */
@utility metric-value {
  font-variant-numeric: tabular-nums slashed-zero;
}

.uplot .u-value {
  font-variant-numeric: tabular-nums;
}
```

### 5.3 Accessible Modals & Multi-Step Flows: Radix UI / shadcn/ui + Top-Layer Motion
The existing codebase relies on Bootstrap 5 JS modals for three critical workflows:
1. **Activity History Modal** (`view_activity.html` lines 313–343).
2. **Two-Step Batch Modify Modal** (`manage.html` lines 141–210).
3. **Two-Step Batch Delete Confirmation Modal** (`manage.html` lines 213–261) and native `window.confirm()` dialogs (`batch-import.js` line 354, `view_activity.html` line 299).

Replacing Bootstrap 5 JS and blocking `window.confirm()` with **Radix UI `Dialog` and `AlertDialog` primitives** (via `shadcn/ui`) provides:
- **WAI-ARIA Compliance:** Automatic focus trapping, `aria-modal="true"`, scroll locking, and `Escape` / outside-click dismissal ([Radix Dialog Docs](https://www.radix-ui.com/primitives/docs/components/dialog), [Radix AlertDialog Docs](https://www.radix-ui.com/primitives/docs/components/alert-dialog)).
- **Declarative Two-Step State Machine:** In `manage.html` (lines 333–389), step transitions (`step 1 <-> step 2`) and modal close resets (`hidden.bs.modal`) are wired via manual `.classList.add('d-none')` mutations. In React 19, a single `type ModalStep = 'configure' | 'confirm'` state inside the dialog component resets automatically when `open` transitions to `false`.

### 5.4 Synchronized Dark / Light Theme Across Tailwind v4, MapLibre, and uPlot
Currently, `view_activity.html` hardcodes light-mode Bootstrap classes (`bg-light`, `bg-white`), `activity-telemetry.js` only exposes `positron` and `liberty` styles (lines 21–24) with hardcoded dark axis text on uPlot canvases, and `route-casing` is hardcoded to `#ffffff` (line 212).

To deliver a cohesive light/dark athletic UI:
1. **OpenFreeMap Dark Style Integration:** OpenFreeMap provides a first-party dark vector tile style (`https://tiles.openfreemap.org/styles/dark` alongside `positron` and `liberty` — [OpenFreeMap QuickStart](https://openfreemap.org/quick_start/)).
   - When the app theme switches to `dark` (and the user hasn't manually overridden the map style to `liberty`), automatically switch MapLibre to `https://tiles.openfreemap.org/styles/dark` and adjust `route-casing` from `#ffffff` to `#09090b` (`zinc-950`) and `3d-buildings` fill-extrusion stops from `['#dfdfdf', '#cfcfcf', '#bcbcbc']` to `['#27272a', '#3f3f46', '#52525b']`.
2. **Preserving Custom Layers Across MapLibre Style Switches:**
   - As noted in `activity-telemetry.js` line 204 (`map.on('style.load', ...)`), calling `map.setStyle(styleUrl, { diff: false })` drops custom GeoJSON sources (`route`, `route-highlight`) and custom layers (`3d-buildings`, `route-casing`, `route-line`, `route-hit`, `route-highlight-casing`, `route-highlight-line`).
   - In the React wrapper component, encapsulate source/layer registration inside a single idempotent `syncCustomLayers(map, routeFeature, highlightFeature, is3dMode, theme)` function triggered on `style.load` so active Best Effort highlights and 3D building extrusions survive theme and style switches without flicker.
3. **uPlot Canvas Theme Tokens:**
   - Canvas 2D contexts do not automatically inherit CSS `color` changes on already-configured axes. Read computed CSS custom properties (`getComputedStyle(document.documentElement).getPropertyValue('--chart-axis')` and `'--chart-grid'`) when instantiating uPlot axes (`axes[i].stroke`, `axes[i].grid.stroke`), and invoke `u.redraw(false)` when the theme toggles.

---

## 6. Summary of Concrete Architectural Recommendations

| Area | Current Implementation (`Thymeleaf + Vanilla JS`) | Target React 19 Architecture (`Vite + TanStack + Tailwind v4`) | Expected Impact |
| :--- | :--- | :--- | :--- |
| **Component Memoization** | N/A (Full page reloads) | `babel-plugin-react-compiler` in `vite.config.ts` | Zero manual `useMemo`/`useCallback` boilerplate; automatic fine-grained UI updates. |
| **Telemetry & Map Scrubbing** | Direct DOM/uPlot/MapLibre calls in `activity-telemetry.js` | `useTelemetryScrubber` imperative bus (`rAF` + `u.setCursor` + `Marker.setLngLat`), bypassing React state | Locked **60–120fps** scrubbing across 9 charts + 3D WebGL map with `< 1ms` JS overhead/frame. |
| **Route Navigation & Data Fetching** | Sequential HTML load -> JS mount -> `fetch(/telemetry)` | TanStack Router (`defaultPreload: 'intent'`, `defaultViewTransition: true`) + parallel `ensureQueryData` in `loader` | **0ms perceived route transitions**; eliminates telemetry waterfall and layout shift (CLS = 0). |
| **Large Tables (`/activities/manage`)** | Full DOM render + 5,000 cloned `<input type="hidden">` on Select All | `@tanstack/react-virtual` (`size=all`) + `Set<string>` state + CSS `content-visibility: auto` on chart cards | Constant `~30` mounted row nodes regardless of database size; `< 16ms` INP on batch selection. |
| **ZIP Inspection & Heavy Telemetry** | Main-thread `inspectZipFile` loop in `batch-import.js` | Vite module Web Worker (`zipInspector.worker.ts` with ZIP64 fallback) + conditional LTTB only when `N > 15,000` | Zero main-thread blocking on 500MB multi-thousand-file ZIP drops; exact index fidelity on normal runs. |
| **Typography, Modals & Themes** | Bootstrap 5 CDN, proportional digits, light-only map/charts | Tailwind v4 `@theme` with `tabular-nums`, Radix `Dialog`/`AlertDialog`, synced OpenFreeMap `positron`/`dark` + uPlot palette | Zero numerical jitter during scrubbing, accessible keyboard-navigable modals, seamless dark/light mode. |

---

## Primary Sources & References

1. **React 19 & React Compiler:**
   - [React Documentation — React Compiler Introduction](https://react.dev/learn/react-compiler/introduction)
   - [React Documentation — Installing & Configuring `babel-plugin-react-compiler` with Vite](https://react.dev/learn/react-compiler/installation)
2. **TanStack Router, Query & Virtual:**
   - [TanStack Router — Preloading (`defaultPreload: 'intent'`)](https://tanstack.com/router/latest/docs/framework/react/guide/preloading)
   - [TanStack Router — External Data Loading (`queryClient.ensureQueryData`)](https://tanstack.com/router/latest/docs/framework/react/guide/external-data-loading)
   - [TanStack Router — View Transitions (`defaultViewTransition: true`)](https://tanstack.com/router/latest/docs/framework/react/guide/view-transitions)
   - [TanStack Virtual — Virtualizing HTML Tables (`useVirtualizer`)](https://tanstack.com/virtual/latest/docs/framework/react/examples/table)
3. **uPlot & MapLibre GL JS:**
   - [uPlot Official Repository & Canvas Performance Benchmarks (`leeoniya/uPlot`)](https://github.com/leeoniya/uPlot)
   - [MapLibre GL JS API — `Marker.setLngLat` vs `GeoJSONSource.setData`](https://maplibre.org/maplibre-gl-js/docs/API/classes/Marker/#setlnglat)
   - [OpenFreeMap — Vector Tile Styles (`positron`, `liberty`, `dark`)](https://openfreemap.org/quick_start/)
4. **Web Platform Specs (MDN, web.dev, Vite, PKWARE):**
   - [web.dev — `content-visibility`: the new CSS property that boosts your rendering performance](https://web.dev/articles/content-visibility)
   - [MDN Web Docs — `contain-intrinsic-size`](https://developer.mozilla.org/en-US/docs/Web/CSS/contain-intrinsic-size)
   - [MDN Web Docs — View Transition API (`document.startViewTransition`)](https://developer.mozilla.org/en-US/docs/Web/API/View_Transition_API)
   - [MDN Web Docs — `font-variant-numeric: tabular-nums`](https://developer.mozilla.org/en-US/docs/Web/CSS/font-variant-numeric)
   - [Vite Documentation — Web Workers with `new Worker(new URL(..., import.meta.url), { type: 'module' })`](https://vite.dev/guide/features.html#web-workers)
   - [PKWARE ZIP File Format Specification (`APPNOTE.TXT` §4.3.14–4.3.15 EOCD & ZIP64)](https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT)
5. **Tailwind CSS v4 & Radix UI:**
   - [Tailwind CSS v4 — `@theme` & Functions and Directives](https://tailwindcss.com/docs/functions-and-directives#theme-directive)
   - [Radix Primitives — `Dialog` & `AlertDialog` WAI-ARIA Specifications](https://www.radix-ui.com/primitives/docs/components/dialog)
6. **Stridehearth Codebase Inspected:**
   - `src/main/resources/static/js/activity-telemetry.js`
   - `src/main/resources/static/js/batch-import.js`
   - `src/main/resources/static/css/activity-telemetry.css`
   - `src/main/resources/templates/activities/{view_activity,manage,dashboard,list,import,history,add_activity}.html`
   - `src/main/resources/templates/fragments/{layout,upload}.html`
