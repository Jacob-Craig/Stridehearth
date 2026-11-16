/**
 * Pre-upload ZIP inspection: counts the `.fit` entries an **Import Batch** archive will contribute by
 * reading only the End of Central Directory (EOCD, or its ZIP64 counterpart) and the Central Directory,
 * never the compressed entry data. Pure and environment-agnostic so it runs inside
 * `zipInspector.worker.ts` and directly under Vitest.
 *
 * Entry filtering mirrors the server's `ZipArchiveExtractor.isValidFitEntry`.
 * Format reference: PKWARE APPNOTE.TXT §4.3.12–4.3.16.
 */

import { type FitTally, NO_FIT_FILES, addFitTallies, hasFitExtension } from './fitTally';

/** Random-access reader over an archive, so large exports are sliced rather than loaded whole. */
export interface ByteSource {
  readonly size: number;
  read(offset: number, length: number): Promise<Uint8Array>;
}

export type ZipInspection =
  | { readonly kind: 'inspected'; readonly tally: FitTally }
  | { readonly kind: 'corrupt'; readonly reason: string };

const EOCD_SIGNATURE = 0x06054b50;
const ZIP64_EOCD_LOCATOR_SIGNATURE = 0x07064b50;
const ZIP64_EOCD_SIGNATURE = 0x06064b50;
const CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
const ZIP64_EXTRA_FIELD_ID = 0x0001;

const EOCD_LENGTH = 22;
const ZIP64_EOCD_LOCATOR_LENGTH = 20;
const ZIP64_EOCD_LENGTH = 56;
const CENTRAL_DIRECTORY_HEADER_LENGTH = 46;
const MAX_COMMENT_LENGTH = 0xffff;
const SATURATED_U16 = 0xffff;
const SATURATED_U32 = 0xffffffff;

/** One decoder for every entry name in every archive inspected by this module instance. */
const entryNameDecoder = new TextDecoder();

class CorruptArchive extends Error {}

export function bytesSource(bytes: Uint8Array): ByteSource {
  return {
    size: bytes.length,
    read: async (offset, length) => bytes.subarray(offset, offset + length),
  };
}

export function blobSource(blob: Blob): ByteSource {
  return {
    size: blob.size,
    read: async (offset, length) =>
      new Uint8Array(await blob.slice(offset, offset + length).arrayBuffer()),
  };
}

export async function inspectZip(source: ByteSource): Promise<ZipInspection> {
  try {
    const directory = await locateCentralDirectory(source);
    return { kind: 'inspected', tally: await tallyFitEntries(source, directory) };
  } catch (error) {
    if (error instanceof CorruptArchive) return { kind: 'corrupt', reason: error.message };
    throw error;
  }
}

/** Mirrors the server's filtering: no directories, macOS metadata, `.DS_Store`, or AppleDouble files. */
export function isFitEntryName(entryName: string): boolean {
  const name = entryName.replaceAll('\\', '/');
  if (name.endsWith('/') || name.includes('__MACOSX')) return false;
  const fileName = name.slice(name.lastIndexOf('/') + 1).toLowerCase();
  if (fileName === '.ds_store' || fileName.startsWith('._')) return false;
  return hasFitExtension(fileName);
}

interface CentralDirectory {
  readonly offset: number;
  readonly size: number;
  readonly entryCount: number;
  /** Absolute offset of the record that must follow the Central Directory. */
  readonly endOffset: number;
}

async function locateCentralDirectory(source: ByteSource): Promise<CentralDirectory> {
  if (source.size < EOCD_LENGTH) throw new CorruptArchive('File is too small to be a ZIP archive');

  const tailLength = Math.min(
    source.size,
    EOCD_LENGTH + MAX_COMMENT_LENGTH + ZIP64_EOCD_LOCATOR_LENGTH,
  );
  const tailStart = source.size - tailLength;
  const tail = view(await source.read(tailStart, tailLength));
  const eocd = findEocd(tail);
  if (eocd < 0) throw new CorruptArchive('End of Central Directory record not found');

  const entryCount = tail.getUint16(eocd + 10, true);
  const size = tail.getUint32(eocd + 12, true);
  const offset = tail.getUint32(eocd + 16, true);
  const isZip64 =
    entryCount === SATURATED_U16 || size === SATURATED_U32 || offset === SATURATED_U32;

  const directory = isZip64
    ? await readZip64Directory(source, tail, eocd)
    : { offset, size, entryCount, endOffset: tailStart + eocd };
  if (directory.offset + directory.size > directory.endOffset) {
    throw new CorruptArchive('Central Directory lies outside the archive');
  }
  return directory;
}

/**
 * Scans backwards for the record whose comment ends exactly at end-of-file, so a trailing archive
 * comment cannot hide it and a signature embedded in that comment cannot impersonate it. Falls back
 * to the last plausible record for archives with trailing bytes after the comment.
 */
function findEocd(tail: DataView): number {
  let plausible = -1;
  for (let position = tail.byteLength - EOCD_LENGTH; position >= 0; position--) {
    if (tail.getUint32(position, true) !== EOCD_SIGNATURE) continue;
    const end = position + EOCD_LENGTH + tail.getUint16(position + 20, true);
    if (end === tail.byteLength) return position;
    if (end < tail.byteLength && plausible < 0) plausible = position;
  }
  return plausible;
}

async function readZip64Directory(
  source: ByteSource,
  tail: DataView,
  eocd: number,
): Promise<CentralDirectory> {
  const locator = eocd - ZIP64_EOCD_LOCATOR_LENGTH;
  if (locator < 0 || tail.getUint32(locator, true) !== ZIP64_EOCD_LOCATOR_SIGNATURE) {
    throw new CorruptArchive('ZIP64 End of Central Directory locator not found');
  }
  const recordOffset = toSafeNumber(tail.getBigUint64(locator + 8, true));
  if (recordOffset + ZIP64_EOCD_LENGTH > source.size) {
    throw new CorruptArchive('ZIP64 End of Central Directory record lies outside the archive');
  }
  const record = view(await source.read(recordOffset, ZIP64_EOCD_LENGTH));
  if (record.getUint32(0, true) !== ZIP64_EOCD_SIGNATURE) {
    throw new CorruptArchive('ZIP64 End of Central Directory record is damaged');
  }
  return {
    entryCount: toSafeNumber(record.getBigUint64(32, true)),
    size: toSafeNumber(record.getBigUint64(40, true)),
    offset: toSafeNumber(record.getBigUint64(48, true)),
    endOffset: recordOffset,
  };
}

async function tallyFitEntries(source: ByteSource, directory: CentralDirectory): Promise<FitTally> {
  const bytes = await source.read(directory.offset, directory.size);
  const headers = view(bytes);
  let position = 0;
  let tally = NO_FIT_FILES;

  for (let entry = 0; entry < directory.entryCount; entry++) {
    if (
      position + CENTRAL_DIRECTORY_HEADER_LENGTH > bytes.length ||
      headers.getUint32(position, true) !== CENTRAL_DIRECTORY_SIGNATURE
    ) {
      throw new CorruptArchive(`Central Directory entry ${entry + 1} is damaged`);
    }
    const uncompressedSize = headers.getUint32(position + 24, true);
    const nameLength = headers.getUint16(position + 28, true);
    const extraLength = headers.getUint16(position + 30, true);
    const commentLength = headers.getUint16(position + 32, true);
    const nameStart = position + CENTRAL_DIRECTORY_HEADER_LENGTH;
    const extraStart = nameStart + nameLength;
    const next = extraStart + extraLength + commentLength;
    if (next > bytes.length) {
      throw new CorruptArchive(`Central Directory entry ${entry + 1} is truncated`);
    }

    const name = entryNameDecoder.decode(bytes.subarray(nameStart, extraStart));
    if (isFitEntryName(name)) {
      const uncompressedBytes =
        uncompressedSize === SATURATED_U32
          ? zip64UncompressedSize(headers, extraStart, extraLength)
          : uncompressedSize;
      tally = addFitTallies(tally, { fitFiles: 1, uncompressedBytes });
    }
    position = next;
  }

  return tally;
}

/**
 * The uncompressed size is the first field of the ZIP64 extended information extra field when
 * saturated. The size only feeds the disk-footprint preview, so an entry missing that field, or
 * whose size a `number` cannot hold exactly, has an unknown size rather than marking the archive corrupt.
 */
function zip64UncompressedSize(
  headers: DataView,
  extraStart: number,
  extraLength: number,
): number | null {
  let field = extraStart;
  const end = extraStart + extraLength;
  while (field + 4 <= end) {
    const id = headers.getUint16(field, true);
    const length = headers.getUint16(field + 2, true);
    if (id === ZIP64_EXTRA_FIELD_ID && length >= 8 && field + 12 <= end) {
      const size = headers.getBigUint64(field + 4, true);
      return size > BigInt(Number.MAX_SAFE_INTEGER) ? null : Number(size);
    }
    field += 4 + length;
  }
  return null;
}

function view(bytes: Uint8Array): DataView {
  return new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
}

function toSafeNumber(value: bigint): number {
  if (value > BigInt(Number.MAX_SAFE_INTEGER)) {
    throw new CorruptArchive('ZIP64 field exceeds the supported size');
  }
  return Number(value);
}
