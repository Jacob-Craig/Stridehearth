import { describe, expect, it } from 'vitest';
import { blobSource, bytesSource, inspectZip } from '../src/features/import/zipInspector';
import { buildZip, bytesOfLength } from './support/zipFixtures';

describe('inspectZip', () => {
  it('counts .fit entries in a standard ZIP and sums their uncompressed size', async () => {
    const archive = buildZip([
      { name: 'morning.fit', content: bytesOfLength(120) },
      { name: 'evening.FIT', content: bytesOfLength(80) },
      { name: 'readme.txt', content: bytesOfLength(999) },
    ]);

    expect(await inspectZip(bytesSource(archive))).toEqual({
      kind: 'inspected',
      tally: { fitFiles: 2, uncompressedBytes: 200 },
    });
  });

  it('counts .fit entries nested in folders while ignoring directories and OS metadata', async () => {
    const archive = buildZip([
      { name: '2026/' },
      { name: '2026/03/run.fit', content: bytesOfLength(10) },
      { name: 'garmin/export/deep/ride.fit', content: bytesOfLength(20) },
      { name: '__MACOSX/2026/03/._run.fit', content: bytesOfLength(4) },
      { name: '2026/03/._run.fit', content: bytesOfLength(4) },
      { name: '2026/.DS_Store', content: bytesOfLength(4) },
      { name: 'folder.fit/' },
    ]);

    expect(await inspectZip(bytesSource(archive))).toEqual({
      kind: 'inspected',
      tally: { fitFiles: 2, uncompressedBytes: 30 },
    });
  });

  it('locates the End of Central Directory behind an archive comment', async () => {
    const archive = buildZip([{ name: 'run.fit', content: bytesOfLength(5) }], {
      comment: 'Exported by Garmin Connect',
    });

    expect(await inspectZip(bytesSource(archive))).toMatchObject({ tally: { fitFiles: 1 } });
  });

  it('ignores an End of Central Directory signature embedded in the archive comment', async () => {
    const fakeEocd = `PK\u0005\u0006${'\u0000'.repeat(18)}`;
    const archive = buildZip([{ name: 'run.fit', content: bytesOfLength(5) }], {
      comment: `${fakeEocd}!!`,
    });

    expect(await inspectZip(bytesSource(archive))).toMatchObject({ tally: { fitFiles: 1 } });
  });

  it('reports an empty archive as containing no .fit entries', async () => {
    expect(await inspectZip(bytesSource(buildZip([])))).toEqual({
      kind: 'inspected',
      tally: { fitFiles: 0, uncompressedBytes: 0 },
    });
  });

  it('follows the ZIP64 EOCD locator and reads 64-bit sizes from the ZIP64 extra field', async () => {
    const archive = buildZip(
      [
        { name: 'big/one.fit', content: bytesOfLength(300) },
        { name: 'big/two.fit', content: bytesOfLength(200) },
        { name: 'big/notes.md', content: bytesOfLength(1) },
      ],
      { zip64: true },
    );

    expect(await inspectZip(bytesSource(archive))).toEqual({
      kind: 'inspected',
      tally: { fitFiles: 2, uncompressedBytes: 500 },
    });
  });

  it('reports an unknown size, not corruption, for a ZIP64 entry missing its extended size field', async () => {
    const archive = buildZip(
      [
        { name: 'big/one.fit', content: bytesOfLength(300) },
        { name: 'big/two.fit', content: bytesOfLength(200) },
      ],
      { zip64: true },
    );
    const view = new DataView(archive.buffer);
    const locator = archive.length - 22 - 20;
    const zip64Eocd = Number(view.getBigUint64(locator + 8, true));
    const centralDirectory = Number(view.getBigUint64(zip64Eocd + 48, true));
    const firstExtraField = centralDirectory + 46 + 'big/one.fit'.length;
    view.setUint16(firstExtraField, 0x9999, true);

    expect(await inspectZip(bytesSource(archive))).toEqual({
      kind: 'inspected',
      tally: { fitFiles: 2, uncompressedBytes: null },
    });
  });

  it('inspects a File by reading slices of the Blob', async () => {
    const archive = buildZip([{ name: 'a/b.fit', content: bytesOfLength(3) }]);
    const file = new File([archive], 'export.zip', { type: 'application/zip' });

    expect(await inspectZip(blobSource(file))).toMatchObject({
      kind: 'inspected',
      tally: { fitFiles: 1 },
    });
  });

  describe('corrupt archives', () => {
    const valid = () =>
      buildZip([
        { name: 'one.fit', content: bytesOfLength(10) },
        { name: 'two.fit', content: bytesOfLength(10) },
      ]);

    it('rejects bytes without an End of Central Directory record', async () => {
      expect(await inspectZip(bytesSource(bytesOfLength(4096)))).toMatchObject({
        kind: 'corrupt',
      });
    });

    it('rejects a file too small to hold an End of Central Directory record', async () => {
      expect(await inspectZip(bytesSource(Uint8Array.of(0x50, 0x4b, 0x05, 0x06)))).toMatchObject({
        kind: 'corrupt',
      });
    });

    it('rejects a truncated archive whose Central Directory lies outside the file', async () => {
      const archive = valid();
      const truncated = archive.slice(30);

      expect(await inspectZip(bytesSource(truncated))).toMatchObject({ kind: 'corrupt' });
    });

    it('rejects a Central Directory header with a damaged signature', async () => {
      const archive = valid();
      const view = new DataView(archive.buffer);
      const eocd = archive.length - 22;
      const cdOffset = view.getUint32(eocd + 16, true);
      view.setUint32(cdOffset, 0xdeadbeef, true);

      expect(await inspectZip(bytesSource(archive))).toMatchObject({ kind: 'corrupt' });
    });

    it('rejects an archive whose entry count disagrees with its Central Directory', async () => {
      const archive = valid();
      const view = new DataView(archive.buffer);
      view.setUint16(archive.length - 22 + 10, 5, true);

      expect(await inspectZip(bytesSource(archive))).toMatchObject({ kind: 'corrupt' });
    });

    it('rejects a ZIP64 archive whose locator points at a damaged ZIP64 EOCD record', async () => {
      const archive = buildZip([{ name: 'one.fit', content: bytesOfLength(10) }], { zip64: true });
      const view = new DataView(archive.buffer);
      const locator = archive.length - 22 - 20;
      const zip64Eocd = Number(view.getBigUint64(locator + 8, true));
      view.setUint32(zip64Eocd, 0, true);

      expect(await inspectZip(bytesSource(archive))).toMatchObject({ kind: 'corrupt' });
    });
  });
});
