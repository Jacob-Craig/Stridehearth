import { describe, expect, it, vi } from 'vitest';
import type { ActivityTelemetryStreamsDto } from '../src/api/generated/types.gen';
import { zActivityTelemetryStreamsDto } from '../src/api/generated/zod.gen';
import { createDownsamplerClient } from '../src/features/telemetry/downsamplerClient';
import { handleDownsampleRequest } from '../src/features/telemetry/downsamplerProtocol';
import { LTTB_SAMPLE_THRESHOLD, lttb, needsDownsampling } from '../src/features/telemetry/lttb';
import {
  type LngLat,
  effortRouteCoordinates,
  parseRoute,
  routeCoordinateAtTime,
  sampleIndexAtTime,
} from '../src/features/telemetry/sampleLookup';
import { createTelemetryScrubber } from '../src/features/telemetry/scrubberBus';
import { readingAt, sensorDropouts } from '../src/features/telemetry/sensorDropouts';
import { selectChartSeries } from '../src/features/telemetry/sensorStreams';

/** Activity Details streams as the server sends them: every declared stream, `null` unless given. */
function streams(recorded: Partial<ActivityTelemetryStreamsDto>): ActivityTelemetryStreamsDto {
  const unrecorded = Object.fromEntries(
    Object.keys(zActivityTelemetryStreamsDto.shape).map((stream) => [stream, null]),
  );
  return { ...unrecorded, t: [0, 1, 2], ...recorded } as ActivityTelemetryStreamsDto;
}

describe('selectChartSeries', () => {
  it('charts only sensor streams that recorded at least one value, in the fixed chart order', () => {
    const series = selectChartSeries(
      streams({
        cadence: [80, 81, 82],
        heartRate: [140, null, 150],
        power: [null, null, null],
        temperature: [],
      }),
    );

    expect(series.map((s) => s.label)).toEqual(['Heart Rate', 'Cadence']);
    expect(series[0]).toMatchObject({ unit: 'bpm', values: [140, null, 150] });
  });

  it('prefers enhanced streams over their legacy equivalents', () => {
    const series = selectChartSeries(
      streams({
        altitude: [1, 2, 3],
        enhancedAltitude: [10.5, 11, 12],
        respirationRate: [null, null, null],
        enhancedRespirationRate: null,
      }),
    );

    expect(series).toHaveLength(1);
    expect(series[0]).toMatchObject({ label: 'Elevation', unit: 'm', values: [10.5, 11, 12] });
  });

  it('falls back to the legacy stream when the enhanced one recorded nothing', () => {
    const series = selectChartSeries(
      streams({ enhancedSpeed: [null, null, null], speed: [2.5, null, 5] }),
    );

    expect(series[0]).toMatchObject({ label: 'Speed', unit: 'km/h', values: [9, null, 18] });
  });

  it('charts all nine sensors when every one was recorded', () => {
    const recorded = [1, 2, 3];
    const series = selectChartSeries(
      streams({
        enhancedAltitude: recorded,
        enhancedSpeed: recorded,
        heartRate: recorded,
        power: recorded,
        cadence: recorded,
        enhancedRespirationRate: recorded,
        temperature: recorded,
        verticalOscillation: recorded,
        stanceTime: recorded,
      }),
    );

    expect(series.map((s) => `${s.label} (${s.unit})`)).toEqual([
      'Elevation (m)',
      'Speed (km/h)',
      'Heart Rate (bpm)',
      'Power (W)',
      'Cadence (/min)',
      'Respiration Rate (brpm)',
      'Temperature (°C)',
      'Vertical Oscillation (mm)',
      'Ground Contact Time (ms)',
    ]);
  });

  it('ignores a stream that is not parallel to the elapsed-time stream', () => {
    expect(selectChartSeries(streams({ heartRate: [140, 150] }))).toEqual([]);
  });

  describe('Grade-Adjusted Pace overlay', () => {
    const hilly = streams({
      routeIndex: [0, 1, 2],
      enhancedAltitude: [30, 31, 32],
      enhancedSpeed: [2.5, 3, null],
    });

    it('overlays GAP on the Speed chart in km/h when the Activity has elevation', () => {
      const series = selectChartSeries(hilly, { gapSpeed: [3, 3.5, null], staminaPct: null });

      const speed = series.find((s) => s.id === 'speed')!;
      expect(speed.overlay).toMatchObject({
        label: 'GAP',
        color: { light: '#c026d3', dark: '#e879f9' },
      });
      const [first, second, missing] = speed.overlay!.values;
      expect(first).toBeCloseTo(10.8, 9);
      expect(second).toBeCloseTo(12.6, 9);
      expect(missing).toBeNull();
      expect(series.filter((s) => s.overlay)).toEqual([speed]);
    });

    it('offers no overlay without a GAP stream', () => {
      expect(
        selectChartSeries(hilly, { gapSpeed: null, staminaPct: null }).some((s) => s.overlay),
      ).toBe(false);
      expect(selectChartSeries(hilly).some((s) => s.overlay)).toBe(false);
    });

    it('offers no overlay without elevation, where GAP only repeats speed', () => {
      const flat = streams({ enhancedSpeed: [2.5, 3, 3] });

      expect(
        selectChartSeries(flat, { gapSpeed: [2.5, 3, 3], staminaPct: null })[0]!.overlay,
      ).toBeUndefined();
    });

    it('offers no overlay without a GPS fix, where GAP repeats speed', () => {
      // An indoor Activity with a barometer: grade is 0 without a GPS fix, so GAP equals speed.
      const indoor = streams({
        routeIndex: [null, null, null],
        enhancedAltitude: [30, 31, 32],
        enhancedSpeed: [2.5, 3, 3],
      });

      expect(
        selectChartSeries(indoor, { gapSpeed: [2.5, 3, 3], staminaPct: null }).some(
          (s) => s.overlay,
        ),
      ).toBe(false);
      expect(
        selectChartSeries(
          { ...indoor, routeIndex: null },
          { gapSpeed: [2.5, 3, 3], staminaPct: null },
        ).some((s) => s.overlay),
      ).toBe(false);
    });

    it('offers no overlay for a GAP stream that is not parallel to the elapsed-time stream', () => {
      expect(
        selectChartSeries(hilly, { gapSpeed: [3, 3], staminaPct: null }).some((s) => s.overlay),
      ).toBe(false);
    });
  });

  describe('Stamina', () => {
    const run = streams({
      enhancedSpeed: [5, 5, 3],
      heartRate: [150, 160, 155],
    });

    it('charts Stamina as its own 0-100 % series directly after Speed', () => {
      const series = selectChartSeries(run, { gapSpeed: null, staminaPct: [100, 50, 62.5] });

      expect(series.map((s) => s.id)).toEqual(['speed', 'stamina', 'heart-rate']);
      expect(series[1]).toMatchObject({
        label: 'Stamina',
        unit: '%',
        values: [100, 50, 62.5],
        yRange: [0, 100],
      });
      expect(series[1]!.overlay).toBeUndefined();
      expect(series[0]!.yRange).toBeUndefined();
    });

    it('charts Stamina after where Speed would be when the device recorded no speed', () => {
      const series = selectChartSeries(
        streams({ enhancedAltitude: [30, 31, 32], heartRate: [150, 160, 155] }),
        { gapSpeed: null, staminaPct: [100, 50, 62.5] },
      );

      expect(series.map((s) => s.id)).toEqual(['elevation', 'stamina', 'heart-rate']);
    });

    it('has no Stamina chart without a stream, or with one not parallel to the elapsed-time stream', () => {
      for (const staminaPct of [null, [100, 50]]) {
        expect(
          selectChartSeries(run, { gapSpeed: null, staminaPct }).some((s) => s.id === 'stamina'),
        ).toBe(false);
      }
      expect(selectChartSeries(run).some((s) => s.id === 'stamina')).toBe(false);
    });
  });
});

describe('sampleIndexAtTime', () => {
  const t = [0, 1, 2, 4, 8, 9];

  it('finds the sample recorded at exactly that elapsed time', () => {
    expect(sampleIndexAtTime(t, 4)).toBe(3);
    expect(sampleIndexAtTime(t, 0)).toBe(0);
    expect(sampleIndexAtTime(t, 9)).toBe(5);
  });

  it('finds the nearest sample between recordings (paused or sparse sampling)', () => {
    expect(sampleIndexAtTime(t, 5.9)).toBe(3);
    expect(sampleIndexAtTime(t, 6.1)).toBe(4);
  });

  it('clamps times outside the Activity to its first and last samples', () => {
    expect(sampleIndexAtTime(t, -3)).toBe(0);
    expect(sampleIndexAtTime(t, 100)).toBe(5);
  });

  it('has no sample in an empty Activity', () => {
    expect(sampleIndexAtTime([], 3)).toBeNull();
  });
});

describe('route lookup by elapsed time', () => {
  const coordinates: LngLat[] = [
    [-0.08, 51.46],
    [-0.18, 51.56],
    [-0.28, 51.66],
  ];
  const t = [0, 1, 2, 3, 4];
  const routeIndex = [0, null, 1, 1, 2];

  it('places the marker on the route vertex of the sample nearest that time', () => {
    expect(routeCoordinateAtTime({ coordinates, t, routeIndex }, 2.2)).toEqual([-0.18, 51.56]);
    expect(routeCoordinateAtTime({ coordinates, t, routeIndex }, 4)).toEqual([-0.28, 51.66]);
  });

  it('has no coordinate for a sample without a GPS fix', () => {
    expect(routeCoordinateAtTime({ coordinates, t, routeIndex }, 1)).toBeNull();
  });

  it('collects the distinct route vertices recorded inside an EffortWindow, in order', () => {
    expect(
      effortRouteCoordinates(
        { coordinates, t, routeIndex },
        { startTimeSeconds: 1, endTimeSeconds: 4 },
      ),
    ).toEqual([
      [-0.18, 51.56],
      [-0.28, 51.66],
    ]);
  });

  it('widens a window that stays on one route vertex to the next vertex, so the path is a line', () => {
    expect(
      effortRouteCoordinates(
        { coordinates, t, routeIndex },
        { startTimeSeconds: 2, endTimeSeconds: 3 },
      ),
    ).toEqual([
      [-0.18, 51.56],
      [-0.28, 51.66],
    ]);
  });

  it('widens a single-vertex window at the end of the route back to the previous vertex', () => {
    expect(
      effortRouteCoordinates(
        { coordinates, t, routeIndex },
        { startTimeSeconds: 4, endTimeSeconds: 4 },
      ),
    ).toEqual([
      [-0.18, 51.56],
      [-0.28, 51.66],
    ]);
  });

  it('has no path when the whole route is a single vertex', () => {
    expect(
      effortRouteCoordinates(
        { coordinates: [[-0.08, 51.46]], t: [0, 1], routeIndex: [0, 0] },
        { startTimeSeconds: 0, endTimeSeconds: 1 },
      ),
    ).toEqual([]);
  });
});

describe('parseRoute', () => {
  it('reads [longitude, latitude] positions', () => {
    expect(
      parseRoute([
        [-0.08, 51.46, 12],
        [-0.18, 51.56],
      ]),
    ).toEqual([
      [-0.08, 51.46],
      [-0.18, 51.56],
    ]);
  });

  it('rejects a route with a malformed position, or fewer than two positions', () => {
    expect(parseRoute([[-0.08, 51.46], [-0.18]])).toBeNull();
    expect(parseRoute([[-0.08, 51.46]])).toBeNull();
  });
});

describe('sensorDropouts', () => {
  const seconds = (count: number) => Array.from({ length: count }, (_, i) => i);

  it('bridges sparse readings a few seconds apart, as smart-recording sensors take them', () => {
    const y = seconds(60).map((i) => (i % 5 === 0 ? 140 : null));

    expect(sensorDropouts({ x: seconds(60), y })).toEqual([]);
  });

  it('breaks the line across silences longer than 30 seconds', () => {
    const x = seconds(200);
    const y = x.map((i) => (i > 50 && i < 120 ? null : 140));

    expect(sensorDropouts({ x, y })).toEqual([{ startTimeSeconds: 50, endTimeSeconds: 120 }]);
  });

  it('keeps a silence of exactly 30 seconds bridged', () => {
    const x = seconds(100);
    const y = x.map((i) => (i > 10 && i < 40 ? null : 140));

    expect(sensorDropouts({ x, y })).toEqual([]);
  });

  it('finds dropouts in a downsampled series, where the silence has no points at all', () => {
    const x = [0, 8, 16, 24, 100, 108, 116];
    const y = [140, 141, 142, 143, 150, 151, 152];

    expect(sensorDropouts({ x, y })).toEqual([{ startTimeSeconds: 24, endTimeSeconds: 100 }]);
  });

  it('does not mistake the spacing of a sparsely downsampled series for dropouts', () => {
    const x = Array.from({ length: 2_000 }, (_, i) => i * 40);
    const y = x.map(() => 140);

    expect(sensorDropouts({ x, y })).toEqual([]);
  });

  it('has no dropouts when fewer than two readings were recorded', () => {
    expect(sensorDropouts({ x: [0, 100], y: [null, 140] })).toEqual([]);
    expect(sensorDropouts({ x: [], y: [] })).toEqual([]);
  });
});

describe('readingAt', () => {
  // Readings every 5 seconds, with a dropout from 20s to 60s.
  const series = {
    x: [0, 1, 2, 3, 4, 5, 10, 15, 20, 40, 60, 65],
    y: [140, null, null, null, null, 145, 150, 155, 160, null, 170, 175],
  };
  const dropouts = sensorDropouts(series);

  it('reads the sample at that time when it was recorded', () => {
    expect(readingAt(series, 5, dropouts)).toBe(145);
  });

  it('reads the nearest recorded sample between sparse readings', () => {
    expect(readingAt(series, 1, dropouts)).toBe(140);
    expect(readingAt(series, 4, dropouts)).toBe(145);
  });

  it('has no reading inside a sensor dropout', () => {
    expect(dropouts).toEqual([{ startTimeSeconds: 20, endTimeSeconds: 60 }]);
    expect(readingAt(series, 40, dropouts)).toBeNull();
  });

  it('has no reading when nothing was recorded', () => {
    expect(readingAt({ x: [0, 1], y: [null, null] }, 0, [])).toBeNull();
    expect(readingAt({ x: [], y: [] }, 0, [])).toBeNull();
  });
});

describe('LTTB downsampling', () => {
  const x = Array.from({ length: 1000 }, (_, i) => i);

  it('renders Activities of up to 15,000 samples at full resolution', () => {
    expect(LTTB_SAMPLE_THRESHOLD).toBe(15_000);
    expect(needsDownsampling(15_000)).toBe(false);
    expect(needsDownsampling(15_001)).toBe(true);
  });

  it('keeps exactly the target number of original points, including the first and last', () => {
    const y = x.map((i) => Math.sin(i / 20));

    const decimated = lttb(x, y, 100);

    expect(decimated.x).toHaveLength(100);
    expect(decimated.x[0]).toBe(0);
    expect(decimated.x.at(-1)).toBe(999);
    decimated.x.forEach((xi, k) => {
      expect(decimated.y[k]).toBe(y[xi]);
      if (k > 0) expect(xi).toBeGreaterThan(decimated.x[k - 1]!);
    });
  });

  it('preserves a one-sample spike that uniform striding would drop', () => {
    const y = x.map((i) => (i === 503 ? 200 : 100));

    const decimated = lttb(x, y, 50);

    expect(decimated.x).toContain(503);
    expect(decimated.y).toContain(200);
  });

  it('keeps a sensor dropout as a gap, downsampling the recorded runs either side of it', () => {
    const y = x.map((i) => (i >= 400 && i < 500 ? null : Math.sin(i / 20)));

    const decimated = lttb(x, y, 100);

    expect(decimated.x.length).toBeLessThanOrEqual(100);
    expect(decimated.x[0]).toBe(0);
    expect(decimated.x.at(-1)).toBe(999);
    // One null marks the dropout where it starts, and nothing inside it is plotted.
    expect(decimated.y.filter((value) => value === null)).toHaveLength(1);
    expect(decimated.x[decimated.y.indexOf(null)]).toBe(400);
    expect(decimated.x.filter((xi) => xi > 400 && xi < 500)).toEqual([]);
    // The recorded points either side of the dropout end and resume their runs.
    expect(decimated.x).toContain(399);
    expect(decimated.x).toContain(500);
    decimated.x.forEach((xi, k) => {
      if (decimated.y[k] !== null) expect(decimated.y[k]).toBe(y[xi]);
      if (k > 0) expect(xi).toBeGreaterThan(decimated.x[k - 1]!);
    });
  });

  it('keeps a gap before the first recorded sample', () => {
    const y = x.map((i) => (i < 10 ? null : i));

    const decimated = lttb(x, y, 20);

    expect(decimated.x.slice(0, 2)).toEqual([0, 10]);
    expect(decimated.y.slice(0, 2)).toEqual([null, 10]);
  });

  it('returns series already within the target unchanged, gaps included', () => {
    expect(lttb([0, 1, 2], [5, null, 7], 10)).toEqual({ x: [0, 1, 2], y: [5, null, 7] });
  });

  it('has nothing to plot for a sensor that recorded nothing', () => {
    expect(
      lttb(
        x,
        x.map(() => null),
        20,
      ),
    ).toEqual({ x: [], y: [] });
  });
});

describe('telemetry scrubber bus', () => {
  const timeline = {
    coordinates: [[-0.08, 51.46] as const, [-0.18, 51.56] as const, [-0.28, 51.66] as const],
    t: [0, 1, 2, 3, 4],
    routeIndex: [0, null, 1, 1, 2],
  };

  /** A manual animation-frame clock standing in for `requestAnimationFrame`. */
  function frames() {
    const queued = new Map<number, () => void>();
    let nextId = 1;
    return {
      requestFrame: (callback: () => void) => {
        const id = nextId++;
        queued.set(id, callback);
        return id;
      },
      cancelFrame: (id: number) => queued.delete(id),
      get pending() {
        return queued.size;
      },
      flush() {
        const callbacks = [...queued.values()];
        queued.clear();
        for (const callback of callbacks) callback();
      },
    };
  }

  function setup() {
    const clock = frames();
    const bus = createTelemetryScrubber({ timeline, clock });
    const charts = [0, 1, 2].map(() => ({ setCursorTime: vi.fn() }));
    for (const chart of charts) bus.registerChart(chart);
    const marker = { moveTo: vi.fn(), hide: vi.fn() };
    bus.registerMarker(marker);
    return { bus, clock, charts, marker };
  }

  it('moves sibling chart cursors and the route marker to the scrubbed time on the next frame', () => {
    const { bus, clock, charts, marker } = setup();

    bus.scrub(2, charts[0]);
    expect(charts[1]!.setCursorTime).not.toHaveBeenCalled();

    clock.flush();
    expect(charts[0]!.setCursorTime).not.toHaveBeenCalled();
    expect(charts[1]!.setCursorTime).toHaveBeenCalledWith(2);
    expect(charts[2]!.setCursorTime).toHaveBeenCalledWith(2);
    expect(marker.moveTo).toHaveBeenCalledWith([-0.18, 51.56]);
  });

  it('coalesces every pointer move within one frame into a single update with the latest time', () => {
    const { bus, clock, charts, marker } = setup();

    bus.scrub(0, charts[0]);
    bus.scrub(3, charts[0]);
    bus.scrub(4, charts[0]);
    expect(clock.pending).toBe(1);
    clock.flush();

    expect(charts[1]!.setCursorTime).toHaveBeenCalledTimes(1);
    expect(charts[1]!.setCursorTime).toHaveBeenCalledWith(4);
    expect(marker.moveTo).toHaveBeenCalledTimes(1);
    expect(marker.moveTo).toHaveBeenCalledWith([-0.28, 51.66]);
  });

  it('hides the sibling cursors and the marker when the pointer leaves a chart', () => {
    const { bus, clock, charts, marker } = setup();

    bus.scrub(null, charts[0]);
    clock.flush();

    expect(charts[1]!.setCursorTime).toHaveBeenCalledWith(null);
    expect(marker.hide).toHaveBeenCalled();
    expect(marker.moveTo).not.toHaveBeenCalled();
  });

  it('hides the marker at a sample without a GPS fix', () => {
    const { bus, clock, charts, marker } = setup();

    bus.scrub(1, charts[0]);
    clock.flush();

    expect(marker.hide).toHaveBeenCalled();
    expect(charts[1]!.setCursorTime).toHaveBeenCalledWith(1);
  });

  it('moves every chart cursor to the first sample recorded at a hovered route vertex', () => {
    const { bus, clock, charts } = setup();

    bus.scrubRouteVertex(1);
    clock.flush();

    for (const chart of charts) expect(chart.setCursorTime).toHaveBeenCalledWith(2);
  });

  it('stops updating a chart once it unregisters, and drops a pending frame when disposed', () => {
    const { bus, clock, charts, marker } = setup();
    const extra = { setCursorTime: vi.fn() };
    bus.registerChart(extra)();

    bus.scrub(2, charts[0]);
    clock.flush();
    expect(extra.setCursorTime).not.toHaveBeenCalled();

    bus.scrub(3, charts[0]);
    bus.dispose();
    expect(clock.pending).toBe(0);
    expect(marker.moveTo).toHaveBeenCalledTimes(1);
  });
});

describe('LTTB downsampling Web Worker', () => {
  it('downsamples every series against the shared elapsed-time stream', () => {
    const t = Array.from({ length: 20_000 }, (_, i) => i);
    const response = handleDownsampleRequest({
      id: 7,
      t,
      series: [t.map((i) => i % 60), t.map(() => null)],
      targetPoints: 500,
    });

    expect(response.id).toBe(7);
    expect(response.series[0]!.x).toHaveLength(500);
    expect(response.series[1]).toEqual({ x: [], y: [] });
  });

  it('correlates concurrent requests through one lazily spawned worker', async () => {
    const posted: Array<Parameters<typeof handleDownsampleRequest>[0]> = [];
    let spawned = 0;
    const worker = {
      onmessage: null as ((event: MessageEvent) => void) | null,
      onerror: null as (() => void) | null,
      postMessage(request: Parameters<typeof handleDownsampleRequest>[0]) {
        posted.push(request);
      },
      terminate: vi.fn(),
    };
    const client = createDownsamplerClient(() => {
      spawned++;
      return worker as unknown as Worker;
    });

    const first = client.downsample([0, 1, 2, 3], [[1, 2, 3, 4]], 3);
    const second = client.downsample([0, 1], [[5, 6]], 3);
    for (const request of [...posted].reverse()) {
      worker.onmessage!({ data: handleDownsampleRequest(request) } as MessageEvent);
    }

    expect(spawned).toBe(1);
    expect(await second).toEqual([{ x: [0, 1], y: [5, 6] }]);
    expect((await first)[0]!.x).toEqual([0, 1, 3]);
    client.dispose();
    expect(worker.terminate).toHaveBeenCalled();
  });
});
