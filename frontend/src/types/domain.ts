/**
 * Domain-named aliases for enum values carried by the generated API types, so components speak the
 * GLOSSARY's vocabulary instead of indexing into wire DTOs.
 */
import type { ActivityBestEffortDto, LegacyChangeDto } from '../api/generated/types.gen';

/** A standardized target distance a **Best Effort** is measured over, e.g. `FIVE_KILOMETERS`. */
export type BenchmarkDistance = ActivityBestEffortDto['benchmarkDistance'];

/** What kind of change an **Activity History Entry** records, e.g. `EDITED`. */
export type ActivityHistoryChangeType = LegacyChangeDto['changeType'];
