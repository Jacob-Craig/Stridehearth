import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createMemoryHistory } from '@tanstack/react-router';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type {
  ActivityChangeDto,
  ImportBatchReceiptDto,
  ImportBatchSummaryDto,
  UserResponseDto,
} from '../src/api/generated/types.gen';
import {
  type ZipInspectionRequest,
  handleZipInspectionRequest,
} from '../src/features/import/zipInspectorProtocol';
import { describeItemUpgrades } from '../src/lib/activityChange';
import { createAppRouter } from '../src/router';
import { buildZip, bytesOfLength } from './support/zipFixtures';

const runner: UserResponseDto = { id: 1, username: 'runner_bob', email: 'bob@example.com' };

/** Runs the real worker protocol in-thread, since jsdom has no Web Worker implementation. */
class InThreadZipInspectorWorker {
  static spawned = 0;
  onmessage: ((event: MessageEvent) => void) | null = null;
  onerror: ((event: ErrorEvent) => void) | null = null;

  constructor() {
    InThreadZipInspectorWorker.spawned++;
  }

  postMessage(request: ZipInspectionRequest) {
    void handleZipInspectionRequest(request).then((response) =>
      this.onmessage?.(new MessageEvent('message', { data: response })),
    );
  }

  terminate() {}
}

function summary(overrides: Partial<ImportBatchSummaryDto> = {}): ImportBatchSummaryDto {
  return {
    id: 7,
    status: 'PROCESSING',
    totalFiles: 3,
    processedFiles: 0,
    successfulFiles: 0,
    duplicateFiles: 0,
    upgradedFiles: 0,
    failedFiles: 0,
    errorMessage: null,
    itemErrors: [],
    itemUpgrades: [],
    createdAt: '2026-03-01T10:00:00Z',
    completedAt: null,
    ...overrides,
  };
}

type Reply = { status: number; body: unknown };
type Handler = (init: RequestInit | undefined) => Reply;

const json = (body: unknown, status = 200): Reply => ({ status, body });

/** Answers each `METHOD url` with the next queued reply, repeating the last one once exhausted. */
function stubApi(routes: Record<string, ReadonlyArray<Reply | Handler>>) {
  const served = new Map<string, number>();
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = typeof input === 'string' ? input : input.toString();
    const key = `${(init?.method ?? 'GET').toUpperCase()} ${url}`;
    const replies = routes[key];
    if (!replies) throw new Error(`Unexpected request ${key}`);
    const index = served.get(key) ?? 0;
    served.set(key, index + 1);
    const next = replies[Math.min(index, replies.length - 1)]!;
    const reply = typeof next === 'function' ? next(init) : next;
    return new Response(JSON.stringify(reply.body), {
      status: reply.status,
      headers: { 'Content-Type': 'application/json' },
    });
  });
  vi.stubGlobal('fetch', fetchMock);
  const callsTo = (key: string) =>
    fetchMock.mock.calls.filter(
      ([input, init]) => `${(init?.method ?? 'GET').toUpperCase()} ${String(input)}` === key,
    );
  return { fetchMock, callsTo };
}

function renderApp(path: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const history = createMemoryHistory({ initialEntries: [path] });
  const router = createAppRouter(
    { queryClient, auth: { user: runner, isLoading: false } },
    history,
  );
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { history };
}

const fitFile = (name: string) => new File([bytesOfLength(64)], name);
const zipFile = (name: string, fitNames: readonly string[]) =>
  new File(
    [
      buildZip([
        ...fitNames.map((n) => ({ name: n, content: bytesOfLength(16) })),
        { name: 'x.txt' },
      ]),
    ],
    name,
    { type: 'application/zip' },
  );

function countFor(term: string): string | null | undefined {
  const dt = screen.getByText(term, { selector: 'dt' });
  return dt.nextElementSibling?.textContent;
}

async function advancePoll() {
  await act(() => vi.advanceTimersByTimeAsync(1000));
}

beforeEach(() => {
  InThreadZipInspectorWorker.spawned = 0;
  vi.stubGlobal('Worker', InThreadZipInspectorWorker);
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe('/activities/import uploader', () => {
  it('previews the .fit file count of picked files, inspecting ZIP archives in the Web Worker', async () => {
    stubApi({});
    const user = userEvent.setup();
    renderApp('/activities/import');

    const picker = await screen.findByLabelText(/choose \.fit or \.zip files/i);
    await user.upload(picker, [
      fitFile('morning.fit'),
      zipFile('export.zip', ['2026/a.fit', '2026/deep/b.fit', '__MACOSX/2026/._a.fit']),
      new File([bytesOfLength(40)], 'broken.zip'),
    ]);

    const selected = screen.getByRole('list', { name: /selected files/i });
    expect(await within(selected).findByText('2 .fit files')).toBeInTheDocument();
    expect(within(selected).getByText('1 .fit file')).toBeInTheDocument();
    expect(within(selected).getByText(/unreadable archive/i)).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent(
      '3 .fit files ready to import (96 B uncompressed)',
    );
    expect(InThreadZipInspectorWorker.spawned).toBe(1);
    expect(screen.getByRole('button', { name: /start import/i })).toBeEnabled();
  });

  it('accepts files dropped onto the drop zone', async () => {
    stubApi({});
    renderApp('/activities/import');

    const zone = await screen.findByRole('region', { name: /file drop zone/i });
    fireEvent.dragOver(zone, { dataTransfer: { files: [], types: ['Files'] } });
    fireEvent.drop(zone, {
      dataTransfer: { files: [zipFile('garmin.zip', ['one.fit', 'two.fit', 'three.FIT'])] },
    });

    const selected = await screen.findByRole('list', { name: /selected files/i });
    expect(await within(selected).findByText('3 .fit files')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('3 .fit files ready to import');
  });

  it('cannot start an import until at least one .fit file is selected', async () => {
    stubApi({});
    const user = userEvent.setup();
    renderApp('/activities/import');

    const start = await screen.findByRole('button', { name: /start import/i });
    expect(start).toBeDisabled();

    await user.upload(screen.getByLabelText(/choose \.fit or \.zip files/i), [
      zipFile('empty.zip', []),
    ]);
    expect(await screen.findByText(/no \.fit files found/i)).toBeInTheDocument();
    expect(start).toBeDisabled();
  });

  it('submits the batch and polls its progress every second until a terminal status', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const receipt: ImportBatchReceiptDto = { batchId: 7, status: 'PROCESSING', totalFiles: 3 };
    let submitted: FormData | undefined;
    const api = stubApi({
      'POST /api/activities/import': [
        (init) => {
          submitted = init?.body as FormData;
          return json(receipt, 202);
        },
      ],
      'GET /api/activities/import/batches/7': [
        json(summary({ processedFiles: 1, successfulFiles: 1 })),
        json(summary({ processedFiles: 2, successfulFiles: 1, duplicateFiles: 1 })),
        json(
          summary({
            status: 'COMPLETED',
            processedFiles: 3,
            successfulFiles: 1,
            duplicateFiles: 1,
            upgradedFiles: 1,
            itemUpgrades: [{ filename: 'old.fit', changes: [] }],
            completedAt: '2026-03-01T10:00:09Z',
          }),
        ),
      ],
    });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    const { history } = renderApp('/activities/import');

    await user.upload(await screen.findByLabelText(/choose \.fit or \.zip files/i), [
      fitFile('morning.fit'),
      zipFile('export.zip', ['a.fit', 'b.fit']),
      new File(['hello'], 'notes.txt'),
    ]);
    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('3 .fit files ready to import'),
    );
    await user.click(screen.getByRole('button', { name: /start import/i }));

    expect(await screen.findByRole('heading', { name: /import batch #7/i })).toBeInTheDocument();
    expect(history.location.search).toBe('?batchId=7');
    expect(submitted?.getAll('files').map((f) => (f as File).name)).toEqual([
      'morning.fit',
      'export.zip',
    ]);

    const progress = screen.getByRole('progressbar', { name: /import progress/i });
    await waitFor(() => expect(progress).toHaveAttribute('value', '1'));
    expect(screen.getByText('Processing')).toBeInTheDocument();
    expect(screen.getByText('1 / 3 files processed')).toBeInTheDocument();

    await advancePoll();
    await waitFor(() => expect(progress).toHaveAttribute('value', '2'));
    expect(countFor('Duplicates')).toBe('1');

    await advancePoll();
    expect(await screen.findByText('Completed')).toBeInTheDocument();
    expect(screen.getByText('3 / 3 files processed')).toBeInTheDocument();
    expect(countFor('Imported')).toBe('1');
    expect(countFor('Parser Version upgrades')).toBe('1');
    expect(countFor('Failed files')).toBe('0');
    expect(screen.queryByRole('button', { name: /cancel import/i })).not.toBeInTheDocument();

    await advancePoll();
    await advancePoll();
    expect(api.callsTo('GET /api/activities/import/batches/7')).toHaveLength(3);
  });

  it('shows why a submission was rejected', async () => {
    stubApi({
      'POST /api/activities/import': [
        json({ kind: 'BAD_REQUEST', message: 'No valid .fit files found in upload' }, 400),
      ],
    });
    const user = userEvent.setup();
    renderApp('/activities/import');

    await user.upload(await screen.findByLabelText(/choose \.fit or \.zip files/i), [
      fitFile('a.fit'),
    ]);
    await user.click(screen.getByRole('button', { name: /start import/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'No valid .fit files found in upload',
    );
  });
});

describe('/activities/import progress dashboard', () => {
  it('cancels an active Import Batch and stops polling', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const api = stubApi({
      'GET /api/activities/import/batches/7': [json(summary({ processedFiles: 1 }))],
      'POST /api/activities/import/batches/7/cancel': [
        json(
          summary({
            status: 'CANCELLED',
            processedFiles: 1,
            completedAt: '2026-03-01T10:00:04Z',
          }),
        ),
      ],
    });
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
    renderApp('/activities/import?batchId=7');

    await user.click(await screen.findByRole('button', { name: /cancel import/i }));

    expect(await screen.findByText('Cancelled')).toBeInTheDocument();
    expect(api.callsTo('POST /api/activities/import/batches/7/cancel')).toHaveLength(1);
    expect(screen.queryByRole('button', { name: /cancel import/i })).not.toBeInTheDocument();
    const pollsAfterCancel = api.callsTo('GET /api/activities/import/batches/7').length;

    await advancePoll();
    await advancePoll();
    expect(api.callsTo('GET /api/activities/import/batches/7')).toHaveLength(pollsAfterCancel);
  });

  it('keeps showing progress and polling through a transient polling failure', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const api = stubApi({
      'GET /api/activities/import/batches/7': [
        json(summary({ processedFiles: 1 })),
        json({ kind: 'BAD_REQUEST', message: 'Gateway timeout' }, 504),
        json(summary({ status: 'COMPLETED', processedFiles: 3, successfulFiles: 3 })),
      ],
    });
    renderApp('/activities/import?batchId=7');

    expect(await screen.findByText('1 / 3 files processed')).toBeInTheDocument();

    await advancePoll();
    expect(await screen.findByText(/reconnecting/i)).toBeInTheDocument();
    expect(screen.getByText('1 / 3 files processed')).toBeInTheDocument();

    await advancePoll();
    expect(await screen.findByText('Completed')).toBeInTheDocument();
    expect(screen.queryByText(/reconnecting/i)).not.toBeInTheDocument();
    expect(api.callsTo('GET /api/activities/import/batches/7')).toHaveLength(3);
  });

  it('polls a PENDING Import Batch until a worker picks it up and completes it', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const api = stubApi({
      'GET /api/activities/import/batches/7': [
        json(summary({ status: 'PENDING' })),
        json(summary({ status: 'PROCESSING', processedFiles: 1, successfulFiles: 1 })),
        json(summary({ status: 'COMPLETED', processedFiles: 3, successfulFiles: 3 })),
      ],
    });
    renderApp('/activities/import?batchId=7');

    expect(await screen.findByText('Pending')).toBeInTheDocument();
    expect(screen.getByText(/waiting for an import worker/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /cancel import/i })).toBeInTheDocument();

    await advancePoll();
    expect(await screen.findByText('Processing')).toBeInTheDocument();
    expect(screen.getByText('1 / 3 files processed')).toBeInTheDocument();

    await advancePoll();
    expect(await screen.findByText('Completed')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /cancel import/i })).not.toBeInTheDocument();

    await advancePoll();
    await advancePoll();
    expect(api.callsTo('GET /api/activities/import/batches/7')).toHaveLength(3);
  });

  it.each([
    ['INTERRUPTED', 'Interrupted', /interrupted by a server restart/i],
    ['FAILED', 'Failed', /disk full/i],
  ] as const)(
    'stops polling once a running Import Batch becomes %s',
    async (status, label, description) => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
      const api = stubApi({
        'GET /api/activities/import/batches/7': [
          json(summary({ processedFiles: 1, successfulFiles: 1 })),
          json(
            summary({
              status,
              processedFiles: 1,
              successfulFiles: 1,
              errorMessage: status === 'FAILED' ? 'Disk full' : null,
              completedAt: '2026-03-01T10:00:05Z',
            }),
          ),
        ],
      });
      renderApp('/activities/import?batchId=7');

      expect(await screen.findByText('Processing')).toBeInTheDocument();

      await advancePoll();
      expect(await screen.findByText(label)).toBeInTheDocument();
      expect(screen.getByText(description)).toBeInTheDocument();
      expect(screen.getByText('1 / 3 files processed')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /cancel import/i })).not.toBeInTheDocument();
      expect(screen.getByRole('link', { name: /start another import/i })).toBeInTheDocument();

      await advancePoll();
      await advancePoll();
      expect(api.callsTo('GET /api/activities/import/batches/7')).toHaveLength(2);
    },
  );

  it('rejects an Import Batch response that does not match the API contract', async () => {
    stubApi({
      'GET /api/activities/import/batches/7': [json({ ...summary(), status: 'EXPLODED' })],
    });
    renderApp('/activities/import?batchId=7');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /this import batch could not be loaded/i,
    );
    expect(screen.queryByText('Processing')).not.toBeInTheDocument();
  });

  it.each([
    ['PENDING', 'Pending', /waiting for an import worker/i, true],
    ['PROCESSING', 'Processing', /importing your activity files/i, true],
    ['COMPLETED', 'Completed', /every file has been processed/i, false],
    ['CANCELLED', 'Cancelled', /files imported before cancelling were kept/i, false],
    ['INTERRUPTED', 'Interrupted', /interrupted by a server restart/i, false],
    ['FAILED', 'Failed', /disk full/i, false],
  ] as const)('renders a %s Import Batch', async (status, label, description, cancellable) => {
    stubApi({
      'GET /api/activities/import/batches/7': [
        json(summary({ status, errorMessage: status === 'FAILED' ? 'Disk full' : null })),
      ],
    });
    renderApp('/activities/import?batchId=7');

    expect(await screen.findByText(label)).toBeInTheDocument();
    expect(screen.getByText(description)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /cancel import/i }) !== null).toBe(cancellable);
  });

  it('lists per-file errors recorded while the batch runs', async () => {
    stubApi({
      'GET /api/activities/import/batches/7': [
        json(
          summary({
            processedFiles: 2,
            failedFiles: 1,
            itemErrors: [{ filename: 'broken.fit', errorMessage: 'Invalid checksum' }],
          }),
        ),
      ],
    });
    const user = userEvent.setup();
    renderApp('/activities/import?batchId=7');

    await user.click(await screen.findByRole('button', { name: /1 file error/i }));
    expect(screen.getByText('broken.fit')).toBeInTheDocument();
    expect(screen.getByText('Invalid checksum')).toBeInTheDocument();
  });

  it('reports an Import Batch that cannot be found', async () => {
    stubApi({
      'GET /api/activities/import/batches/7': [
        json({ kind: 'RESOURCE_NOT_FOUND', message: 'Import Batch 7 was not found' }, 404),
      ],
    });
    renderApp('/activities/import?batchId=7');

    expect(await screen.findByRole('alert')).toHaveTextContent('Import Batch 7 was not found');
  });
});

describe('/activities/import/history', () => {
  const upgradeChanges: ActivityChangeDto[] = [
    { kind: 'UPGRADED', details: { kind: 'PARSER_VERSION_UPGRADED', from: 1, to: 2 }, laps: null },
    { kind: 'UPGRADED', details: null, laps: { kind: 'BACKFILLED', count: 2 } },
  ];
  const history: ImportBatchSummaryDto[] = [
    summary({ id: 12, status: 'PROCESSING', processedFiles: 1, createdAt: '2026-03-02T09:00:00Z' }),
    summary({
      id: 11,
      status: 'COMPLETED',
      totalFiles: 4,
      processedFiles: 4,
      successfulFiles: 1,
      duplicateFiles: 1,
      upgradedFiles: 1,
      failedFiles: 1,
      itemErrors: [{ filename: 'bad.fit', errorMessage: 'Truncated file' }],
      itemUpgrades: [{ filename: 'old.fit', changes: upgradeChanges }],
      createdAt: '2026-02-01T08:00:00Z',
      completedAt: '2026-02-01T08:01:05Z',
    }),
  ];

  it('renders past Import Batches with status badges and client-formatted durations', async () => {
    stubApi({ 'GET /api/activities/import/history': [json(history)] });
    renderApp('/activities/import/history');

    const table = await screen.findByRole('table', { name: /import history/i });
    const [, newest, older] = within(table).getAllByRole('row');
    expect(within(newest!).getByText('Processing')).toBeInTheDocument();
    expect(within(newest!).getByText('2 Mar 2026')).toBeInTheDocument();
    expect(within(newest!).getByText('—')).toBeInTheDocument();
    expect(within(older!).getByText('Completed')).toBeInTheDocument();
    expect(within(older!).getByText('01:05')).toHaveClass('tabular-nums');
    expect(within(older!).getByText('4 / 4')).toHaveClass('tabular-nums');
  });

  it('expands accessible disclosures for item errors and Parser Version upgrades', async () => {
    stubApi({ 'GET /api/activities/import/history': [json(history)] });
    const user = userEvent.setup();
    renderApp('/activities/import/history');

    const errors = await screen.findByRole('button', { name: /1 file error/i });
    const upgrades = screen.getByRole('button', { name: /1 parser version upgrade/i });
    expect(errors).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Truncated file')).not.toBeInTheDocument();

    await user.click(errors);
    expect(errors).toHaveAttribute('aria-expanded', 'true');
    const errorList = document.getElementById(errors.getAttribute('aria-controls')!);
    expect(errorList).toHaveTextContent('bad.fit');
    expect(errorList).toHaveTextContent('Truncated file');

    await user.click(upgrades);
    expect(upgrades).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByText(describeItemUpgrades(upgradeChanges))).toBeInTheDocument();

    await user.click(errors);
    expect(errors).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByText('Truncated file')).not.toBeInTheDocument();
  });

  it('invites the athlete to import when there is no history yet', async () => {
    stubApi({ 'GET /api/activities/import/history': [json([])] });
    renderApp('/activities/import/history');

    expect(await screen.findByText(/no imports yet/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /start an import/i })).toHaveAttribute(
      'href',
      '/activities/import',
    );
  });
});
