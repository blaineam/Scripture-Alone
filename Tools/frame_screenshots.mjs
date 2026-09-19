#!/usr/bin/env node
// Frames raw captures with Monkr, one caption per scene.
//
// The shared pipeline (_shared/update-screenshots.sh) renders a whole device set through ONE
// .monkr, so every frame gets the same text. Scripture Alone's listing tells a story, a caption
// per screen, so this renders each capture through its device's .monkr with its text blocks set
// from docs/appstore-screenshots/captions.json (keyed by device, then by the scene name after the
// "NN-" prefix; a list of lines fills the text blocks in order). A device or scene without a
// caption renders with the design's own text.
// Everything else — canvas, frame, background — is the saved design, so a Monkr editor export of
// the same .monkr matches pixel for pixel.
//
//   node Tools/frame_screenshots.mjs [--device <rawKey>]
//
// Reads DEVICES from .local-screenshots.conf. Writes screenshots/<rawKey>/framed/NN-<scene>.png
// and embeds the raw captures into each .monkr (as `monkr render --save` does), so opening the
// design in Monkr shows the current set.
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir, homedir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const MONKR = join(process.env.MONKR_DIR ?? join(homedir(), 'Documents/scripts/monkr'), 'bin/monkr.mjs');
const only = process.argv.includes('--device') ? process.argv[process.argv.indexOf('--device') + 1] : null;

const conf = readFileSync(join(ROOT, '.local-screenshots.conf'), 'utf8');
const devices = [...conf.matchAll(/^\s*"([^"|]+)\|([^"|]+)\|([^"|]+)"/gm)].map((m) => ({ key: m[1], monkr: m[2] }));
const captions = JSON.parse(readFileSync(join(ROOT, 'docs/appstore-screenshots/captions.json'), 'utf8'));
if (!existsSync(MONKR)) throw new Error(`Monkr CLI not found at ${MONKR} (set MONKR_DIR)`);

const scratch = mkdtempSync(join(tmpdir(), 'sa-frames-'));
try {
  for (const { key, monkr } of devices) {
    if (only && key !== only) continue;
    const raw = join(ROOT, 'screenshots', key);
    const shots = readdirSync(raw).filter((f) => /^\d+-.+\.png$/.test(f)).sort();
    if (!shots.length) throw new Error(`no raw captures in ${raw} — run Tools/capture_screenshots.sh`);
    const designPath = join(ROOT, monkr);
    const design = JSON.parse(readFileSync(designPath, 'utf8'));
    const framed = join(raw, 'framed');
    rmSync(framed, { recursive: true, force: true });
    mkdirSync(framed, { recursive: true });

    for (const shot of shots) {
      const scene = basename(shot, '.png').replace(/^\d+-/, '');
      const project = structuredClone(design);
      // A caption is a line or a list of lines, one per text block. Monkr's text box shrinks to
      // about half the canvas when it wraps, so the lines are set by hand instead.
      const entry = captions[key]?.[scene];
      const lines = entry == null ? [] : Array.isArray(entry) ? entry : [entry];
      lines.forEach((line, i) => { if (project.textBlocks?.[i]) project.textBlocks[i].text = line; });
      const caption = lines.join(' ');
      const temp = join(scratch, `${key}-${scene}.monkr`);
      writeFileSync(temp, JSON.stringify(project));
      execFileSync('node', [MONKR, 'render', temp, '--out', framed, '--screenshots', join(raw, shot)], {
        stdio: ['ignore', 'ignore', 'inherit'],
      });
      if (!existsSync(join(framed, shot))) throw new Error(`Monkr wrote no ${shot} for ${key}`);
      console.log(`  ✓ ${key}/framed/${shot}${caption ? `  “${caption}”` : ''}`);
    }

    // Keep the design's embedded screenshots current, like `monkr render --save`.
    const urls = shots.map((f) => `data:image/png;base64,${readFileSync(join(raw, f)).toString('base64')}`);
    const obj = design.sceneObjects[0];
    obj.screenshotUrl = urls[0];
    obj.screenshotFile = null;
    obj.extraScreenshots = urls.slice(1).map((url) => ({ url, file: null }));
    writeFileSync(designPath, JSON.stringify(design, null, 2) + '\n');
  }
} finally {
  rmSync(scratch, { recursive: true, force: true });
}
