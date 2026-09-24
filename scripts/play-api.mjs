/**
 * play-api.mjs — the Google Play Developer API plumbing shared by scripts/play-publish.mjs (bundles
 * and tracks) and scripts/play-listing.mjs (store listing text and images): service-account auth,
 * a retrying JSON client, and the edit lifecycle.
 *
 * Credentials: env PLAY_SERVICE_ACCOUNT_JSON (the JSON key's contents, raw or base64), or a key file.
 * Play allows ONE open edit per package that can commit, so every workflow that opens one shares the
 * `android-play-publish` concurrency group.
 */
import { readFileSync, appendFileSync } from 'node:fs';
import { createSign } from 'node:crypto';

export const API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications';
export const UPLOAD = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications';

export const summary = (line) => { if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, line + '\n'); };
export const output = (k, v) => { if (process.env.GITHUB_OUTPUT) appendFileSync(process.env.GITHUB_OUTPUT, `${k}=${v}\n`); };
export const die = (m) => { console.error(`✗ ${m}`); summary(`- ❌ Play: ${m}`); process.exit(1); };
// Inside an open edit, fail by THROWING so `finally` deletes the edit (process.exit would skip it).
export const fail = (m) => { throw new Error(m); };
export const log = (m) => console.log(`• ${m}`);
const b64url = (b) => Buffer.from(b).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

// ── auth ──────────────────────────────────────────────────────────────────────────────
let SA = null, TOKEN = null, TOKEN_AT = 0;

/** Load the service account (env PLAY_SERVICE_ACCOUNT_JSON, else `keyFile`); dies when there is none. */
export function useServiceAccount(keyFile) {
	let raw = process.env.PLAY_SERVICE_ACCOUNT_JSON || '';
	if (!raw && keyFile) raw = readFileSync(keyFile, 'utf8');
	if (!raw) die('no credentials — set PLAY_SERVICE_ACCOUNT_JSON (the service-account JSON) or pass --key-file');
	if (!raw.trim().startsWith('{')) raw = Buffer.from(raw.trim(), 'base64').toString('utf8'); // tolerate base64
	let j; try { j = JSON.parse(raw); } catch { die('PLAY_SERVICE_ACCOUNT_JSON is not JSON'); }
	if (!j.client_email || !j.private_key) die('service-account JSON lacks client_email / private_key');
	SA = j;
	return j;
}

async function token() {
	if (TOKEN && Date.now() - TOKEN_AT < 45 * 60_000) return TOKEN;
	if (!SA) die('useServiceAccount() was not called');
	const now = Math.floor(Date.now() / 1000);
	const input = `${b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${b64url(JSON.stringify({
		iss: SA.client_email, scope: 'https://www.googleapis.com/auth/androidpublisher',
		aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600,
	}))}`;
	const sig = createSign('RSA-SHA256').update(input).sign(SA.private_key);
	const res = await fetch('https://oauth2.googleapis.com/token', {
		method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
		body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${input}.${b64url(sig)}` }),
	});
	const j = await res.json().catch(() => ({}));
	if (!res.ok) die(`Google OAuth refused the service account: ${j.error_description || j.error || res.status}`);
	TOKEN = j.access_token; TOKEN_AT = Date.now();
	return TOKEN;
}

/**
 * Authenticated request. JSON in and out by default; `raw: true` sends `body` as-is (a Buffer) and
 * `raw: 'response'` also returns the Response. 429 / 5xx / network errors are retried for JSON calls.
 */
export async function api(method, url, body, { headers = {}, raw = false, retries = 3 } = {}) {
	for (let attempt = 0; ; attempt++) {
		const res = await fetch(url, {
			method,
			headers: { authorization: `Bearer ${await token()}`, ...(body && !raw ? { 'content-type': 'application/json' } : {}), ...headers },
			body: body == null ? undefined : raw ? body : JSON.stringify(body),
			...(raw ? { duplex: 'half' } : {}),
		}).catch((e) => ({ ok: false, status: 0, headers: new Headers(), text: async () => String(e) }));
		if (res.ok) return raw === 'response' ? res : (res.status === 204 ? null : res.json().catch(() => null));
		const text = await res.text();
		let msg = text; try { msg = JSON.parse(text).error?.message || text; } catch { /* */ }
		if ((res.status === 0 || res.status === 429 || res.status >= 500) && attempt < retries && !raw) {
			await new Promise((r) => setTimeout(r, 5000 * (attempt + 1))); continue;
		}
		const err = new Error(`${method} ${url.replace(/^https:\/\/[^/]+/, '')} → ${res.status}: ${msg}`); err.status = res.status; throw err;
	}
}

// ── edits ─────────────────────────────────────────────────────────────────────────────
export const base = (a) => `${API}/${encodeURIComponent(a.pkg)}`;
export const openEdit = async (a) => (await api('POST', `${base(a)}/edits`, {})).id;
export const deleteEdit = (a, id) => api('DELETE', `${base(a)}/edits/${id}`).catch(() => {});
