import { queryOptions, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { match } from 'ts-pattern';
import type { ZodType } from 'zod';
import { refreshActivityData } from './activities';
import { ApiError, apiClient } from './client';
import type { ImportBatchReceiptDto, ImportBatchSummaryDto } from './generated/types.gen';
import {
  zCancelImportBatchResponse,
  zGetImportBatchResponse,
  zGetImportHistoryResponse,
  zSubmitImportBatchResponse,
} from './generated/zod.gen';

export type ImportBatchStatus = ImportBatchSummaryDto['status'];

/** How often an active **Import Batch** is polled for progress. */
export const IMPORT_BATCH_POLL_INTERVAL_MS = 1000;

/** Whether an **Import Batch** has stopped changing, ending progress polling and cancellation. */
export function isTerminalImportBatchStatus(status: ImportBatchStatus): boolean {
  return match(status)
    .with('PENDING', 'PROCESSING', () => false)
    .with('COMPLETED', 'CANCELLED', 'INTERRUPTED', 'FAILED', () => true)
    .exhaustive();
}

/**
 * Runtime-validates a response against its generated Zod schema, then returns the JSON as received:
 * the generated int64 schemas coerce ids to `bigint`, whereas the generated types (and routes) use `number`.
 */
async function conforming<T>(schema: ZodType, response: Promise<unknown>): Promise<T> {
  const data = await response;
  const result = schema.safeParse(data);
  if (!result.success) {
    throw new Error('The server sent an Import Batch response this app does not understand', {
      cause: result.error,
    });
  }
  return data as T;
}

export async function submitImportBatch(files: readonly File[]): Promise<ImportBatchReceiptDto> {
  const body = new FormData();
  for (const file of files) body.append('files', file, file.name);
  return conforming(
    zSubmitImportBatchResponse,
    apiClient('/api/activities/import', { method: 'POST', body }),
  );
}

export async function getImportBatch(batchId: number): Promise<ImportBatchSummaryDto> {
  return conforming(
    zGetImportBatchResponse,
    apiClient(`/api/activities/import/batches/${batchId}`),
  );
}

export async function cancelImportBatch(batchId: number): Promise<ImportBatchSummaryDto> {
  return conforming(
    zCancelImportBatchResponse,
    apiClient(`/api/activities/import/batches/${batchId}/cancel`, { method: 'POST' }),
  );
}

export async function getImportHistory(): Promise<ImportBatchSummaryDto[]> {
  return conforming(zGetImportHistoryResponse, apiClient('/api/activities/import/history'));
}

interface ImportBatchQuery {
  readonly userId: number | undefined;
  readonly batchId: number;
}

/**
 * Polls an **Import Batch** every second until it reaches a terminal status, riding out transient
 * failures but stopping once the batch is not found. Keyed by the athlete so
 * a cached batch is never served to a different athlete on the same client.
 */
export const importBatchQueryOptions = ({ userId, batchId }: ImportBatchQuery) =>
  queryOptions({
    queryKey: ['importBatch', userId, batchId] as const,
    queryFn: () => getImportBatch(batchId),
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      const missing = query.state.error instanceof ApiError && query.state.error.status === 404;
      if (missing || (status && isTerminalImportBatchStatus(status))) return false;
      return IMPORT_BATCH_POLL_INTERVAL_MS;
    },
  });

export const importHistoryQueryOptions = (userId: number | undefined) =>
  queryOptions({
    queryKey: ['importHistory', userId] as const,
    queryFn: getImportHistory,
  });

/**
 * Follows one **Import Batch** until it is terminal, and offers to cancel it. The first time the batch
 * is seen terminal, the import history and everything derived from the athlete's **Activities** are
 * refreshed: even a cancelled, interrupted or failed batch keeps the files it imported first, which
 * may have created **Activities**, upgraded **Activity Details** and recalibrated **Personal Records**.
 */
export function useImportBatch({ userId, batchId }: ImportBatchQuery) {
  const queryClient = useQueryClient();
  const options = importBatchQueryOptions({ userId, batchId });
  const batch = useQuery(options);
  const cancellation = useMutation({
    mutationFn: () => cancelImportBatch(batchId),
    onSuccess: (summary) => queryClient.setQueryData(options.queryKey, summary),
  });

  const terminal = batch.data ? isTerminalImportBatchStatus(batch.data.status) : false;
  useEffect(() => {
    if (!terminal) return;
    void queryClient.invalidateQueries({ queryKey: importHistoryQueryOptions(userId).queryKey });
    void refreshActivityData(queryClient, userId);
  }, [terminal, queryClient, userId]);

  return { batch, cancellation, terminal };
}
