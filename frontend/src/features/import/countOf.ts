/** A count with its noun, pluralised in English: `1 file error`, `3 .fit files`. */
export function countOf(count: number, singular: string, plural = `${singular}s`): string {
  return `${count} ${count === 1 ? singular : plural}`;
}
