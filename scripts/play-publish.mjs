#!/usr/bin/env node
/**
 * play-publish.mjs — Google Play Developer API for the release workflow (.github/workflows/android.yml).
 *
 *   node scripts/play-publish.mjs plan
 *       Read-only. Opens an edit, lists every track and every uploaded bundle, prints them, derives
 *       the next versionCodes (one sequential counter: phone = next, Wear OS = next + 1), deletes the edit. With
 *       --check-tracks a,b,c it also fails if a CUSTOM track name is not one Play lists (so a wrong
 *       Wear closed-track id fails in seconds, before a 30-minute build).
 *
 *   node scripts/play-publish.mjs publish --version-name 1.1.0 \
 *       --phone-aab app-release.aab --phone-code 3 --phone-tracks internal,alpha \
 *       [--wear-aab wear-release.aab --wear-code 4 --wear-tracks "wear:internal,wear:Wear OS closed testing"] \
 *       [--status completed|inProgress|draft] [--user-fraction 0.2] [--notes-dir DIR] [--dry-run]
 *       ONE edit: upload both bundles, assign each to its tracks (the same versionCode may sit on
 *       several tracks — nothing is uploaded twice), attach release notes, validate, commit.
 *       All-or-nothing: if any track assignment is refused, nothing lands and a re-run is safe.
 *       --dry-run uploads nothing and commits nothing: it validates the plan against Play's track
 *       list and prints what it would do.
 *
 * Release notes: --notes-dir holds `whatsnew-<language>` files (the workflow's Python step builds
 * them from android/play-metadata*.md `## release_notes` and enforces Play's 500-character cap).
 *
 * Credentials: env PLAY_SERVICE_ACCOUNT_JSON (the JSON key's contents), or --key-file <path>.
 * Auth, the API client and the edit lifecycle live in scripts/play-api.mjs (shared with play-listing.mjs).
 * Package: --package (default com.blainemiller.scripturealone).
 *
 * Track ids (https://developers.google.com/android-publisher/tracks): phone tracks are
 * production / beta (open) / alpha (closed, "Alpha" in the Console) / internal. Form-factor tracks
 * are prefixed: Wear OS → wear:production, wear:beta, wear:internal, and a custom closed track
 * named N in the Console is `wear:N`. (The API docs call internal "qa"; this app's tracks.list
 * answers "internal" and "wear:internal" — verified 2026-09-23 — so the listing is the authority.)
 */
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { UPLOAD, die, fail, log, summary, output, useServiceAccount, api, base, openEdit, deleteEdit } from './play-api.mjs';

// The Wear OS app numbers from 1,000,000 up, the phone below it. A single sequential counter was
// tried (2026-09-23) and can't work: Play refuses a watch build numbered below the one already on
// its track ("does not allow any existing users to upgrade"), and Wear 1,000,004 is there.
const WEAR_FLOOR = 1_000_000;

const list = (s) => (s || '').split(',').map((x) => x.trim()).filter(Boolean);

function parseArgs(argv) {
	const a = { cmd: argv[2], pkg: 'com.blainemiller.scripturealone', status: 'completed', dryRun: false, checkTracks: [] };
	for (let i = 3; i < argv.length; i++) {
		const v = () => argv[++i];
		switch (argv[i]) {
			case '--package': a.pkg = v(); break;
			case '--key-file': a.keyFile = v(); break;
			case '--version-name': a.versionName = v(); break;
			case '--phone-aab': a.phoneAab = v(); break;
			case '--phone-code': a.phoneCode = Number(v()); break;
			case '--phone-tracks': a.phoneTracks = list(v()); break;
			case '--wear-aab': a.wearAab = v(); break;
			case '--wear-code': a.wearCode = Number(v()); break;
			case '--wear-tracks': a.wearTracks = list(v()); break;
			case '--status': a.status = v(); break;
			case '--user-fraction': a.userFraction = v(); break;
			case '--notes-dir': a.notesDir = v(); break;
			case '--check-tracks': a.checkTracks = list(v()); break;
			case '--dry-run': a.dryRun = true; break;
			default: die(`unknown arg ${argv[i]}`);
		}
	}
	if (!['plan', 'publish'].includes(a.cmd)) die('usage: play-publish.mjs plan|publish [options] — see the header');
	if (!['completed', 'inProgress', 'draft', 'halted'].includes(a.status)) die(`--status ${a.status} is not a Play release status`);
	return a;
}

async function snapshot(a, editId) {
	const tracks = (await api('GET', `${base(a)}/edits/${editId}/tracks`))?.tracks || [];
	const bundles = (await api('GET', `${base(a)}/edits/${editId}/bundles`))?.bundles || [];
	// Every code Play knows: uploaded bundles AND anything a track references (APKs, older uploads).
	const codes = new Set(bundles.map((b) => Number(b.versionCode)));
	for (const t of tracks) for (const r of t.releases || []) for (const c of r.versionCodes || []) codes.add(Number(c));
	return { tracks, bundles, codes: [...codes].filter(Number.isFinite).sort((x, y) => x - y) };
}

function describeTracks(tracks) {
	for (const t of tracks) {
		const rel = (t.releases || []).map((r) => `${r.name || '?'} [${(r.versionCodes || []).join(',')}] ${r.status}${r.userFraction ? ` ${r.userFraction}` : ''}`).join('; ');
		log(`track "${t.track}": ${rel || '(empty)'}`);
	}
}

// Every target track must be one Play lists. tracks.list returns the default tracks even when
// empty (verified 2026-09-23: production, beta, alpha, internal, wear:production, wear:beta,
// wear:internal, plus the custom "wear:Wear OS closed testing"), so an unlisted name is a typo.
function checkTracks(tracks, wanted) {
	const have = new Set(tracks.map((t) => t.track));
	for (const w of wanted) {
		if (have.has(w)) continue;
		fail(`Play has no track "${w}". Tracks Play lists: ${[...have].map((t) => `"${t}"`).join(', ') || '(none)'} — fix the PLAY_* track variable (docs/RELEASING.md)`);
	}
}

function nextCodes(codes) {
	// Phone and watch share the package, so their codes must differ: the phone counts up below
	// WEAR_FLOOR, the watch above it, each from the highest code Play has seen in its range.
	const phone = codes.filter((c) => c < WEAR_FLOOR);
	const watch = codes.filter((c) => c >= WEAR_FLOOR);
	const phoneNext = Math.max(phone.length ? Math.max(...phone) + 1 : 1, 3);   // 1 and 2 are burned
	const wearNext = watch.length ? Math.max(...watch) + 1 : WEAR_FLOOR + 1;
	return { phoneNext, wearNext };
}

function releaseNotes(dir) {
	if (!dir) return [];
	if (!existsSync(dir)) fail(`--notes-dir ${dir} does not exist`);
	return readdirSync(dir).filter((f) => f.startsWith('whatsnew-')).sort().map((f) => ({
		language: f.slice('whatsnew-'.length), text: readFileSync(join(dir, f), 'utf8').trim(),
	})).filter((n) => n.text);
}

async function uploadBundle(a, editId, file, expectCode) {
	if (!existsSync(file)) fail(`bundle ${file} not found`);
	const size = statSync(file).size;
	log(`uploading ${file} (${(size / 1048576).toFixed(1)} MB)…`);
	// Resumable upload: a phone bundle with the Bible packs is well over 100 MB.
	const start = await api('POST', `${UPLOAD}/${encodeURIComponent(a.pkg)}/edits/${editId}/bundles?uploadType=resumable&ackBundleInstallationWarning=true`, null, {
		headers: { 'x-upload-content-type': 'application/octet-stream', 'x-upload-content-length': String(size) }, raw: 'response',
	});
	const location = start.headers.get('location');
	if (!location) fail('Play did not return a resumable upload location');
	let bundle = null;
	for (let attempt = 0; attempt < 3 && !bundle; attempt++) {
		try {
			const res = await api('PUT', location, readFileSync(file), { headers: { 'content-type': 'application/octet-stream' }, raw: 'response' });
			bundle = await res.json();
		} catch (e) {
			if (attempt === 2 || (e.status && e.status < 500)) throw e;
			log(`upload attempt ${attempt + 1} failed (${e.message.slice(0, 120)}) — retrying`);
		}
	}
	if (Number(bundle.versionCode) !== expectCode) fail(`${file} carries versionCode ${bundle.versionCode}, expected ${expectCode} — the build did not take -PsaVersionCode/-PsaWearVersionCode`);
	log(`uploaded versionCode ${bundle.versionCode} (sha256 ${String(bundle.sha256 || '').slice(0, 12)}…)`);
	return bundle;
}

async function main() {
	const a = parseArgs(process.argv);
	useServiceAccount(a.keyFile);
	const editId = await openEdit(a);
	let committed = false;
	try {
		const snap = await snapshot(a, editId);
		describeTracks(snap.tracks);
		const { phoneNext, wearNext } = nextCodes(snap.codes);

		if (a.cmd === 'plan') {
			log(`versionCodes Play knows: ${snap.codes.join(', ') || '(none)'}`);
			log(`next phone versionCode ${phoneNext}, next Wear OS versionCode ${wearNext}`);
			checkTracks(snap.tracks, a.checkTracks);
			output('phone_code', phoneNext); output('wear_code', wearNext);
			return;
		}

		// publish
		if (!a.versionName) fail('--version-name required');
		if (!a.phoneAab || !a.phoneCode || !a.phoneTracks?.length) fail('--phone-aab, --phone-code and --phone-tracks are required');
		const wear = Boolean(a.wearAab);
		if (wear && (!a.wearCode || !a.wearTracks?.length)) fail('--wear-aab needs --wear-code and --wear-tracks');
		if (a.phoneCode >= WEAR_FLOOR || (wear && a.wearCode < WEAR_FLOOR)) fail('the phone numbers below 1,000,000 and Wear OS from 1,000,000 up — the plan step picks both');
		if (wear && a.wearCode === a.phoneCode) fail('the phone and Wear OS bundles need different versionCodes');
		for (const [c, what] of [[a.phoneCode, 'phone'], ...(wear ? [[a.wearCode, 'Wear OS']] : [])]) {
			if (snap.codes.includes(c)) fail(`${what} versionCode ${c} was already used on Play — codes can never be reused; re-run the workflow so the plan step picks a fresh one`);
		}
		for (const t of a.phoneTracks) if (t.includes(':')) fail(`phone track "${t}" is a form-factor track — the phone bundle belongs on phone tracks`);
		if (wear) for (const t of a.wearTracks) if (!t.startsWith('wear:')) fail(`Wear OS track "${t}" must be a wear: track — Play refuses a watch bundle on a phone track`);
		checkTracks(snap.tracks, [...a.phoneTracks, ...(wear ? a.wearTracks : [])]);

		const notes = releaseNotes(a.notesDir);
		log(`release notes: ${notes.map((n) => `${n.language}:${[...n.text].length}`).join(', ') || '(none)'}`);
		const release = (code) => ({
			name: a.versionName,
			versionCodes: [String(code)],
			status: a.status,
			...(a.status === 'inProgress' && a.userFraction ? { userFraction: Number(a.userFraction) } : {}),
			...(notes.length ? { releaseNotes: notes } : {}),
		});
		const plan = [
			...a.phoneTracks.map((t) => [t, a.phoneCode]),
			...(wear ? a.wearTracks.map((t) => [t, a.wearCode]) : []),
		];

		if (a.dryRun) {
			for (const [t, c] of plan) log(`(dry run) would put ${a.versionName} (${c}) on "${t}" as ${a.status}`);
			return;
		}

		await uploadBundle(a, editId, a.phoneAab, a.phoneCode);
		if (wear) await uploadBundle(a, editId, a.wearAab, a.wearCode);
		for (const [t, c] of plan) {
			try {
				await api('PUT', `${base(a)}/edits/${editId}/tracks/${encodeURIComponent(t)}`, { track: t, releases: [release(c)] });
			} catch (e) {
				const hint = /draft app/i.test(e.message) ? ' — the app is still a draft on Play: set repo variable PLAY_RELEASE_STATUS=draft' : '';
				fail(`assigning ${c} to "${t}" failed: ${e.message}${hint}`);
			}
			log(`"${t}" ← ${a.versionName} (${c}) ${a.status}`);
		}
		await api('POST', `${base(a)}/edits/${editId}:validate`);
		await api('POST', `${base(a)}/edits/${editId}:commit`);
		committed = true;
		const line = `${a.versionName}: phone ${a.phoneCode} → ${a.phoneTracks.join(' + ')}${wear ? `; Wear OS ${a.wearCode} → ${a.wearTracks.join(' + ')}` : ''} (${a.status})`;
		console.log(`✓ Play: ${line}`);
		summary(`- ✅ Play: ${line}`);
	} finally {
		if (!committed) await deleteEdit(a, editId);
	}
}

main().catch((e) => die(e.message));
