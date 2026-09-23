#!/usr/bin/env node
/**
 * asc-autosubmit.mjs — ship a tagged Scripture Alone release to the App Store from CI, using the
 * build Xcode Cloud made for THAT commit. Ported from Haven's Scripts/asc-autosubmit.mjs (same
 * supersede, tag-refusal fallback, commit pin and 10-minute push-run poll), plus two things Haven
 * does not need: Apple-hosted asset packs and the ci_post_clone.sh nothing-to-build guard.
 *
 *   scripts/asc-autosubmit.mjs --version 1.1.0 --commit <sha> [--tag v1.1.0]
 *       [--platforms IOS] [--wait-build 120] [--wait-process 45] [--notes-dir .]
 *       [--testflight-only] [--no-submit] [--dry-run] [--resubmit]
 *
 * What it does, in order (every step idempotent — re-running after a hiccup is safe):
 *   1. Find the Xcode Cloud run that built `--commit` — or an iOS-EQUIVALENT commit (see below).
 *      If none, wait 10 minutes for the push-triggered run, then START one on the tag, falling
 *      back to branch main when XCC refuses a tag start.
 *   2. Wait for the run to SUCCEED, collect its builds, wait for each to finish PROCESSING → VALID
 *      (confirmed on /v1/builds/{id}, never the list).
 *      --testflight-only (rc tags) stops here: a VALID build is already on TestFlight.
 *   3. Per platform: find-or-create the editable App Store version (superseding an OLDER queued
 *      version); set What's New + promotional text per locale from appstore-metadata[.<locale>].md
 *      (en-US must START with the version); attach the build; answer export compliance if asked.
 *   4. Asset packs: every non-archived Background Assets pack whose newest version is COMPLETE and
 *      whose App Store release is PREPARE_FOR_SUBMISSION / READY_FOR_REVIEW / *REJECTED rides on
 *      the SAME reviewSubmission as the version (backgroundAssetVersion items). Never more than 10
 *      per submission — more fails loudly, BEFORE anything is canceled. A pack still processing
 *      fails the run (the version would ship without data it needs). Packs are never archived.
 *   5. Submit via reviewSubmissions. Already WAITING_FOR_REVIEW / IN_REVIEW with this version is
 *      success; an UNRESOLVED_ISSUES submission is reported, never silently reused.
 *
 * THE NOTHING-TO-BUILD GUARD. ci_scripts/ci_post_clone.sh deliberately exits 1 when a commit only
 * touches android/, docs/, .claude/ or *.md — so a "FAILED" Xcode Cloud run can be a deliberate
 * skip, and its commit never gets a build. Two commits are iOS-EQUIVALENT when every path that
 * differs between them is one that guard ignores (or .github/): the binary one builds is the
 * binary the other would. A tagged commit's build may therefore come from an equivalent commit;
 * a skipped run is never taken as the build. If no equivalent commit has a build, push an EMPTY
 * commit to main (an empty diff passes the guard) and re-run.
 *
 * Auth (CI): env ASC_API_KEY_ID, ASC_API_ISSUER_ID, ASC_API_KEY_P8 (the .p8 contents, raw PEM or
 * base64). Locally: ids from ~/.rocket/config.json and the key at
 * ~/.appstoreconnect/private_keys/AuthKey_<id>.p8. The key needs the App Manager role.
 * --dry-run is read-only (GETs only): it finds the run and the builds, checks the notes and plans
 * the asset packs, and writes nothing.
 */
import { readFile } from 'node:fs/promises';
import { existsSync, readFileSync, appendFileSync } from 'node:fs';
import { createPrivateKey, sign } from 'node:crypto';
import { join } from 'node:path';
import { homedir } from 'node:os';
import { execFileSync } from 'node:child_process';

const API = 'https://api.appstoreconnect.apple.com';
const die = (m) => { console.error(`✗ ${m}`); summary(`❌ ${m}`); process.exit(1); };
const log = (m) => console.log(`• ${m}`);
const b64url = (b) => Buffer.from(b).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const summary = (line) => { if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, line + '\n'); };

function parseArgs(argv) {
	const a = { bundleId: 'com.blainemiller.ScriptureAlone', appId: '6813729762', workflowId: '4977FB75-C998-4E7B-B3A5-34F85CDF19B2', platforms: ['IOS'], waitBuild: 120, waitProcess: 45, notesDir: '.', submit: true, dryRun: false, testflightOnly: false, resubmit: false };
	for (let i = 2; i < argv.length; i++) {
		switch (argv[i]) {
			case '--bundle-id': a.bundleId = argv[++i]; break;
			case '--app-id': a.appId = argv[++i]; break;
			case '--workflow-id': a.workflowId = argv[++i]; break;
			case '--version': a.version = argv[++i]; break;
			case '--commit': a.commit = argv[++i]; break;
			case '--tag': a.tag = argv[++i]; break;
			case '--platforms': a.platforms = argv[++i].split(',').map((s) => s.trim().toUpperCase()).filter(Boolean); break;
			case '--wait-build': a.waitBuild = Number(argv[++i]); break;
			case '--wait-process': a.waitProcess = Number(argv[++i]); break;
			case '--testflight-only': a.testflightOnly = true; break;
			case '--notes-dir': a.notesDir = argv[++i]; break;
			case '--no-submit': a.submit = false; break;
			case '--dry-run': a.dryRun = true; break;
			case '--resubmit': a.resubmit = true; break;
			default: die(`unknown arg ${argv[i]}`);
		}
	}
	if (!a.version) die('--version required (X.Y.Z — the App Store marketing version)');
	if (!/^\d+\.\d+\.\d+$/.test(a.version)) die(`--version ${a.version} is not plain X.Y.Z (an rc never reaches the App Store)`);
	if (!a.commit) die('--commit required (the full sha the tag points at)');
	if (!a.tag) a.tag = `v${a.version}`;
	for (const p of a.platforms) if (!['IOS', 'MAC_OS', 'TV_OS', 'VISION_OS'].includes(p)) die(`unknown platform ${p}`);
	return a;
}

// ── auth ──────────────────────────────────────────────────────────────────────────────
function creds() {
	let keyId = process.env.ASC_API_KEY_ID, issuer = process.env.ASC_API_ISSUER_ID;
	let pem = process.env.ASC_API_KEY_P8 || '';
	if (pem && !pem.includes('-----BEGIN')) pem = Buffer.from(pem.trim(), 'base64').toString('utf8');
	if (!keyId || !issuer) {
		const cfg = join(homedir(), '.rocket/config.json');
		if (existsSync(cfg)) { const j = JSON.parse(readFileSync(cfg, 'utf8')); keyId = keyId || j.ascKeyId; issuer = issuer || j.ascIssuerId; }
	}
	if (!keyId || !issuer) die('No ASC creds — set ASC_API_KEY_ID + ASC_API_ISSUER_ID (+ ASC_API_KEY_P8 in CI)');
	if (!pem) {
		const p8 = join(homedir(), '.appstoreconnect/private_keys', `AuthKey_${keyId}.p8`);
		if (!existsSync(p8)) die(`Missing ASC_API_KEY_P8 and ${p8}`);
		pem = readFileSync(p8, 'utf8');
	}
	return { keyId, issuer, pem };
}

let CREDS = null, TOKEN = null, TOKEN_AT = 0;
function token() {
	// Re-mint every 15 minutes: the JWT lives 20 and this script waits for hours.
	if (TOKEN && Date.now() - TOKEN_AT < 15 * 60_000) return TOKEN;
	const { keyId, issuer, pem } = CREDS;
	const now = Math.floor(Date.now() / 1000);
	const input = `${b64url(JSON.stringify({ alg: 'ES256', kid: keyId, typ: 'JWT' }))}.` +
		`${b64url(JSON.stringify({ iss: issuer, iat: now, exp: now + 19 * 60, aud: 'appstoreconnect-v1' }))}`;
	const sig = sign('sha256', Buffer.from(input), { key: createPrivateKey(pem), dsaEncoding: 'ieee-p1363' });
	TOKEN = `${input}.${b64url(sig)}`; TOKEN_AT = Date.now();
	return TOKEN;
}

// --dry-run is enforced HERE, not only by each caller remembering: anything but a GET throws.
let READ_ONLY = false;
async function api(method, path, body, { retries = 3 } = {}) {
	if (READ_ONLY && method !== 'GET') throw new Error(`dry run: refusing ${method} ${path}`);
	for (let attempt = 0; ; attempt++) {
		const res = await fetch(path.startsWith('http') ? path : `${API}${path}`, {
			method, headers: { authorization: `Bearer ${token()}`, ...(body ? { 'content-type': 'application/json' } : {}) },
			body: body ? JSON.stringify(body) : undefined,
		}).catch((e) => ({ ok: false, status: 0, text: async () => String(e) }));
		if (res.status === 204) return null;
		const text = await res.text();
		let json = null; try { json = text ? JSON.parse(text) : null; } catch { /* */ }
		if (res.ok) return json;
		const detail = json?.errors?.map((e) => `${e.title}: ${e.detail}${e.meta?.associatedErrors ? ' ' + JSON.stringify(e.meta.associatedErrors) : ''}`).join('; ') || text;
		// Transient: network, 429, 5xx. Everything else is the caller's problem.
		if ((res.status === 0 || res.status === 429 || res.status >= 500) && attempt < retries) { await sleep(5000 * (attempt + 1)); continue; }
		const err = new Error(`${method} ${path} → ${res.status}: ${detail}`); err.status = res.status; throw err;
	}
}

async function paged(path, max = 5) {
	const out = [];
	let next = path;
	for (let i = 0; next && i < max; i++) {
		const r = await api('GET', next);
		out.push(...(r?.data || []));
		next = r?.links?.next || null;
	}
	return out;
}

// ── 1. the Xcode Cloud run for this commit ──────────────────────────────────────────────
async function ciProductFor(appId) {
	const r = await api('GET', `/v1/ciProducts?filter[productType]=APP&limit=200&include=app&fields[ciProducts]=name,app&fields[apps]=bundleId`);
	return (r.data || []).find((p) => p.relationships?.app?.data?.id === appId) || null;
}

async function archiveWorkflow(productId, pinnedId) {
	// Scripture Alone's workflow is "Main" (pinned by id). Fall back to discovery only if the pin
	// is gone (renamed workflows keep their id; a deleted-and-recreated one does not).
	if (pinnedId) {
		const w = await api('GET', `/v1/ciWorkflows/${pinnedId}?fields[ciWorkflows]=name,isEnabled,actions,branchStartCondition`).catch(() => null);
		if (w?.data?.attributes?.isEnabled) return w.data;
		log(`pinned Xcode Cloud workflow ${pinnedId} not found or disabled — discovering the Archive workflow`);
	}
	const r = await api('GET', `/v1/ciProducts/${productId}/workflows?limit=50&fields[ciWorkflows]=name,isEnabled,actions,branchStartCondition`);
	const wfs = (r.data || []).filter((w) => w.attributes?.isEnabled && (w.attributes?.actions || []).some((x) => x.actionType === 'ARCHIVE'));
	if (!wfs.length) die('no enabled Xcode Cloud workflow with an Archive action — nothing can produce an App Store build');
	// Prefer the one that builds `main` on push (that's the TestFlight pipeline); else the first.
	return wfs.find((w) => (w.attributes?.branchStartCondition?.source?.patterns || []).some((p) => p.pattern === 'main')) || wfs[0];
}

// ── the nothing-to-build guard (ci_scripts/ci_post_clone.sh) ────────────────────────────
// Paths that guard ignores — keep in step with its RELEVANT grep — plus .github/ (workflows never
// reach the binary). Two commits whose diff is only these build the same iOS app.
const GUARD_IGNORED = /^android\/|(^|\/)(docs|\.claude)\/|\.md$|^\.github\//;
const git = (...a) => execFileSync('git', a, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
function haveCommit(sha) {
	try { git('cat-file', '-e', `${sha}^{commit}`); return true; } catch { /* not local */ }
	try { git('fetch', '-q', 'origin', sha); git('cat-file', '-e', `${sha}^{commit}`); return true; } catch { return false; }
}
const EQUIV = new Map();
function iosEquivalent(a, b) {
	if (!a || !b) return false;
	if (a.toLowerCase() === b.toLowerCase()) return true;
	const key = `${a}..${b}`;
	if (EQUIV.has(key)) return EQUIV.get(key);
	let ok = false;
	if (haveCommit(a) && haveCommit(b)) {
		const changed = git('diff', '--name-only', a, b).split('\n').filter(Boolean);
		ok = changed.every((f) => GUARD_IGNORED.test(f));
	}
	EQUIV.set(key, ok);
	return ok;
}
// Does this commit's OWN diff (vs its parent) trip the guard? Then its push run "fails" on purpose.
function guardSkips(sha) {
	try {
		const changed = git('diff', '--name-only', `${sha}~1`, sha).split('\n').filter(Boolean);
		return changed.length > 0 && changed.every((f) => /^android\/|(^|\/)(docs|\.claude)\/|\.md$/.test(f));
	} catch { return false; }
}

const runSha = (r) => (r?.attributes?.sourceCommit?.commitSha || '').toLowerCase();
const usable = (r) => r && (r.attributes.executionProgress !== 'COMPLETE' || r.attributes.completionStatus === 'SUCCEEDED');

// The run that builds `sha`: its own run if that is running or succeeded; otherwise the newest
// running/succeeded run of an iOS-equivalent commit. A FAILED run is never the build — for a
// guard-skipped commit that "failure" is the skip itself.
async function findRun(productId, sha) {
	const runs = await paged(`/v1/ciProducts/${productId}/buildRuns?limit=200&sort=-number&fields[ciBuildRuns]=number,sourceCommit,executionProgress,completionStatus,createdDate,startReason`, 3);
	const own = runs.find((r) => runSha(r) === sha.toLowerCase()) || null;
	if (usable(own)) return own;
	const equiv = runs.filter((r) => runSha(r) && runSha(r) !== sha.toLowerCase() && usable(r))
		.sort((x, y) => Number(y.attributes.number) - Number(x.attributes.number))
		.find((r) => iosEquivalent(runSha(r), sha));
	if (equiv) {
		log(`commit ${sha.slice(0, 10)}${own ? ` (its run #${own.attributes.number} ${own.attributes.completionStatus}${guardSkips(sha) ? ' — the nothing-to-build guard' : ''})` : ''} builds the same iOS app as ${runSha(equiv).slice(0, 10)} — using its run #${equiv.attributes.number}`);
		return equiv;
	}
	return own;
}

async function gitReference(workflowId, { tag, commit }) {
	const repo = await api('GET', `/v1/ciWorkflows/${workflowId}/repository?fields[scmRepositories]=repositoryName`);
	const repoId = repo?.data?.id;
	if (!repoId) die('workflow has no SCM repository');
	// XCC learns about a new tag from the provider with a little lag — poll for it.
	for (let i = 0; i < 12; i++) {
		const refs = await paged(`/v1/scmRepositories/${repoId}/gitReferences?limit=200&fields[scmGitReferences]=name,canonicalName,kind,isDeleted`, 5);
		const t = refs.find((x) => x.attributes?.kind === 'TAG' && !x.attributes?.isDeleted && (x.attributes?.name === tag || x.attributes?.canonicalName === `refs/tags/${tag}`));
		if (t) return { ref: t, via: `tag ${tag}` };
		// Fallback: main's tip IS this commit → build main, which is the same tree.
		let mainTip = null;
		try { mainTip = execFileSync('git', ['rev-parse', 'origin/main'], { encoding: 'utf8' }).trim(); } catch { /* no checkout */ }
		if (mainTip && iosEquivalent(mainTip, commit)) {
			const m = refs.find((x) => x.attributes?.kind === 'BRANCH' && !x.attributes?.isDeleted && x.attributes?.name === 'main');
			if (m) return { ref: m, via: 'branch main (its tip is this commit)' };
		}
		log(`Xcode Cloud hasn't seen tag ${tag} yet — waiting (${i + 1}/12)…`);
		await sleep(30_000);
	}
	die(`Xcode Cloud never listed tag ${tag} and main's tip is not ${commit.slice(0, 10)} — start a build of the tag in Xcode Cloud by hand and re-run`);
}

async function startRun(workflowId, ref) {
	const r = await api('POST', '/v1/ciBuildRuns', {
		data: {
			type: 'ciBuildRuns',
			relationships: {
				workflow: { data: { type: 'ciWorkflows', id: workflowId } },
				sourceBranchOrTag: { data: { type: 'scmGitReferences', id: ref.id } },
			},
		},
	});
	return r.data;
}

async function waitRun(runId, minutes) {
	const deadline = Date.now() + minutes * 60_000;
	for (;;) {
		const r = await api('GET', `/v1/ciBuildRuns/${runId}?fields[ciBuildRuns]=number,executionProgress,completionStatus,sourceCommit`);
		const at = r.data.attributes;
		if (at.executionProgress === 'COMPLETE') return r.data;
		if (Date.now() >= deadline) die(`Xcode Cloud run #${at.number} still ${at.executionProgress} after ${minutes} min`);
		log(`run #${at.number}: ${at.executionProgress}… (${Math.round((deadline - Date.now()) / 60_000)} min left)`);
		await sleep(60_000);
	}
}

async function runBuilds(runId) {
	// The run→builds relationship comes back as bare ids (every attribute null), so hydrate
	// each build from its own resource — which is also the authoritative processingState.
	const ids = ((await api('GET', `/v1/ciBuildRuns/${runId}/builds?limit=50`)).data || []).map((b) => b.id);
	const out = [];
	for (const id of ids) {
		const r = await api('GET', `/v1/builds/${id}?include=preReleaseVersion&fields[builds]=version,processingState,uploadedDate,usesNonExemptEncryption,preReleaseVersion&fields[preReleaseVersions]=platform,version`);
		const p = (r.included || []).find((x) => x.type === 'preReleaseVersions')?.attributes || {};
		out.push({ id, number: r.data.attributes.version, state: r.data.attributes.processingState, platform: p.platform, marketing: p.version, usesNonExemptEncryption: r.data.attributes.usesNonExemptEncryption });
	}
	return out;
}

async function waitValid(build, minutes) {
	const deadline = Date.now() + minutes * 60_000;
	for (;;) {
		const r = await api('GET', `/v1/builds/${build.id}?fields[builds]=version,processingState,usesNonExemptEncryption`);
		const at = r.data.attributes;
		if (at.processingState === 'VALID') return { ...build, state: 'VALID', usesNonExemptEncryption: at.usesNonExemptEncryption };
		if (at.processingState === 'FAILED' || at.processingState === 'INVALID') die(`${build.platform} build ${build.number} is ${at.processingState} — Xcode Cloud uploaded a binary ASC rejected`);
		if (Date.now() >= deadline) die(`${build.platform} build ${build.number} still ${at.processingState} after ${minutes} min`);
		log(`${build.platform} build ${build.number}: ${at.processingState}…`);
		await sleep(60_000);
	}
}

// ── 3. version, notes, attach ──────────────────────────────────────────────────────────
const EDITABLE = new Set(['PREPARE_FOR_SUBMISSION', 'DEVELOPER_REJECTED', 'REJECTED', 'METADATA_REJECTED', 'INVALID_BINARY']);
const LIVE_OR_QUEUED = new Set(['WAITING_FOR_REVIEW', 'IN_REVIEW', 'PENDING_DEVELOPER_RELEASE', 'READY_FOR_SALE', 'READY_FOR_DISTRIBUTION', 'PROCESSING_FOR_DISTRIBUTION']);

async function ensureVersion(appId, platform, version, opts) {
	const { dryRun } = opts;
	const versions = (await api('GET', `/v1/apps/${appId}/appStoreVersions?filter[platform]=${platform}&limit=50&fields[appStoreVersions]=versionString,appStoreState`)).data || [];
	const same = versions.find((v) => v.attributes.versionString === version);
	if (same && LIVE_OR_QUEUED.has(same.attributes.appStoreState)) {
		// --resubmit: the SAME version is queued (not live) — pull it back so a fixed build can
		// replace the queued one. Never touches live/pending-release states, and the platform
		// filter keeps the other platform's queued submission untouched.
		const RESUBMITTABLE = new Set(['WAITING_FOR_REVIEW', 'READY_FOR_REVIEW', 'IN_REVIEW']);
		if (!(opts.resubmit && RESUBMITTABLE.has(same.attributes.appStoreState)))
			return { version: same, state: same.attributes.appStoreState, done: true };
		await opts.beforeCancel?.();
		log(`${platform}: --resubmit — canceling the queued review of ${version} (${same.attributes.appStoreState})`);
		if (!dryRun) {
			const subs = (await api('GET', `/v1/reviewSubmissions?filter[app]=${appId}&filter[platform]=${platform}&filter[state]=READY_FOR_REVIEW,WAITING_FOR_REVIEW,IN_REVIEW&fields[reviewSubmissions]=state&limit=10`)).data || [];
			for (const sub of subs) {
				await api('PATCH', `/v1/reviewSubmissions/${sub.id}`, { data: { type: 'reviewSubmissions', id: sub.id, attributes: { canceled: true } } })
					.catch((e) => log(`${platform}: cancel of submission ${sub.id} → ${e.message || e} (continuing)`));
			}
			let st = same.attributes.appStoreState;
			for (let i = 0; i < 24; i++) {
				await new Promise((r) => setTimeout(r, 5000));
				const fresh = await api('GET', `/v1/appStoreVersions/${same.id}?fields[appStoreVersions]=appStoreState`).catch(() => null);
				st = fresh?.data?.attributes?.appStoreState || st;
				if (EDITABLE.has(st)) break;
			}
			if (!EDITABLE.has(st)) die(`${platform}: canceled the review but ${version} is still ${st} after 2min — check App Store Connect and re-run`);
			log(`${platform}: ${version} back to ${st} — the new build will be attached`);
			return { version: same, state: st };
		}
		return { version: same, state: 'PREPARE_FOR_SUBMISSION' };
	}
	if (same && EDITABLE.has(same.attributes.appStoreState)) return { version: same, state: same.attributes.appStoreState };
	if (same) die(`${platform} ${version} exists but is ${same.attributes.appStoreState} — neither editable nor shipping; sort it out in App Store Connect`);
	// One editable slot per platform. A stale draft with a different number that has no
	// build attached is just an abandoned number — rename it (its notes get rewritten below).
	const other = versions.find((v) => EDITABLE.has(v.attributes.appStoreState));
	if (other) {
		const b = await api('GET', `/v1/appStoreVersions/${other.id}/build?fields[builds]=version`).catch(() => null);
		if (b?.data) die(`${platform}: the editable slot is taken by ${other.attributes.versionString} (${other.attributes.appStoreState}) with build ${b.data.attributes.version} attached — ASC allows one editable version per platform. Submit or remove it in App Store Connect, then re-run.`);
		log(`${platform}: renaming abandoned draft ${other.attributes.versionString} (${other.attributes.appStoreState}, no build) → ${version}`);
		if (!dryRun) await api('PATCH', `/v1/appStoreVersions/${other.id}`, { data: { type: 'appStoreVersions', id: other.id, attributes: { versionString: version } } });
		return { version: { ...other, attributes: { ...other.attributes, versionString: version } }, state: other.attributes.appStoreState };
	}
	// SUPERSEDE: a version QUEUED for review (not yet live) also occupies the slot — POSTing a
	// new version 409s "cannot create a new version in the current state". When that queued
	// version is OLDER than the target, this release supersedes it: cancel its pending review
	// submission, wait for the version to fall back to an editable state, and rename it in
	// place (its notes are rewritten and the new build attached by the steps that follow).
	// Never touches a queued version that is NEWER than or equal to the target.
	const semverLt = (a, b) => {
		const pa = String(a).split('.').map(Number), pb = String(b).split('.').map(Number);
		for (let i = 0; i < 3; i++) { if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) < (pb[i] || 0); }
		return false;
	};
	const QUEUED = new Set(['WAITING_FOR_REVIEW', 'READY_FOR_REVIEW', 'IN_REVIEW']);
	const queued = versions.find((v) => QUEUED.has(v.attributes.appStoreState) && semverLt(v.attributes.versionString, version));
	if (queued) {
		await opts.beforeCancel?.();
		log(`${platform}: superseding ${queued.attributes.versionString} (${queued.attributes.appStoreState}) — canceling its review submission`);
		if (!dryRun) {
			const subs = (await api('GET', `/v1/reviewSubmissions?filter[app]=${appId}&filter[platform]=${platform}&filter[state]=READY_FOR_REVIEW,WAITING_FOR_REVIEW,IN_REVIEW&fields[reviewSubmissions]=state&limit=10`)).data || [];
			for (const sub of subs) {
				await api('PATCH', `/v1/reviewSubmissions/${sub.id}`, { data: { type: 'reviewSubmissions', id: sub.id, attributes: { canceled: true } } })
					.catch((e) => log(`${platform}: cancel of submission ${sub.id} → ${e.message || e} (continuing)`));
			}
			// The version takes a moment to fall back to an editable state after the cancel.
			let st = queued.attributes.appStoreState;
			for (let i = 0; i < 24; i++) {
				await new Promise((r) => setTimeout(r, 5000));
				const fresh = await api('GET', `/v1/appStoreVersions/${queued.id}?fields[appStoreVersions]=appStoreState`).catch(() => null);
				st = fresh?.data?.attributes?.appStoreState || st;
				if (EDITABLE.has(st)) break;
			}
			if (!EDITABLE.has(st)) die(`${platform}: canceled the review but ${queued.attributes.versionString} is still ${st} after 2min — check App Store Connect and re-run`);
			log(`${platform}: ${queued.attributes.versionString} back to ${st} — renaming → ${version}`);
			await api('PATCH', `/v1/appStoreVersions/${queued.id}`, { data: { type: 'appStoreVersions', id: queued.id, attributes: { versionString: version } } });
		}
		return { version: { ...queued, attributes: { ...queued.attributes, versionString: version } }, state: 'PREPARE_FOR_SUBMISSION' };
	}
	if (dryRun) return { version: { id: 'dry-run', attributes: { versionString: version } }, state: 'PREPARE_FOR_SUBMISSION' };
	const created = (await api('POST', '/v1/appStoreVersions', {
		data: { type: 'appStoreVersions', attributes: { platform, versionString: version }, relationships: { app: { data: { type: 'apps', id: appId } } } },
	})).data;
	log(`${platform}: created App Store version ${version}`);
	return { version: created, state: 'PREPARE_FOR_SUBMISSION' };
}

// `## whats_new` (and `## promotional_text`) from appstore-metadata[.<locale>].md, per
// locale that has a file. Promotional text rides along because a NEW App Store version
// does not inherit it from its predecessor — 1.6.1 went into review with it blank.
async function notesByLocale(dir) {
	const section = (md, key) => { const m = md.match(new RegExp(`^##\\s+${key}\\s*$\\n([\\s\\S]*?)(?=^##\\s+|\\s*$(?![\\s\\S]))`, 'm')); return m ? m[1].replace(/<!--[\s\S]*?-->/g, '').trim() : null; };
	const out = {}, promo = {};
	const { readdir } = await import('node:fs/promises');
	for (const f of await readdir(dir)) {
		const m = f.match(/^appstore-metadata(?:\.([A-Za-z-]+))?\.md$/);
		if (!m) continue;
		const locale = m[1] || 'en-US';
		const md = await readFile(join(dir, f), 'utf8');
		const body = section(md, 'whats_new');
		if (body) out[locale] = body;
		const p = section(md, 'promotional_text');
		if (p) promo[locale] = p;
	}
	return Object.assign(out, { __promo: promo });
}

async function setNotes(versionId, version, notes, { dryRun }) {
	const en = notes['en-US'];
	if (!en) die('appstore-metadata.md has no `## whats_new` — nothing to ship as release notes');
	if (!en.startsWith(version)) die(`appstore-metadata.md whats_new starts with "${en.split('\n')[0].slice(0, 60)}" — it must start with "${version}" (stale notes would ship otherwise)`);
	const locs = (await api('GET', `/v1/appStoreVersions/${versionId}/appStoreVersionLocalizations?limit=50&fields[appStoreVersionLocalizations]=locale,whatsNew,promotionalText`)).data || [];
	let set = 0; const fellBack = [];
	for (const loc of locs) {
		const locale = loc.attributes.locale;
		let text = notes[locale];
		// A translation still on an older version is worse than English: fall back, loudly.
		if (locale !== 'en-US' && (!text || !text.startsWith(version))) { fellBack.push(locale); text = en; }
		if (text.length > 4000) die(`${locale} whats_new is ${text.length} chars (ASC max 4000)`);
		priceCheck(`${locale} whats_new`, text);
		const attrs = {};
		if (loc.attributes.whatsNew !== text) attrs.whatsNew = text;
		const promo = (notes.__promo || {})[locale];
		if (promo != null) {
			if (promo.length > 170) die(`${locale} promotional_text is ${promo.length} chars (ASC max 170)`);
			priceCheck(`${locale} promotional_text`, promo);
			if ((loc.attributes.promotionalText || '') !== promo) attrs.promotionalText = promo;
		}
		if (Object.keys(attrs).length && !dryRun) {
			try {
				await api('PATCH', `/v1/appStoreVersionLocalizations/${loc.id}`, { data: { type: 'appStoreVersionLocalizations', id: loc.id, attributes: attrs } });
			} catch (e) {
				// A brand-new app's FIRST version has no What's New field — ASC rejects the
				// attribute outright (hit on Blip's first iOS version). Promotional text still
				// applies, so retry without whatsNew rather than failing the release.
				if (attrs.whatsNew && /whatsNew|first version|APP_STORE_VERSION/i.test(String(e.message))) {
					delete attrs.whatsNew;
					if (Object.keys(attrs).length) {
						await api('PATCH', `/v1/appStoreVersionLocalizations/${loc.id}`, { data: { type: 'appStoreVersionLocalizations', id: loc.id, attributes: attrs } });
					}
					log(`${locale}: first-version localization — ASC refused whatsNew, set the rest`);
				} else { throw e; }
			}
		}
		set++;
	}
	log(`What's New${Object.keys(notes.__promo || {}).length ? ' + promotional text' : ''} set on ${set}/${locs.length} localization(s)`);
	if (fellBack.length) {
		const msg = `no ${version} translation for ${fellBack.join(', ')} — those listings got the English notes (run \`rocket loc translate \"Scripture Alone\" --locale big8 --provider claude\` and re-push with \`rocket meta \"Scripture Alone\" --locale all\`)`;
		console.log(`⚠ ${msg}`); summary(`- ⚠️ ${msg}`);
	}
	return { set, fellBack };
}

// App Review 2.3.7: no price words in store text. Same list cut-release.yml and the Play notes
// step use. "DRM-free" and the like are not a price, so a hyphenated "-free" is allowed.
const PRICE_WORDS = /(?<![-\w])(free|price|pricing|priced|discount|sale|cheap|gratis|kostenlos|gratuit|gratuite|gratuito|grátis)(?![-\w])|\$\s?\d|無料|免费|免費|무료|価格|价格|가격/i;
function priceCheck(what, text) {
	const m = String(text || '').match(PRICE_WORDS);
	if (m) die(`${what} mentions "${m[0]}" — no price words in store text (App Review 2.3.7). Reword it in the metadata file.`);
}

// ── 4. Apple-hosted asset packs (ported from ARK's rocket lib/asc.mjs assetPackPlan) ─────
// Packs are reviewed like binaries: before the app's first approval every pack rides in the SAME
// submission as the version, and later a version that needs a new pack version is submitted with
// it. Per non-archived pack, the NEWEST version for the platform decides:
//   release PREPARE_FOR_SUBMISSION / READY_FOR_REVIEW / *REJECTED → attach it
//   WAITING_FOR_REVIEW / IN_REVIEW / live states                  → nothing to do
//   version state not COMPLETE (processing, failed)               → stop: the app would ship
//                                                                    without the data it needs
// The release state is only readable via include=appStoreRelease on the versions list (the
// direct relationship GET is 403). This script never archives a pack — archiving is permanent.
const PACK_CAP = 10;       // ASC: at most 10 asset packs per review submission
const PACK_SUBMITTABLE = new Set(['PREPARE_FOR_SUBMISSION', 'READY_FOR_REVIEW', 'DEVELOPER_REJECTED', 'REJECTED']);
const PACK_QUEUED = new Set(['WAITING_FOR_REVIEW', 'IN_REVIEW']);
const PACK_SETTLED = new Set([...PACK_QUEUED, 'ACCEPTED', 'APPROVED', 'READY_FOR_DISTRIBUTION', 'READY_FOR_SALE', 'DISTRIBUTED', 'REPLACED_WITH_NEW_VERSION']);

async function assetPackPlan(appId, platform) {
	const plan = { attach: [], settled: [], problems: [] };
	const r = await api('GET', `/v1/apps/${appId}/backgroundAssets?limit=200`).catch((e) => {
		// An endpoint error must not read as "no packs" — that would ship the app without them.
		plan.problems.push(`could not list asset packs: ${String(e.message).split('\n')[0]}`);
		return { data: [] };
	});
	for (const pack of r?.data || []) {
		if (pack.attributes?.archived) continue;
		const name = pack.attributes?.assetPackIdentifier || pack.id;
		const vs = await api('GET', `/v1/backgroundAssets/${pack.id}/versions?include=appStoreRelease&limit=200`);
		const releases = new Map((vs?.included || []).filter((x) => x.type === 'backgroundAssetVersionAppStoreReleases').map((x) => [x.id, x.attributes?.state]));
		const newest = (vs?.data || []).filter((v) => (v.attributes?.platforms || []).includes(platform))
			.sort((x, y) => Number(y.attributes.version) - Number(x.attributes.version))[0];
		if (!newest) continue;   // this pack does not ship on this platform
		const label = `${name} v${newest.attributes.version}`;
		if (newest.attributes.state !== 'COMPLETE') { plan.problems.push(`${label} is ${newest.attributes.state} — wait for processing, or fix the upload`); continue; }
		const release = releases.get(newest.relationships?.appStoreRelease?.data?.id);
		const entry = { id: newest.id, pack: name, version: newest.attributes.version, release, label };
		if (PACK_SUBMITTABLE.has(release)) plan.attach.push(entry);
		else if (PACK_SETTLED.has(release)) plan.settled.push(entry);
		else plan.problems.push(`${label} has App Store release state ${release || 'unknown'} — check it in App Store Connect`);
	}
	return plan;
}

function checkPackPlan(platform, plan, { superseding = false } = {}) {
	if (plan.problems.length) die(`${platform}: asset packs not ready — ${plan.problems.join('; ')}`);
	// Canceling a queued submission hands its queued packs back: they must ride again.
	const back = superseding ? plan.settled.filter((p) => PACK_QUEUED.has(p.release)) : [];
	const n = plan.attach.length + back.length;
	if (n > PACK_CAP) {
		die(`${platform}: ${n} asset packs would need this submission (${[...plan.attach, ...back].map((p) => p.label).join(', ')}) — ASC allows ${PACK_CAP} per review submission. `
			+ (back.length ? `Superseding the queued version would return its ${back.length} queued pack(s) to the pile; nothing was canceled. Let that version finish review, then re-run.` : 'Split the packs across releases.'));
	}
	return n;
}

// ── 5. review submission ───────────────────────────────────────────────────────────────
async function submit(appId, platform, versionId, packs = []) {
	const subs = (await api('GET', `/v1/reviewSubmissions?filter[app]=${appId}&filter[platform]=${platform}&filter[state]=READY_FOR_REVIEW,WAITING_FOR_REVIEW,IN_REVIEW,UNRESOLVED_ISSUES&fields[reviewSubmissions]=state,platform&limit=10`)).data || [];
	const itemsOf = async (sub) => new Set(((await api('GET', `/v1/reviewSubmissions/${sub.id}/items?include=appStoreVersion&limit=50`).catch(() => ({}))).included || []).filter((x) => x.type === 'appStoreVersions').map((x) => x.id));
	for (const s of subs) {
		if (['WAITING_FOR_REVIEW', 'IN_REVIEW'].includes(s.attributes.state)) {
			if ((await itemsOf(s)).has(versionId)) { log(`${platform}: already ${s.attributes.state} (submission ${s.id})`); return s.attributes.state; }
			die(`${platform}: another submission (${s.id}) is ${s.attributes.state} — ASC allows one at a time; wait for it or cancel it in App Store Connect`);
		}
		if (s.attributes.state === 'UNRESOLVED_ISSUES') die(`${platform}: submission ${s.id} is UNRESOLVED_ISSUES (a rejected item) — ASC refuses new items on it. Cancel it in App Store Connect (App Review → Cancel Submission) and re-run.`);
	}
	let sub = subs.find((s) => s.attributes.state === 'READY_FOR_REVIEW') || null;
	if (!sub) {
		sub = (await api('POST', '/v1/reviewSubmissions', { data: { type: 'reviewSubmissions', attributes: { platform }, relationships: { app: { data: { type: 'apps', id: appId } } } } })).data;
		log(`${platform}: opened review submission ${sub.id}`);
	}
	if (!(await itemsOf(sub)).has(versionId)) {
		// ASC settles for a moment after a version edit/attach; a 409 here is usually that.
		for (let i = 0; ; i++) {
			try {
				await api('POST', '/v1/reviewSubmissionItems', { data: { type: 'reviewSubmissionItems', relationships: { reviewSubmission: { data: { type: 'reviewSubmissions', id: sub.id } }, appStoreVersion: { data: { type: 'appStoreVersions', id: versionId } } } } });
				break;
			} catch (e) {
				// The add can land while its RESPONSE is lost (seen on Haven 1.7.0's resubmit):
				// every retry then 409s "state does not allow adding more items" even though
				// the item exists and the submission is fine. Re-check membership before
				// treating any 409 as fatal — presence means the add already succeeded.
				if (e.status === 409 && (await itemsOf(sub)).has(versionId)) { log(`${platform}: item already present — continuing`); break; }
				if (e.status === 409 && i < 5) { log(`${platform}: item add 409 — retrying in 60s (${i + 1}/5): ${e.message.split(':').slice(-1)[0].trim()}`); await sleep(60_000); continue; }
				throw e;
			}
		}
		log(`${platform}: version added to submission`);
	}
	// The asset packs ride on the SAME submission, added before it is submitted. A pack that will
	// not add stops the submit: the version would reach users without data it needs.
	const packIds = async () => {
		const r = await api('GET', `/v1/reviewSubmissions/${sub.id}/items?include=appStoreVersion,backgroundAssetVersion&limit=50`).catch(() => ({}));
		return new Set((r?.data || []).filter((it) => it.attributes?.state !== 'REMOVED').map((it) => it.relationships?.backgroundAssetVersion?.data?.id).filter(Boolean));
	};
	const onSub = await packIds();
	if (onSub.size + packs.filter((p) => !onSub.has(p.id)).length > PACK_CAP) die(`${platform}: submission ${sub.id} would carry more than ${PACK_CAP} asset packs — not submitting`);
	for (const p of packs) {
		if (onSub.has(p.id)) { log(`${platform}: asset pack ${p.label} already on the submission`); continue; }
		try {
			await api('POST', '/v1/reviewSubmissionItems', { data: { type: 'reviewSubmissionItems', relationships: { reviewSubmission: { data: { type: 'reviewSubmissions', id: sub.id } }, backgroundAssetVersion: { data: { type: 'backgroundAssetVersions', id: p.id } } } } });
			log(`${platform}: asset pack ${p.label} added`);
		} catch (e) {
			if (e.status === 409 && (await packIds()).has(p.id)) { log(`${platform}: asset pack ${p.label} already present — continuing`); continue; }
			die(`${platform}: could not add asset pack ${p.label} to submission ${sub.id}: ${String(e.message).split('\n')[0]} — the submission is NOT submitted; fix and re-run`);
		}
	}
	await api('PATCH', `/v1/reviewSubmissions/${sub.id}`, { data: { type: 'reviewSubmissions', id: sub.id, attributes: { submitted: true } } });
	const after = await api('GET', `/v1/reviewSubmissions/${sub.id}?fields[reviewSubmissions]=state`);
	const state = after?.data?.attributes?.state;
	if (state !== 'WAITING_FOR_REVIEW' && state !== 'IN_REVIEW') die(`${platform}: submitted=true accepted but submission is ${state}, not WAITING_FOR_REVIEW`);
	return state;
}

function emptyCommitAdvice(args, why) {
	return `${why}. An empty commit builds (the guard only skips a non-empty android/docs/md diff) and is iOS-equivalent to the tag: `
		+ `\`git commit --allow-empty -m "Build ${args.version} for the App Store" && git push\` on main (no [ci skip]), let Xcode Cloud build it, then re-run this lane.`;
}

// ── main ───────────────────────────────────────────────────────────────────────────────
async function main() {
	const args = parseArgs(process.argv);
	READ_ONLY = args.dryRun;
	CREDS = creds();
	const app = (await api('GET', `/v1/apps?filter[bundleId]=${encodeURIComponent(args.bundleId)}&limit=1&fields[apps]=name,bundleId`)).data?.[0];
	if (!app) die(`No app for ${args.bundleId}`);
	if (args.appId && app.id !== args.appId) die(`${args.bundleId} is app ${app.id}, expected ${args.appId}`);
	log(`${app.attributes.name} ${args.version} ← commit ${args.commit.slice(0, 10)} (${args.tag})${args.dryRun ? ' — DRY RUN' : ''}`);

	// 1. the run
	const product = await ciProductFor(app.id);
	if (!product) die('no Xcode Cloud product for this app');
	let run = await findRun(product.id, args.commit);
	const workflow = await archiveWorkflow(product.id, args.workflowId);
	let ownRunFailed = false;
	if (run && run.attributes.executionProgress === 'COMPLETE' && run.attributes.completionStatus !== 'SUCCEEDED') {
		// Its own run failed and no iOS-equivalent commit has a build (findRun looked). If the
		// failure is the nothing-to-build guard, a fresh run of the same commit skips again.
		if (guardSkips(args.commit)) die(emptyCommitAdvice(args, `Xcode Cloud run #${run.attributes.number} for ${args.commit.slice(0, 10)} was the nothing-to-build guard's deliberate skip, and no iOS-equivalent commit has a build`));
		log(`Xcode Cloud run #${run.attributes.number} for this commit ${run.attributes.completionStatus} — starting a fresh one`);
		run = null; ownRunFailed = true;
	}
	if (!run && !ownRunFailed) {
		// XCC builds every push to main and lists the run with some lag. A commit pushed minutes
		// ago almost certainly HAS a run incoming — poll before resorting to a manual start.
		// Ten minutes, not four: the tag lands seconds after the push and XCC's listing lags; giving
		// up early used to start a run on `main` — which, once main had moved on, built the wrong version.
		for (let i = 0; i < 20 && !run; i++) {
			log(`no Xcode Cloud run listed for ${args.commit.slice(0, 10)} yet — waiting for the push-triggered one (${i + 1}/20)…`);
			await sleep(30_000);
			run = await findRun(product.id, args.commit);
			if (run && run.attributes.executionProgress === 'COMPLETE' && run.attributes.completionStatus !== 'SUCCEEDED') run = null;
		}
	}
	if (!run) {
		if (args.dryRun) die('dry run: no Xcode Cloud run for this commit (or an iOS-equivalent one), and a dry run does not start one');
		const { ref, via } = await gitReference(workflow.id, { tag: args.tag, commit: args.commit });
		try {
			run = await startRun(workflow.id, ref);
			log(`started Xcode Cloud run #${run.attributes?.number ?? '?'} on workflow "${workflow.attributes.name}" via ${via}`);
		} catch (e) {
			// XCC refuses manual runs on a TAG unless the workflow's start condition lists tags.
			// Ours builds on branch pushes — fall back to starting the BRANCH when its tip carries
			// the same tree/version.
			//
			// TWO shapes of refusal, both seen in production:
			//   * 409 "the tag is not associated with the workflow" — the documented one (1.8.6).
			//   * 500 UNEXPECTED_ERROR — what the SAME refused tag start returned on 2026-09-11,
			//     with no detail beyond "an unexpected error occurred on the server side". Matching
			//     only the 409 text made that fatal, and the fallback that exists for exactly this
			//     case never ran: the 1.8.7 lane died twice in a row with the branch start, which
			//     would have worked, one line away.
			// A 500 here is indistinguishable from Apple being down, so the fallback is ATTEMPTED
			// rather than assumed: if the branch start also fails, that error is the one that
			// surfaces, and the message below says to start the build by hand.
			const refused = /not associated with the workflow/i.test(String(e?.message || e)) || e?.status === 500;
			if (!refused) throw e;
			log(`tag start refused (${e?.status ?? '?'}) — starting branch main instead`);
			const repo = await api('GET', `/v1/ciWorkflows/${workflow.id}/repository?fields[scmRepositories]=repositoryName`);
			const refs = await paged(`/v1/scmRepositories/${repo.data.id}/gitReferences?limit=200&fields[scmGitReferences]=name,kind,isDeleted`, 5);
			const m = refs.find((x) => x.attributes?.kind === 'BRANCH' && !x.attributes?.isDeleted && x.attributes?.name === 'main');
			if (!m) die('no main branch reference in Xcode Cloud — start the build by hand and re-run');
			try {
				run = await startRun(workflow.id, m);
			} catch (e2) {
				// Neither ref could be started. On 2026-09-11 this was Apple's side: POST
				// /v1/ciBuildRuns returned 500 UNEXPECTED_ERROR for the tag AND for branch main,
				// for over an hour, while every GET on the same product answered fine. There is
				// nothing to retry around, so say what actually happened and what unblocks it.
				die(`Xcode Cloud refused to start a run for both ${args.tag} and branch main `
					+ `(${e2?.status ?? '?'}: ${String(e2?.message || e2).slice(0, 120)}). `
					+ `A PUSH still triggers the workflow — push a commit to main (without [ci skip]) `
					+ `or start the build in Xcode Cloud by hand, then re-run this lane.`);
			}
			log(`started Xcode Cloud run #${run.attributes?.number ?? '?'} via branch main`);
			// The branch tip may differ from the tagged sha: the pin check below accepts it only
			// when it is iOS-equivalent to the tag (Haven empties the pin here and relies on the
			// marketing version alone; the equivalence check is stricter).
		}
	} else log(`Xcode Cloud run #${run.attributes.number} (${run.attributes.executionProgress}/${run.attributes.completionStatus || 'running'})`);
	run = await waitRun(run.id, args.waitBuild);
	if (run.attributes.completionStatus !== 'SUCCEEDED') {
		if (runSha(run) && guardSkips(runSha(run))) die(emptyCommitAdvice(args, `Xcode Cloud run #${run.attributes.number} (${runSha(run).slice(0, 10)}) was the nothing-to-build guard's deliberate skip`));
		die(`Xcode Cloud run #${run.attributes.number} ${run.attributes.completionStatus} — fix the build, push, re-tag`);
	}
	// Commit pin: the run must have built the tagged commit or an iOS-equivalent one. The
	// marketing-version check right below is a second guard against the wrong tree.
	if (!iosEquivalent(runSha(run), args.commit)) die(`run #${run.attributes.number} built ${runSha(run).slice(0, 10) || '?'}, which differs from ${args.commit.slice(0, 10)} in files the iOS app is built from — start a build of the tagged commit in Xcode Cloud by hand and re-run`);
	log(`run #${run.attributes.number} SUCCEEDED`);

	// 2. the builds
	let builds = await runBuilds(run.id);
	if (!builds.length) die(`run #${run.attributes.number} uploaded no builds`);
	const wrongVersion = builds.filter((b) => b.marketing && b.marketing !== args.version);
	if (wrongVersion.length) die(`run #${run.attributes.number} built marketing version ${wrongVersion[0].marketing}, not ${args.version} — MARKETING_VERSION in apple/project.yml is out of step with the tag`);
	const byPlatform = {};
	for (const p of args.platforms) {
		const b = builds.filter((x) => x.platform === p).sort((x, y) => Number(y.number) - Number(x.number))[0];
		if (!b) die(`run #${run.attributes.number} has no ${p} build (got: ${builds.map((x) => `${x.platform} ${x.number}`).join(', ') || 'none'})`);
		byPlatform[p] = await waitValid(b, args.waitProcess);
		log(`${p}: build ${byPlatform[p].number} VALID`);
	}

	// RC path: the build reaching VALID *is* the deliverable — Xcode Cloud's post-action has
	// already handed it to TestFlight, and a VALID build is TestFlight-installable. No App Store
	// version, no notes, no review submission.
	if (args.testflightOnly) {
		console.log(`✓ TestFlight-only: ${args.platforms.map((p) => `${p} build ${byPlatform[p].number}`).join(', ')} VALID — testers have it. Stopping before any App Store version.`);
		process.exit(0);
	}

	// 3 + 4. per platform
	const notes = await notesByLocale(args.notesDir);
	const results = [];
	for (const platform of args.platforms) {
		const build = byPlatform[platform];
		// Plan the asset packs BEFORE anything changes: a supersede cancels a queued submission,
		// and if the packs would then overflow the 10-per-submission cap that cancel must not happen.
		let packPlan = await assetPackPlan(app.id, platform);
		for (const p of packPlan.settled) log(`${platform}: asset pack ${p.label} already ${p.release}`);
		checkPackPlan(platform, packPlan);
		let superseded = false;
		const { version, state, done } = await ensureVersion(app.id, platform, args.version, {
			dryRun: args.dryRun, resubmit: args.resubmit,
			beforeCancel: async () => { checkPackPlan(platform, packPlan, { superseding: true }); superseded = true; },
		});
		if (done) { log(`${platform} ${args.version} is already ${state} — nothing to do`); results.push(`${platform} ${args.version}: already ${state}`); continue; }
		if (superseded && !args.dryRun) {
			// The canceled submission's packs fall back to an attachable state after a moment.
			for (let i = 0; i < 12; i++) {
				packPlan = await assetPackPlan(app.id, platform);
				if (!packPlan.settled.some((p) => PACK_QUEUED.has(p.release))) break;
				log(`${platform}: waiting for the canceled submission's asset packs to come back (${i + 1}/12)…`);
				await sleep(10_000);
			}
			if (packPlan.settled.some((p) => PACK_QUEUED.has(p.release))) die(`${platform}: after the supersede, asset packs are still queued (${packPlan.settled.filter((p) => PACK_QUEUED.has(p.release)).map((p) => p.label).join(', ')}) — check App Store Connect and re-run`);
			checkPackPlan(platform, packPlan);
		}
		const packNote = packPlan.attach.length ? ` + ${packPlan.attach.length} asset pack${packPlan.attach.length === 1 ? '' : 's'} (${packPlan.attach.map((p) => p.label).join(', ')})` : '';
		if (packPlan.attach.length) log(`${platform}: asset packs going with this version: ${packPlan.attach.map((p) => p.label).join(', ')}`);
		log(`${platform}: editable version ${args.version} (${state})`);
		if (!args.dryRun) {
			await setNotes(version.id, args.version, notes, { dryRun: false });
			const attached = await api('GET', `/v1/appStoreVersions/${version.id}/build?fields[builds]=version`).catch(() => null);
			if (attached?.data?.id !== build.id) {
				await api('PATCH', `/v1/appStoreVersions/${version.id}/relationships/build`, { data: { type: 'builds', id: build.id } });
				log(`${platform}: attached build ${build.number}`);
			} else log(`${platform}: build ${build.number} already attached`);
			if (build.usesNonExemptEncryption == null) {
				// ITSAppUsesNonExemptEncryption=NO is in the Info.plist, so ASC normally never asks;
				// if a build arrives unanswered anyway, answer the same thing rather than stall.
				await api('PATCH', `/v1/builds/${build.id}`, { data: { type: 'builds', id: build.id, attributes: { usesNonExemptEncryption: false } } });
				log(`${platform}: answered export compliance on build ${build.number} (exempt)`);
			}
			if (!args.submit) { results.push(`${platform} ${args.version}: build ${build.number} attached, not submitted (--no-submit)`); continue; }
			const st = await submit(app.id, platform, version.id, packPlan.attach);
			results.push(`${platform} ${args.version}: build ${build.number}${packNote} → ${st}`);
			console.log(`✓ ${platform} ${args.version} (build ${build.number}) ${st}`);
		} else {
			await setNotes(version.id === 'dry-run' ? null : version.id, args.version, notes, { dryRun: true }).catch((e) => { if (version.id !== 'dry-run') throw e; log(`(dry run) ${e.message}`); });
			results.push(`${platform} ${args.version}: would attach build ${build.number}${packNote} and submit`);
		}
	}
	summary(`### App Store — ${app.attributes.name} ${args.version}\n- Xcode Cloud run #${run.attributes.number} (commit \`${args.commit.slice(0, 10)}\`)\n${results.map((r) => `- ${r}`).join('\n')}`);
}

main().catch((e) => die(e.message));
