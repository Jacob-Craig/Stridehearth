import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { PmcResponseDto } from '../src/api/generated/types.gen';
import { PmcPanel } from '../src/features/pmc/PmcPanel';
import { changeableColourScheme } from './support/colourScheme';
import { pmcDay, pmcSeries, renderedPmcCharts } from './support/fakePmcChart';

vi.mock('../src/features/pmc/pmcChart', () =>
  import('./support/fakePmcChart').then((m) => m.fakePmcChartModule),
);

/** Renders the panel inside a router, since its empty state links to the settings page. */
function renderPanel(pmc: PmcResponseDto | undefined, { isError = false } = {}) {
  let replace: (next: PmcResponseDto) => void = () => {};
  // biome-ignore lint/correctness/noComponentHookFactories: a per-render test harness that exposes its state setter
  function Panel() {
    const [data, setData] = useState(pmc);
    replace = setData;
    return <PmcPanel pmc={{ data, isError }} />;
  }
  const rootRoute = createRootRoute({ component: Panel });
  const router = createRouter({
    routeTree: rootRoute,
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  const rendered = render(<RouterProvider router={router} />);
  /** Swaps in a refetched PMC, as after the Dashboard is invalidated. */
  const refetched = (next: PmcResponseDto) => act(() => replace(next));
  return { ...rendered, refetched };
}

async function liveChart() {
  await waitFor(() => expect(renderedPmcCharts.live()).toHaveLength(1));
  return renderedPmcCharts.live()[0]!;
}

const readout = () =>
  screen.getByRole('status', { name: 'Latest day, or the day under the cursor' });

beforeEach(() => {
  renderedPmcCharts.reset();
  vi.unstubAllGlobals();
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Performance Management Chart panel', () => {
  it('draws one chart of the whole series in three panes, showing the last 90 days', async () => {
    const pmc = pmcSeries(400);
    renderPanel(pmc);

    const chart = await liveChart();
    expect(screen.getByRole('heading', { name: 'Performance Management Chart' })).toBeVisible();
    expect(chart.options.days).toBe(pmc.days);
    expect(chart.options.range).toBe('90d');
    expect(chart.options.panes.fitness).toBeInstanceOf(HTMLElement);
    expect(chart.options.panes.form).toBeInstanceOf(HTMLElement);
    expect(chart.options.panes.acwr).toBeInstanceOf(HTMLElement);
    expect(screen.getByRole('button', { name: '90 days' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '1 year' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'false');
  });

  it('changes the range on the same chart, without recreating it', async () => {
    renderPanel(pmcSeries(400));
    const chart = await liveChart();

    fireEvent.click(screen.getByRole('button', { name: '1 year' }));
    fireEvent.click(screen.getByRole('button', { name: 'All' }));

    expect(chart.setRange.mock.calls).toEqual([['1y'], ['all']]);
    expect(screen.getByRole('button', { name: 'All' })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByRole('button', { name: '90 days' })).toHaveAttribute(
      'aria-pressed',
      'false',
    );
    expect(renderedPmcCharts.charts).toHaveLength(1);
    expect(chart.destroy).not.toHaveBeenCalled();
  });

  it('reads out the latest day before any hover, without needing a pointer', async () => {
    const pmc = pmcSeries(3, '2026-11-18');
    renderPanel(pmc);
    await liveChart();

    expect(readout()).toHaveTextContent(/^18 Nov 2026 · Load 50\.0 · Fitness \(CTL\) 30\.2/);
  });

  it('reads out every value of the hovered day, and returns to the latest day when the cursor leaves', async () => {
    const pmc = pmcSeries(3, '2026-11-18');
    pmc.days[1] = pmcDay({
      date: '2026-11-17',
      trainingLoad: 120.04,
      ctl: 61.26,
      atl: 88.94,
      tsb: -31.2,
      acwr: 1.3449,
      monotony: 2.104,
      strain: 1499.6,
    });
    renderPanel(pmc);
    const chart = await liveChart();

    act(() => chart.options.onHover(1));
    expect(readout()).toHaveTextContent(
      '17 Nov 2026 · Load 120.0 · Fitness (CTL) 61.3 · Fatigue (ATL) 88.9 · Form (TSB) -31.2 High Risk · ACWR 1.34 · Monotony 2.10 · Strain 1500',
    );

    act(() => chart.options.onHover(null));
    expect(readout()).toHaveTextContent(/^18 Nov 2026 · /);
  });

  it('shows dashes for withheld values in the readout', async () => {
    renderPanel({
      timeZone: 'UTC',
      firstDay: '2026-11-18',
      days: [pmcDay({ date: '2026-11-18', trainingLoad: 40 })],
    });
    const chart = await liveChart();

    act(() => chart.options.onHover(0));
    expect(readout()).toHaveTextContent('ACWR — · Monotony — · Strain —');
  });

  it('shows the empty state, linking to Settings, and no chart without Training Load', async () => {
    renderPanel({ timeZone: 'UTC', firstDay: null, days: [] });

    const panel = await screen.findByRole('region', { name: 'Performance Management Chart' });
    expect(within(panel).getByText('No Training Load yet')).toBeVisible();
    expect(within(panel).getByRole('link', { name: /settings/i })).toHaveAttribute(
      'href',
      '/settings',
    );
    expect(within(panel).queryByRole('button', { name: '90 days' })).not.toBeInTheDocument();
    await Promise.resolve();
    expect(renderedPmcCharts.charts).toHaveLength(0);
  });

  it('notes Activities without a Training Load, which count as 0', async () => {
    const pmc = pmcSeries(5);
    pmc.days[1] = { ...pmc.days[1]!, unscoredActivityCount: 2 };
    pmc.days[3] = { ...pmc.days[3]!, unscoredActivityCount: 1 };
    renderPanel(pmc);

    expect(
      await screen.findByText('3 Activities have no Training Load and count as 0.'),
    ).toBeVisible();
  });

  it('notes a single unscored Activity in the singular, and nothing when every Activity is scored', async () => {
    const pmc = pmcSeries(5);
    pmc.days[2] = { ...pmc.days[2]!, unscoredActivityCount: 1 };
    const { unmount } = renderPanel(pmc);
    expect(
      await screen.findByText('1 Activity has no Training Load and counts as 0.'),
    ).toBeVisible();
    unmount();

    renderPanel(pmcSeries(5));
    await liveChart();
    expect(screen.queryByText(/no Training Load and count/)).not.toBeInTheDocument();
  });

  it('creates the chart in the colour scheme, and restyles it in place when the scheme changes', async () => {
    const scheme = changeableColourScheme('dark');
    renderPanel(pmcSeries(10));
    const chart = await liveChart();
    expect(chart.options.theme).toBe('dark');

    act(() => scheme.change('light'));

    await waitFor(() => expect(chart.setTheme).toHaveBeenLastCalledWith('light'));
    expect(renderedPmcCharts.charts).toHaveLength(1);
    expect(chart.destroy).not.toHaveBeenCalled();
  });

  it('moves the chart to a range picked between its creation and the first render that sees it', async () => {
    renderedPmcCharts.onCreate = () =>
      queueMicrotask(() => fireEvent.click(screen.getByRole('button', { name: '1 year' })));
    renderPanel(pmcSeries(400));
    const chart = await liveChart();

    expect(chart.options.range).toBe('90d');
    await waitFor(() => expect(chart.setRange).toHaveBeenLastCalledWith('1y'));
    expect(screen.getByRole('button', { name: '1 year' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('keeps the readout out of the live announcements, since it changes on every pointer move', async () => {
    renderPanel(pmcSeries(3));
    await liveChart();

    expect(readout()).toHaveAttribute('aria-live', 'off');
  });

  it('reserves room for a full day’s reading, so its wrapping on a phone does not move the chart', async () => {
    renderPanel(pmcSeries(3));
    await liveChart();

    // A full reading wraps to four 20 px lines on a 360–390 px phone and to two from the sm breakpoint
    // (measured in the visual check); jsdom has no layout, so the reserved heights are pinned here.
    expect(readout()).toHaveClass('min-h-20', 'sm:min-h-10');
  });

  it('reads out the new series’ latest day when a refetched series redraws the chart', async () => {
    const { refetched } = renderPanel(pmcSeries(3, '2026-11-18'));
    const first = await liveChart();
    act(() => first.options.onHover(1));
    expect(readout()).toHaveTextContent(/^17 Nov 2026 · /);

    refetched(pmcSeries(4, '2026-11-19'));

    await waitFor(() => expect(first.destroy).toHaveBeenCalled());
    const second = await liveChart();
    expect(readout()).toHaveTextContent(/^19 Nov 2026 · /);
    act(() => second.options.onHover(null));
    expect(readout()).toHaveTextContent(/^19 Nov 2026 · /);
  });

  it('says the chart could not be displayed when the PMC could not be loaded', async () => {
    renderPanel(undefined, { isError: true });

    const panel = await screen.findByRole('region', { name: 'Performance Management Chart' });
    expect(within(panel).getByText('This chart could not be displayed.')).toBeVisible();
    expect(within(panel).queryByRole('button', { name: '90 days' })).not.toBeInTheDocument();
    expect(renderedPmcCharts.charts).toHaveLength(0);
  });
});
