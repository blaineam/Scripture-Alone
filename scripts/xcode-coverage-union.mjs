#!/usr/bin/env node
/**
 * xcode-coverage-union.mjs — line coverage of the app's own Swift sources across several test runs:
 * a line counts as covered when ANY of the result bundles ran it (the hosted unit tests and the
 * XCUITest suites exercise different code — the unit tests the logic, the UI tests the screens — and
 * each bundle measures its own build, so `xcresulttool merge` can't add them up).
 *
 *   SOREN_KEEP_RESULTS=1 node ../_shared/soren/soren.mjs run "Scripture Alone" ios ui-iphone ui-ipad
 *   node scripts/xcode-coverage-union.mjs <a.xcresult> <b.xcresult> … [--dirs=ScriptureAlone,ScriptureAloneWatch]
 *
 * Prints a per-folder table and one total. Only files under the given top-level folders count
 * (default: ScriptureAlone/ — the iPhone, iPad and Mac app), never test bundles.
 */
import { spawnSync } from 'node:child_process';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const argv = process.argv.slice(2);
const dirs = ((argv.find((a) => a.startsWith('--dirs=')) || '--dirs=ScriptureAlone').slice(7)).split(',').map((d) => d.replace(/\/$/, '') + '/');
const bundles = argv.filter((a) => !a.startsWith('--'));
if (!bundles.length) { console.error('usage: xcode-coverage-union.mjs <bundle.xcresult>… [--dirs=A,B]'); process.exit(64); }

/** file → Map(line → covered?) for every executable line any bundle reports. */
const lines = new Map();
for (const bundle of bundles) {
  const r = spawnSync('xcrun', ['xccov', 'view', '--archive', '--json', bundle], { encoding: 'utf8', maxBuffer: 1 << 30 });
  if (r.status !== 0) { console.error(`xccov failed on ${bundle}: ${r.stderr}`); process.exit(2); }
  const archive = JSON.parse(r.stdout);
  for (const [path, entries] of Object.entries(archive)) {
    const rel = relative(ROOT, path);
    if (rel.startsWith('..') || !dirs.some((d) => rel.startsWith(d))) continue;
    const file = lines.get(rel) || new Map();
    for (const e of entries) {
      if (!e.isExecutable) continue;
      file.set(e.line, file.get(e.line) || (e.executionCount ?? 0) > 0);
    }
    lines.set(rel, file);
  }
}

const folders = new Map();
let covered = 0, executable = 0;
for (const [rel, file] of lines) {
  const c = [...file.values()].filter(Boolean).length, n = file.size;
  covered += c; executable += n;
  const folder = rel.split('/').slice(0, 2).join('/');
  const f = folders.get(folder) || { covered: 0, executable: 0 };
  f.covered += c; f.executable += n;
  folders.set(folder, f);
}
const pct = (c, n) => (n ? (100 * c / n).toFixed(1) : '0.0');
for (const [folder, f] of [...folders].sort((a, b) => b[1].executable - a[1].executable)) {
  console.log(`${pct(f.covered, f.executable).padStart(6)}%  ${String(f.covered).padStart(6)}/${String(f.executable).padEnd(6)} ${folder}`);
}
console.log(`union line coverage (${dirs.join(', ')}): ${pct(covered, executable)}% (${covered}/${executable}) over ${bundles.length} run(s)`);
