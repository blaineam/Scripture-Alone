#!/usr/bin/env node
/**
 * android-release-smoke.mjs — the phone app as it ships (R8-minified, resources shrunk), on an emulator.
 * Soren's `android-release-smoke` suite (soren.config.mjs).
 *
 *   node scripts/android-release-smoke.mjs [--no-build] [--keep-emulator] [--skip-connected] [--apk=FILE]
 *   (--apk: smoke another build — e.g. the previous release, for a before/after memory reading)
 *
 *  1. Builds `:app:assembleRelease -PdebugSignedRelease -PsideloadApk -PappIdSuffix=smoke` — release,
 *     signed with the debug key so it installs, every Bible pack in its assets (only Play can deliver
 *     an asset pack), as …scripturealone.smoke so a reader's install on the emulator is left alone.
 *  2. Checks R8 really obfuscated the app: mapping.txt exists and maps the app's own classes to
 *     short names (a keep-everything rule would ship readable classes and a larger app).
 *  3. Uses its own AVD, `SA_AVD` (default sa_guide_phone, API 35), if it's running; else boots it
 *     headless on port SA_EMULATOR_PORT (default 5556) and shuts it down at the end. Every adb and
 *     Gradle call targets that serial; an emulator running any other AVD is never touched.
 *  4. Installs it fresh and drives it with uiautomator: launch → open a chapter (Go To) → Listen →
 *     Previous/Next tapped as fast as adb can send them (the 1.1.0-rc.5 ANR) → Minimize player (the pill,
 *     still reading; its play/pause), Expand, a swipe down to minimize, Stop → the share card (three styles, Customize › Strong shadow) →
 *     a 48-megapixel photo imported as a slide. After every step: the process must be alive, with
 *     no crash and no ANR in the logs.
 *  5. Runs the instrumented tests (connectedDebugAndroidTest — incl. SystemBarsInsetsTest) on the
 *     same emulator, unless --skip-connected.
 *
 * Env: JAVA_HOME, ANDROID_HOME (Soren sets both), SA_AVD.
 * Exit 0 = green. Gradle: one build at a time on this Mac — it waits for another to finish.
 */
import { execFileSync, spawn } from 'node:child_process';
import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const ANDROID = join(ROOT, 'android');
// The classes' namespace; the smoke installs as …scripturealone.smoke (-PappIdSuffix), beside — never
// over — any other install on the emulator.
const NS = 'com.blainemiller.scripturealone';
const PKG = `${NS}.smoke`;
const SDK = process.env.ANDROID_HOME || '/opt/homebrew/share/android-commandlinetools';
const ADB = join(SDK, 'platform-tools', 'adb');
const EMULATOR = join(SDK, 'emulator', 'emulator');
const AVD = process.env.SA_AVD || 'sa_guide_phone';
const args = new Set(process.argv.slice(2));
const OUT = join('/tmp', 'sa-release-smoke');
mkdirSync(OUT, { recursive: true });

const log = (m) => console.log(`• ${m}`);
const fail = (m) => { throw new Error(m); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const sh = (cmd, a, opts = {}) => execFileSync(cmd, a, { encoding: 'utf8', maxBuffer: 64 << 20, ...opts });
let serial = null;
const adb = (...a) => sh(ADB, serial ? ['-s', serial, ...a] : a);
const shell = (cmd) => adb('shell', cmd);

// ── Gradle, one at a time ───────────────────────────────────────────────────────────────────────
async function gradle(tasks) {
	// Idle for 30 s straight: two builds that both just finished waiting mustn't start together.
	for (let idle = 0, said = false; idle < 3;) {
		let busy = '';
		try { busy = sh('pgrep', ['-f', 'gradle-wrapper.jar|GradleWrapperMain']).trim(); } catch { /* none */ }
		if (busy) { idle = 0; if (!said) { log('another Gradle build is running — waiting'); said = true; } } else idle++;
		await sleep(10_000);
	}
	log(`gradlew ${tasks.join(' ')}`);
	execFileSync('./gradlew', ['--no-daemon', ...tasks], { cwd: ANDROID, stdio: 'inherit', env: { ...process.env, ...(serial ? { ANDROID_SERIAL: serial } : {}) } });
}

// ── R8 ──────────────────────────────────────────────────────────────────────────────────────────
function checkR8() {
	const mapping = join(ANDROID, 'app/build/outputs/mapping/release/mapping.txt');
	if (!existsSync(mapping)) fail(`no ${mapping}: R8 did not run on the release build`);
	const classes = readFileSync(mapping, 'utf8').split('\n').filter((l) => l.startsWith(`${NS}.`) && l.includes(' -> '));
	const renamed = classes.filter((l) => { const [from, to] = l.replace(/:$/, '').split(' -> '); return from !== to; });
	const share = classes.length ? renamed.length / classes.length : 0;
	log(`R8: ${renamed.length} of ${classes.length} app classes renamed (${(share * 100).toFixed(0)}%)`);
	for (const c of ['ui.listen.ListenSpeech', 'data.image.ImageSizing', 'speech.SpeechThread']) {
		const line = classes.find((l) => l.startsWith(`${NS}.${c} -> `));
		if (line) log(`  ${line.replace(/:$/, '')}`);
	}
	if (classes.length < 100 || share < 0.5) fail('R8 left most of the app\'s classes un-obfuscated');
}

// ── the emulator ────────────────────────────────────────────────────────────────────────────────
function devices() {
	return sh(ADB, ['devices']).split('\n').slice(1).map((l) => l.split('\t')).filter((p) => p[1] === 'device').map((p) => p[0]);
}

/** The AVD an emulator runs, or '' when it won't say. */
function avdOf(device) {
	try { return sh(ADB, ['-s', device, 'emu', 'avd', 'name']).split('\n')[0].trim(); } catch { return ''; }
}

async function emulatorUp() {
	// Only ever this suite's own AVD: an emulator running another (another project's tests) is
	// never touched — not used, not installed on, not shut down.
	const mine = devices().filter((d) => d.startsWith('emulator-')).find((d) => avdOf(d) === AVD);
	if (mine) {
		serial = mine;
		log(`using ${AVD}, already running as ${serial}`);
		return false;
	}
	const port = Number(process.env.SA_EMULATOR_PORT || 5556);
	serial = `emulator-${port}`;
	if (devices().includes(serial)) fail(`${serial} is taken by another AVD (${avdOf(serial)}); set SA_EMULATOR_PORT`);
	log(`booting ${AVD} headless as ${serial}`);
	const child = spawn(EMULATOR, ['-avd', AVD, '-port', String(port), '-no-window', '-no-snapshot-save', '-no-boot-anim', '-no-audio'], { detached: true, stdio: 'ignore' });
	child.unref();
	const deadline = Date.now() + 300_000;
	let up = false;
	while (Date.now() < deadline && !up) {
		await sleep(3_000);
		if (!devices().includes(serial)) continue;
		try { up = shell('getprop sys.boot_completed').trim() === '1'; } catch { /* booting */ }
	}
	if (!up) fail(`${AVD} did not boot`);
	shell('settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0');
	return true;
}

// ── uiautomator ─────────────────────────────────────────────────────────────────────────────────
function dump() {
	for (let i = 0; i < 3; i++) {
		try {
			shell('uiautomator dump /sdcard/sa-ui.xml >/dev/null');
			return adb('exec-out', 'cat', '/sdcard/sa-ui.xml');
		} catch { /* a transient "could not get idle state" */ }
	}
	return '';
}

function nodes(xml) {
	return [...xml.matchAll(/<node ([^>]*?)\/?>/g)].map((m) => {
		const attr = (k) => (m[1].match(new RegExp(` ?${k}="([^"]*)"`)) || [])[1] ?? '';
		const b = attr('bounds').match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/);
		const unescape = (s) => s.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>');
		return {
			text: unescape(attr('text')), desc: unescape(attr('content-desc')), id: attr('resource-id'), pkg: attr('package'),
			clickable: attr('clickable') === 'true',
			bounds: b ? b.slice(1).map(Number) : [0, 0, 0, 0],
		};
	});
}

const matches = (n, q) => (q instanceof RegExp ? q.test(n.text) || q.test(n.desc) : n.text === q || n.desc === q);

async function find(q, { timeout = 15_000 } = {}) {
	const deadline = Date.now() + timeout;
	do {
		const hit = nodes(dump()).find((n) => matches(n, q) && n.bounds[2] > n.bounds[0]);
		if (hit) return hit;
		await sleep(700);
	} while (Date.now() < deadline);
	return null;
}

const center = (n) => [Math.round((n.bounds[0] + n.bounds[2]) / 2), Math.round((n.bounds[1] + n.bounds[3]) / 2)];
const tapAt = ([x, y]) => shell(`input tap ${x} ${y}`);

async function tap(q, opts) {
	const n = await find(q, opts);
	if (!n) { screenshot('missing'); fail(`nothing on screen matches ${q}`); }
	tapAt(center(n));
	await sleep(600);
	return n;
}

function screenshot(name) {
	try { writeFileSync(join(OUT, `${name}.png`), execFileSync(ADB, ['-s', serial, 'exec-out', 'screencap', '-p'], { maxBuffer: 64 << 20 })); } catch { /* best effort */ }
}

// ── health ──────────────────────────────────────────────────────────────────────────────────────
function alive(step) {
	const pid = (() => { try { return shell(`pidof ${PKG}`).trim(); } catch { return ''; } })();
	// A crash of this app: the crash buffer names it as "Process: <package>, PID: …".
	const crash = adb('logcat', '-d', '-b', 'crash').split('\n').filter((l) => l.includes(`Process: ${PKG},`));
	const anr = adb('logcat', '-d', '-b', 'events').split('\n').filter((l) => /am_anr/.test(l) && l.includes(PKG));
	const stripped = adb('logcat', '-d', '-b', 'main').split('\n').filter((l) => /ClassNotFoundException|NoSuchMethodException|NoSuchFieldException/.test(l) && l.includes(PKG));
	if (crash.length || anr.length || stripped.length || !pid) {
		screenshot(`failed-${step}`);
		fail(`${step}: ${!pid ? 'the app is not running. ' : ''}${[...crash, ...anr, ...stripped].slice(0, 12).join('\n')}`);
	}
	log(`✓ ${step}`);
}

// ── the run ─────────────────────────────────────────────────────────────────────────────────────
async function main() {
	const given = process.argv.find((x) => x.startsWith('--apk='))?.slice(6);
	if (!given && !args.has('--no-build')) await gradle([':app:assembleRelease', '-PdebugSignedRelease', '-PsideloadApk', '-PappIdSuffix=smoke']);
	if (!given) checkR8();
	const apk = given || join(ANDROID, 'app/build/outputs/apk/release/app-release.apk');
	if (!existsSync(apk)) fail(`no ${apk}`);

	const booted = await emulatorUp();
	try {
		try { adb('uninstall', PKG); } catch { /* not installed */ }
		log('installing the release APK');
		adb('install', '-r', '-g', apk);
		adb('logcat', '-c', '-b', 'all');

		await smoke();

		if (!args.has('--skip-connected')) {
			// Debug build's instrumented tests on the same emulator (edge-to-edge insets, verse nodes…).
			try { adb('uninstall', PKG); } catch { /* */ }
			// 3-button navigation: in landscape it is a bar at the side, which the insets test must clear.
			const NAV = 'com.android.internal.systemui.navbar.';
			const gestural = /\[x\] com\.android\.internal\.systemui\.navbar\.gestural/.test(shell('cmd overlay list'));
			shell(`cmd overlay enable-exclusive --category ${NAV}threebutton`);
			try {
				await sleep(2_000);
				await gradle([':app:connectedDebugAndroidTest', '-PappIdSuffix=smoke']);
			} finally {
				if (gestural) shell(`cmd overlay enable-exclusive --category ${NAV}gestural`);
				shell('cmd window user-rotation free');
			}
		}
	} finally {
		try { adb('uninstall', PKG); } catch { /* */ }
		if (booted && !args.has('--keep-emulator')) {
			log(`shutting down ${serial}`);
			try { adb('emu', 'kill'); } catch { /* */ }
		}
	}
	console.log('✓ release smoke green');
}

/** A 48-megapixel "slide" (8000×6000 JPEG) — the worst case for the slide import's memory. */
function bigPhoto() {
	const file = join(OUT, 'sa-smoke-slide.jpg');
	if (!existsSync(file)) {
		sh('python3', ['-c', `
from PIL import Image, ImageDraw, ImageFont
im = Image.new('RGB', (8000, 6000), (20, 30, 60))
d = ImageDraw.Draw(im)
try: f = ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf', 420)
except Exception: f = ImageFont.load_default()
d.rectangle([800, 600, 7200, 5400], fill=(240, 240, 235))
for i, t in enumerate(['The Good Shepherd', 'John 10:11-15', 'Psalm 23:1-6']):
    d.text((1200, 1200 + i * 1200), t, fill=(10, 10, 10), font=f)
im.save(${JSON.stringify(file)}, quality=92)
`]);
	}
	return file;
}

/** The app's memory now: dumpsys meminfo's summary rows, in KB. */
function memory(step) {
	const text = shell(`dumpsys meminfo ${PKG}`);
	writeFileSync(join(OUT, `meminfo-${step}.txt`), text);
	const row = (label) => Number((text.match(new RegExp(`${label}:\\s+(\\d+)`)) || [])[1] || NaN);
	const m = { javaHeap: row('Java Heap'), nativeHeap: row('Native Heap'), graphics: row('Graphics'), totalPss: row('TOTAL PSS') };
	log(`memory at ${step}: Java ${m.javaHeap} KB, native ${m.nativeHeap} KB, graphics ${m.graphics} KB, PSS ${m.totalPss} KB`);
	return m;
}

async function dismissWelcome() {
	// A fresh install's first launch may offer the User Guide (it can appear a moment after the
	// reader): Skip it, and go on only once the reader is up with no dialog over it.
	const deadline = Date.now() + 40_000;
	while (Date.now() < deadline) {
		const all = nodes(dump());
		const skip = all.find((n) => n.text === 'Skip' || n.desc === 'Skip');
		if (skip) { tapAt(center(skip)); await sleep(1_000); continue; }
		if (all.some((n) => /^Go to passage, currently /.test(n.desc))) { await sleep(1_500); if (!nodes(dump()).some((n) => n.text === 'Skip')) return; continue; }
		await sleep(1_000);
	}
	screenshot('launch');
	fail('the reader never showed its toolbar');
}

async function smoke() {
	shell(`am start -W -n ${PKG}/${NS}.MainActivity`);
	await dismissWelcome();
	alive('launch');

	// Open a chapter through Go To.
	await tap(/^Go to passage, currently /);
	if (!(await find('Go To', { timeout: 10_000 }))) { screenshot('goto'); fail('Go To did not open'); }
	await tap(/^(Go to or search|John 3:16, Rom 8, or search words)$/);
	shell('input text "John%s3"');
	if (!(await find('John 3', { timeout: 10_000 }))) { screenshot('goto'); fail('typing into Go To did nothing'); }
	shell('input keyevent 66');
	if (!(await find(/^Go to passage, currently .* 3$/, { timeout: 10_000 }))) { screenshot('goto'); fail('Go To did not open John 3'); }
	await sleep(1_000);
	alive('open a chapter');

	// Listen, then Previous/Next as fast as adb can tap — a burst of 16 in one shell.
	await tap('Listen');
	const previous = await find('Previous Verse', { timeout: 20_000 });
	const next = await find('Next Verse', { timeout: 5_000 });
	if (!previous || !next) { screenshot('listen'); fail('the Now Playing bar has no Previous/Next'); }
	await sleep(1_500);
	const [px, py] = center(previous);
	const [nx, ny] = center(next);
	const burst = [];
	for (let i = 0; i < 8; i++) burst.push(`input tap ${nx} ${ny}`, `input tap ${px} ${py}`, `input tap ${nx} ${ny}`);
	const began = Date.now();
	shell(burst.join('; '));
	log(`${burst.length} skips sent in ${Date.now() - began} ms`);
	await sleep(3_000);
	// The bar answers at once after the burst: the UI thread was never stuck.
	const t0 = Date.now();
	if (!(await find('Stop Listening', { timeout: 5_000 }))) { screenshot('skips'); fail('the Now Playing bar stopped answering after the skips'); }
	log(`UI answered ${Date.now() - t0} ms after the burst`);
	alive('Listen with fast skips');

	// Minimize: the bar becomes a pill naming the verse being read, and reading goes on.
	screenshot('listen-expanded');
	await tap('Minimize player');
	const pill = await find(/^Expand player, .+:\d+/, { timeout: 5_000 });
	if (!pill) { screenshot('minimize'); fail('Minimize player did not show the pill'); }
	if (await find('Stop Listening', { timeout: 1_000 })) { screenshot('minimize'); fail('the full bar stayed up after Minimize player'); }
	if (!(await find('Pause', { timeout: 3_000 }))) { screenshot('minimize'); fail('minimizing stopped the reading'); }
	log(`pill: ${pill.desc}`);
	screenshot('listen-minimized');
	await tap('Pause');
	if (!(await find('Play', { timeout: 5_000 }))) { screenshot('pill'); fail("the pill's Pause did not pause"); }
	await tap('Play');
	if (!(await find('Pause', { timeout: 10_000 }))) { screenshot('pill'); fail("the pill's Play did not play"); }
	alive('Listen minimized');
	await tap(/^Expand player, /);
	if (!(await find('Stop Listening', { timeout: 5_000 }))) { screenshot('expand'); fail('the pill did not open the bar'); }
	// A swipe down on the bar minimizes it too.
	const chevron = await find('Minimize player', { timeout: 3_000 });
	if (!chevron) fail('the bar has no Minimize player');
	const [cx, cy] = center(chevron);
	shell(`input swipe ${cx + 120} ${cy} ${cx + 120} ${cy + 260} 250`);
	if (!(await find(/^Expand player, /, { timeout: 5_000 }))) { screenshot('swipe'); fail('swiping the bar down did not minimize it'); }
	await tap(/^Expand player, /);
	alive('Listen expanded again');
	await tap('Stop Listening');
	if (await find(/^Expand player, /, { timeout: 1_500 })) { screenshot('stop'); fail('the pill outlived Stop'); }

	// The share card: select a verse, Share › Share Image…, then export it to the share sheet.
	const verse = nodes(dump()).find((n) => /^Verse \d+\./.test(n.desc) && n.bounds[1] > 400);
	if (!verse) fail('no verse on screen to select');
	tapAt(center(verse));
	await sleep(800);
	await tap('Share');
	await tap(/^Share Image/);
	if (!(await find(/^Preview: /, { timeout: 10_000 }))) { screenshot('share'); fail('the share card designer did not open'); }
	screenshot('share-card');
	// A ready-made style or three, then Customize › a strong shadow: the preview follows each tap.
	for (const style of ['Watercolor', 'Golden Hour', 'Linen']) {
		const tile = await find(style, { timeout: 5_000 });
		if (!tile) { screenshot('share-styles'); fail(`the ${style} style is not in the Styles row`); }
		tapAt(center(tile));
		await sleep(1_500);
		screenshot(`share-style-${style.toLowerCase().replace(/ /g, '-')}`);
	}
	await tap('Customize');
	// The fine controls open beneath the Styles; scroll the controls (not the pinned preview) to the shadow.
	let strong = await find('Strong', { timeout: 2_000 });
	for (let i = 0; i < 5 && !strong; i++) {
		const [w, h] = (shell('wm size').match(/(\d+)x(\d+)\s*$/) || [0, 1080, 2400]).slice(1).map(Number);
		shell(`input swipe ${w / 2} ${Math.round(h * 0.85)} ${w / 2} ${Math.round(h * 0.6)} 400`);
		await sleep(800);
		strong = await find('Strong', { timeout: 2_000 });
	}
	if (!strong) { screenshot('missing'); fail('Customize has no Strong shadow'); }
	tapAt(center(strong));
	await sleep(1_000);
	screenshot('share-customized');
	alive('share styles');
	const exportButton = nodes(dump()).find((n) => n.desc === 'Share Image' && n.clickable);
	if (exportButton) {
		tapAt(center(exportButton));
		await sleep(3_000);
		screenshot('share-sheet');
		shell('input keyevent 4');
		await sleep(1_000);
	}
	alive('share card');
	await tap('Done');
	const clear = await find('Clear Selection', { timeout: 3_000 });
	if (clear) { tapAt(center(clear)); await sleep(600); }

	// Import a 48 MP photo as a slide: Notes › Scan Slide › Choose from Photos.
	adb('push', bigPhoto(), '/sdcard/Pictures/sa-smoke-slide.jpg');
	shell('am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/sa-smoke-slide.jpg');
	await sleep(1_500);
	const before = memory('before-import');
	await tap('Notes');
	await tap('Scan Slide');
	await tap('Choose from Photos');
	const photo = await find(/^Photo taken on /, { timeout: 15_000 });
	if (!photo) { screenshot('picker'); fail('the photo picker showed no photo'); }
	tapAt(center(photo));
	if (!(await find(/^(Create Note|Add to Note|New Note)$/, { timeout: 40_000 }))) { screenshot('import'); fail('the slide review never opened'); }
	await sleep(4_000); // the recognizer reads it
	screenshot('slide-review');
	const after = memory('slide-review');
	writeFileSync(join(OUT, 'memory.json'), JSON.stringify({ before, after }, null, 2));
	alive('image import');
	shell('input keyevent 4');
	await sleep(800);
	try { shell('rm /sdcard/Pictures/sa-smoke-slide.jpg'); } catch { /* */ }
}

main().catch((e) => { console.error(`✗ ${e.message}`); process.exit(1); });
