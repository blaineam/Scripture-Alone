#!/usr/bin/env node
/**
 * swift-coverage.mjs — `swift test` for ScriptureAloneCore with code coverage on, then the package's
 * own line coverage (Sources/ only: tests and toolchain files left out). Soren's `core` suite.
 *
 *   node scripts/swift-coverage.mjs [--min=PERCENT] [extra swift test args…]
 *
 * Prints the usual Swift Testing output, then one `soren-coverage: {…}` line that Soren reads into
 * the run's summary and history, plus a per-file table of the twenty least-covered files.
 * Builds outside iCloud Drive (/tmp), where fileproviderd can't stamp xattrs on build products.
 * --min fails the run (exit 3) when line coverage drops below PERCENT — a ratchet, never lowered.
 */
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const PKG = join(ROOT, 'ScriptureAloneCore');
const SOURCES = join(PKG, 'Sources') + '/';
const argv = process.argv.slice(2);
const min = Number((argv.find((a) => a.startsWith('--min=')) || '').slice(6) || 0);
const extra = argv.filter((a) => !a.startsWith('--min='));
// One scratch folder per checkout, so a worktree never fights the main checkout's build lock.
const tag = createHash('sha1').update(ROOT).digest('hex').slice(0, 8);
const scratch = `/tmp/sa-soren-core-${tag}`;

const test = spawnSync('swift', ['test', '--scratch-path', scratch, '--enable-code-coverage', ...extra],
  { cwd: PKG, stdio: 'inherit' });
if (test.status !== 0) process.exit(test.status ?? 1);

const where = spawnSync('swift', ['test', '--scratch-path', scratch, '--show-codecov-path'], { cwd: PKG, encoding: 'utf8' });
const path = (where.stdout || '').trim().split('\n').pop();
let report;
try { report = JSON.parse(readFileSync(path, 'utf8')); } catch (e) {
  console.error(`coverage: could not read ${path || '(no path)'}: ${e.message}`);
  process.exit(2);
}
const files = (report.data?.[0]?.files || []).filter((f) => f.filename.startsWith(SOURCES));
let covered = 0, executable = 0;
const rows = files.map((f) => {
  const l = f.summary.lines;
  covered += l.covered; executable += l.count;
  return { name: relative(SOURCES, f.filename), covered: l.covered, executable: l.count };
});
const percent = executable ? Math.round((covered / executable) * 1000) / 10 : 0;
console.log('\nLeast-covered files (lines):');
for (const r of rows.filter((r) => r.executable >= 20).sort((a, b) => a.covered / a.executable - b.covered / b.executable).slice(0, 20)) {
  console.log(`  ${(100 * r.covered / r.executable).toFixed(1).padStart(5)}%  ${String(r.covered).padStart(5)}/${String(r.executable).padEnd(5)} ${r.name}`);
}
console.log(`soren-coverage: ${JSON.stringify({ percent, covered, executable, targets: [{ name: 'ScriptureAloneCore', covered, executable }] })}`);
console.log(`ScriptureAloneCore line coverage: ${percent}% (${covered}/${executable})`);
if (min && percent < min) {
  console.error(`coverage ${percent}% is below the ${min}% floor`);
  process.exit(3);
}
