/**
 * Builds ZIP archives byte-by-byte (stored, uncompressed entries) so the ZIP inspector can be exercised
 * against standard, ZIP64, and deliberately corrupted layouts without binary fixture files.
 */

export interface ZipFixtureEntry {
  readonly name: string;
  readonly content?: Uint8Array;
}

export interface ZipFixtureOptions {
  /** Writes ZIP64 EOCD record + locator and saturates the standard EOCD / Central Directory fields. */
  readonly zip64?: boolean;
  readonly comment?: string;
}

const encoder = new TextEncoder();

class ByteWriter {
  private readonly chunks: number[] = [];

  get length(): number {
    return this.chunks.length;
  }

  u16(value: number): this {
    this.chunks.push(value & 0xff, (value >>> 8) & 0xff);
    return this;
  }

  u32(value: number): this {
    this.chunks.push(
      value & 0xff,
      (value >>> 8) & 0xff,
      (value >>> 16) & 0xff,
      (value >>> 24) & 0xff,
    );
    return this;
  }

  u64(value: number): this {
    return this.u32(value % 2 ** 32).u32(Math.floor(value / 2 ** 32));
  }

  bytes(value: Uint8Array): this {
    for (const b of value) this.chunks.push(b);
    return this;
  }

  toUint8Array(): Uint8Array<ArrayBuffer> {
    return Uint8Array.from(this.chunks);
  }
}

export function buildZip(
  entries: ReadonlyArray<ZipFixtureEntry>,
  options: ZipFixtureOptions = {},
): Uint8Array<ArrayBuffer> {
  const zip64 = options.zip64 ?? false;
  const out = new ByteWriter();
  const localOffsets: number[] = [];

  for (const entry of entries) {
    const name = encoder.encode(entry.name);
    const content = entry.content ?? new Uint8Array();
    localOffsets.push(out.length);
    out
      .u32(0x04034b50)
      .u16(20)
      .u16(0)
      .u16(0)
      .u16(0)
      .u16(0)
      .u32(0)
      .u32(content.length)
      .u32(content.length)
      .u16(name.length)
      .u16(0)
      .bytes(name)
      .bytes(content);
  }

  const centralDirectoryOffset = out.length;
  entries.forEach((entry, index) => {
    const name = encoder.encode(entry.name);
    const size = entry.content?.length ?? 0;
    const offset = localOffsets[index] ?? 0;
    const extra = new ByteWriter();
    if (zip64) {
      extra.u16(0x0001).u16(24).u64(size).u64(size).u64(offset);
    }
    const extraBytes = extra.toUint8Array();
    out
      .u32(0x02014b50)
      .u16(zip64 ? 45 : 20)
      .u16(zip64 ? 45 : 20)
      .u16(0)
      .u16(0)
      .u16(0)
      .u16(0)
      .u32(0)
      .u32(zip64 ? 0xffffffff : size)
      .u32(zip64 ? 0xffffffff : size)
      .u16(name.length)
      .u16(extraBytes.length)
      .u16(0)
      .u16(0)
      .u16(0)
      .u32(0)
      .u32(zip64 ? 0xffffffff : offset)
      .bytes(name)
      .bytes(extraBytes);
  });
  const centralDirectorySize = out.length - centralDirectoryOffset;

  if (zip64) {
    const zip64EocdOffset = out.length;
    out
      .u32(0x06064b50)
      .u64(44)
      .u16(45)
      .u16(45)
      .u32(0)
      .u32(0)
      .u64(entries.length)
      .u64(entries.length)
      .u64(centralDirectorySize)
      .u64(centralDirectoryOffset);
    out.u32(0x07064b50).u32(0).u64(zip64EocdOffset).u32(1);
  }

  const comment = encoder.encode(options.comment ?? '');
  out
    .u32(0x06054b50)
    .u16(0)
    .u16(0)
    .u16(zip64 ? 0xffff : entries.length)
    .u16(zip64 ? 0xffff : entries.length)
    .u32(zip64 ? 0xffffffff : centralDirectorySize)
    .u32(zip64 ? 0xffffffff : centralDirectoryOffset)
    .u16(comment.length)
    .bytes(comment);

  return out.toUint8Array();
}

export function bytesOfLength(length: number): Uint8Array<ArrayBuffer> {
  return new Uint8Array(length).fill(7);
}
