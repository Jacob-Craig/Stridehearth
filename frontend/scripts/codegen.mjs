// Generates the typed API client from the committed OpenAPI snapshot (openapi.json), or with
// `--check` verifies that src/api/generated is exactly what codegen would produce right now.
//
//   node scripts/codegen.mjs                         regenerate src/api/generated
//   node scripts/codegen.mjs --check [--generated <dir>]   fail if <dir> (default src/api/generated) is stale
//
// The snapshot itself is kept honest by the backend's OpenApiContractTest (ADR-0004).
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const frontendDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const defaultOutput = path.join(frontendDir, 'src/api/generated');
const NO_CHECK_HEADER = '// @ts-nocheck\n';

const args = process.argv.slice(2);
const check = args.includes('--check');
const generatedFlag = args.indexOf('--generated');
const generatedDir =
  generatedFlag >= 0 && args[generatedFlag + 1]
    ? path.resolve(args[generatedFlag + 1])
    : defaultOutput;

function runOpenApiTs(outputDir) {
  const bin = path.join(frontendDir, 'node_modules/.bin/openapi-ts');
  execFileSync(bin, ['--silent', '--no-log-file', '--output', outputDir], {
    cwd: frontendDir,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
}

// Generated code is exempt from the strict app tsconfig; tag every file so tsc skips it.
function tagNoCheck(dir) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const file = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      tagNoCheck(file);
    } else if (file.endsWith('.ts')) {
      const source = fs.readFileSync(file, 'utf8');
      if (!source.startsWith(NO_CHECK_HEADER)) {
        fs.writeFileSync(file, NO_CHECK_HEADER + source);
      }
    }
  }
}

function listFiles(dir, base = dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? listFiles(file, base) : [path.relative(base, file)];
  });
}

if (!check) {
  runOpenApiTs(defaultOutput);
  tagNoCheck(defaultOutput);
  process.exit(0);
}

const expectedDir = fs.mkdtempSync(path.join(os.tmpdir(), 'openapi-codegen-check-'));
try {
  runOpenApiTs(expectedDir);
  tagNoCheck(expectedDir);

  const expected = listFiles(expectedDir);
  const actual = listFiles(generatedDir);
  const stale = [
    ...expected.filter(
      (file) =>
        !actual.includes(file) ||
        fs.readFileSync(path.join(expectedDir, file), 'utf8') !==
          fs.readFileSync(path.join(generatedDir, file), 'utf8'),
    ),
    ...actual.filter((file) => !expected.includes(file)),
  ].sort();

  if (stale.length > 0) {
    console.error('Generated API client is stale versus openapi.json:');
    for (const file of stale) console.error(`  ${file}`);
    console.error('Run `npm run codegen` in frontend/ and commit src/api/generated.');
    process.exit(1);
  }
  console.log('Generated API client matches openapi.json.');
} finally {
  fs.rmSync(expectedDir, { recursive: true, force: true });
}
