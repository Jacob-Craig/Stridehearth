import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';
import { describe, expect, it } from 'vitest';

const src = join(__dirname, '..', 'src');

function sourceFiles(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const path = join(dir, entry.name);
    if (entry.isDirectory()) return sourceFiles(path);
    return /\.tsx?$/.test(entry.name) ? [path] : [];
  });
}

/**
 * Which queries go stale when **Activities** change is owned by `src/api`. Pages change data through
 * its hooks, so none of them can forget a refresh by reaching into the query cache themselves.
 */
describe('query cache ownership', () => {
  const pages = [
    ...sourceFiles(join(src, 'features')),
    ...sourceFiles(join(src, 'components')),
    join(src, 'router.tsx'),
  ];

  it.each(pages.map((path) => relative(src, path)))(
    '%s leaves the query cache to src/api',
    (path) => {
      const code = readFileSync(join(src, path), 'utf8');
      expect(code).not.toMatch(
        /\b(useQueryClient|invalidateQueries|refreshActivityData|useActivityMutation|setQueryData)\b/,
      );
    },
  );
});
