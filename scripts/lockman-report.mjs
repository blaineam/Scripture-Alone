/**
 * lockman-report.mjs — fills docs/lockman/annual-report-template.md with the year's figures from
 * App Store Connect and Google Play, for the usage report owed to The Lockman Foundation under the
 * NASB Distribution Permission Agreements (due within 60 days after each October 1).
 *
 *   node scripts/lockman-report.mjs                       # the last complete Oct 1 – Sep 30 year
 *   node scripts/lockman-report.mjs --from 2026-10-01 --to 2027-09-30 --available-from 2026-11-15
 *   node scripts/lockman-report.mjs --out report.md
 *
 * Apple (Sales and Trends, via the App Store Connect API):
 *   ASC_API_KEY_ID, ASC_API_ISSUER_ID, ASC_API_KEY_P8   the key apple-store.yml uses; it needs a role
 *                                                      that can read Sales and Trends (Admin, Finance
 *                                                      or Sales — or App Manager with access to reports)
 *   ASC_VENDOR_NUMBER                                  App Store Connect › Payments and Financial
 *                                                      Reports, top left (not secret)
 * Google Play (the monthly installs report in the Play Console's Cloud Storage bucket):
 *   PLAY_SERVICE_ACCOUNT_JSON                          the account android.yml uses, granted "View app
 *                                                      information and download bulk reports"
 *   PLAY_REPORTS_BUCKET                                Play Console › Download reports › Copy Cloud
 *                                                      Storage URI, e.g. gs://pubsite_prod_rev_0123…
 * Optional, typed in by hand because no API supplies them:
 *   NASB_AVAILABLE_FROM   date the first store build containing the NASB went live (YYYY-MM-DD);
 *                         counting starts there rather than at the start of the period
 *   APPLE_ACTIVE          App Store Connect › Analytics › Active Devices on the last day, if wanted
 *   WEB_USAGE             a sentence about the website, e.g. "The NASB is not offered on the website."
 *   EXTRA_NOTES           any further bullet lines
 *
 * Hopps (local runs): when ~/.hopps/hopps.db exists (or HOPPS_DB points at one) the figures come from
 * Hopps' archive of the same store reports first. Hopps keeps every day it collects, so Apple's
 * daily sales still count after App Store Connect drops them at 365 days; it also supplies Apple
 * active devices (App Analytics) and the website's page views, which the APIs path leaves to hand.
 * A store whose days/months Hopps doesn't fully cover falls back to the APIs above (or is noted).
 *   --no-hopps            ignore Hopps and use the APIs only
 *
 * A source without credentials is left as "—" and said so in the notes; the report is still written.
 * No figure is invented: "—" means "not fetched", 0 means the store reported zero.
 */
import { readFileSync, writeFileSync, appendFileSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { createPrivateKey, createSign, sign } from 'node:crypto';
import { gunzipSync } from 'node:zlib';

const BUNDLE_ID = 'com.blainemiller.ScriptureAlone';
const PLAY_PACKAGE = 'com.blainemiller.scripturealone';
const TEMPLATE = new URL('../docs/lockman/annual-report-template.md', import.meta.url);

const b64url = (b) => Buffer.from(b).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const summary = (line) => { if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, line + '\n'); };
const log = (m) => console.log(`• ${m}`);
const notes = [];

// ── dates ─────────────────────────────────────────────────────────────────────────────
const iso = (d) => d.toISOString().slice(0, 10);
const day = (s) => { const d = new Date(`${s}T00:00:00Z`); if (Number.isNaN(+d)) throw new Error(`not a date: ${s}`); return d; };
const addDays = (d, n) => new Date(+d + n * 86_400_000);
const longDate = (d) => d.toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric', timeZone: 'UTC' });

function parseArgs(argv) {
	const a = {};
	for (let i = 0; i < argv.length; i++) {
		const k = argv[i];
		if (!k.startsWith('--')) continue;
		a[k.slice(2)] = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : true;
	}
	return a;
}

/** The last complete agreement year: Oct 1 – Sep 30, ending before today. */
function defaultPeriod(today = new Date()) {
	const y = today.getUTCMonth() >= 9 ? today.getUTCFullYear() : today.getUTCFullYear() - 1;
	return { from: day(`${y - 1}-10-01`), to: day(`${y}-09-30`) };
}

/** [first, last] of each calendar month the range touches, clipped to the range. */
function months(from, to) {
	const out = [];
	let cursor = new Date(Date.UTC(from.getUTCFullYear(), from.getUTCMonth(), 1));
	while (cursor <= to) {
		const end = new Date(Date.UTC(cursor.getUTCFullYear(), cursor.getUTCMonth() + 1, 0));
		out.push({ key: iso(cursor).slice(0, 7), first: cursor < from ? from : cursor, last: end > to ? to : end,
			whole: cursor >= from && end <= to });
		cursor = new Date(Date.UTC(cursor.getUTCFullYear(), cursor.getUTCMonth() + 1, 1));
	}
	return out;
}

// ── Apple: Sales and Trends ───────────────────────────────────────────────────────────
const DOWNLOAD = new Set(['1', '1F', '1T', 'F1', '1E', '1EP', '1EU']);
const REDOWNLOAD = new Set(['3', '3F', '3T', 'F3']);
const UPDATE = new Set(['7', '7F', '7T', 'F7']);

function ascToken() {
	let pem = process.env.ASC_API_KEY_P8 || '';
	if (pem && !pem.includes('-----BEGIN')) pem = Buffer.from(pem.trim(), 'base64').toString('utf8');
	const now = Math.floor(Date.now() / 1000);
	const input = `${b64url(JSON.stringify({ alg: 'ES256', kid: process.env.ASC_API_KEY_ID, typ: 'JWT' }))}.` +
		`${b64url(JSON.stringify({ iss: process.env.ASC_API_ISSUER_ID, iat: now, exp: now + 19 * 60, aud: 'appstoreconnect-v1' }))}`;
	return `${input}.${b64url(sign('sha256', Buffer.from(input), { key: createPrivateKey(pem), dsaEncoding: 'ieee-p1363' }))}`;
}

async function asc(path, accept = 'application/json') {
	for (let attempt = 0; ; attempt++) {
		const res = await fetch(`https://api.appstoreconnect.apple.com${path}`, { headers: { authorization: `Bearer ${ascToken()}`, accept } });
		if (res.ok) return accept === 'application/json' ? res.json() : Buffer.from(await res.arrayBuffer());
		const text = await res.text();
		if ((res.status === 429 || res.status >= 500) && attempt < 3) { await new Promise((r) => setTimeout(r, 5000 * (attempt + 1))); continue; }
		const err = new Error(`GET ${path.split('?')[0]} → ${res.status}: ${text.slice(0, 300)}`); err.status = res.status; err.body = text; throw err;
	}
}

/** One Sales report's rows for this app, or [] when Apple says there were no sales that period. */
async function salesRows(frequency, reportDate, appId) {
	const q = new URLSearchParams({
		'filter[frequency]': frequency, 'filter[reportDate]': reportDate, 'filter[reportSubType]': 'SUMMARY',
		'filter[reportType]': 'SALES', 'filter[vendorNumber]': process.env.ASC_VENDOR_NUMBER, 'filter[version]': '1_1',
	});
	let gz;
	try { gz = await asc(`/v1/salesReports?${q}`, 'application/a-gzip'); } catch (e) {
		// 404 with "no sales" is Apple's way of saying zero; anything else is a real failure.
		if (e.status === 404 && /no sales|NOT_FOUND/i.test(e.body || '')) return [];
		throw e;
	}
	const [head, ...lines] = gunzipSync(gz).toString('utf8').trim().split('\n');
	const cols = head.split('\t');
	const at = (name) => cols.indexOf(name);
	const [type, units, apple, parent] = ['Product Type Identifier', 'Units', 'Apple Identifier', 'Parent Identifier'].map(at);
	return lines.map((l) => l.split('\t'))
		.filter((c) => c[apple] === appId || c[parent] === appId)
		.map((c) => ({ type: c[type], units: Number(c[units]) || 0 }));
}

async function apple(from, to) {
	const need = ['ASC_API_KEY_ID', 'ASC_API_ISSUER_ID', 'ASC_API_KEY_P8', 'ASC_VENDOR_NUMBER'].filter((k) => !process.env[k]);
	if (need.length) { notes.push(`- Apple figures not fetched: ${need.join(', ')} not set.`); return null; }
	const app = (await asc(`/v1/apps?filter[bundleId]=${BUNDLE_ID}&fields[apps]=bundleId`)).data?.[0];
	if (!app) throw new Error(`no App Store Connect app with bundle id ${BUNDLE_ID}`);
	const totals = { downloads: 0, redownloads: 0, updates: 0 };
	const add = (rows) => rows.forEach(({ type, units }) => {
		if (DOWNLOAD.has(type)) totals.downloads += units;
		else if (REDOWNLOAD.has(type)) totals.redownloads += units;
		else if (UPDATE.has(type)) totals.updates += units;
	});
	for (const m of months(from, to)) {
		if (m.whole) { add(await salesRows('MONTHLY', m.key, app.id)); log(`Apple ${m.key}: monthly`); continue; }
		// A month the counted range only partly covers: add up its days instead, so nothing outside
		// the range is counted. Apple keeps daily reports for a year; past that, say so and use the month.
		try {
			for (let d = m.first; d <= m.last; d = addDays(d, 1)) add(await salesRows('DAILY', iso(d), app.id));
			log(`Apple ${m.key}: daily, ${iso(m.first)}–${iso(m.last)}`);
		} catch (e) {
			if (e.status !== 404 && e.status !== 400) throw e;
			add(await salesRows('MONTHLY', m.key, app.id));
			notes.push(`- Apple's daily reports for ${m.key} have expired, so the whole month is counted; it includes days outside ${iso(m.first)}–${iso(m.last)}.`);
		}
	}
	return totals;
}

// ── Google Play: monthly installs report in Cloud Storage ─────────────────────────────
async function googleToken(scope) {
	let raw = process.env.PLAY_SERVICE_ACCOUNT_JSON || '';
	if (!raw.trim().startsWith('{')) raw = Buffer.from(raw.trim(), 'base64').toString('utf8');
	const sa = JSON.parse(raw);
	const now = Math.floor(Date.now() / 1000);
	const input = `${b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${b64url(JSON.stringify({
		iss: sa.client_email, scope, aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 }))}`;
	const sig = createSign('RSA-SHA256').update(input).sign(sa.private_key);
	const res = await fetch('https://oauth2.googleapis.com/token', {
		method: 'POST', headers: { 'content-type': 'application/x-www-form-urlencoded' },
		body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${input}.${b64url(sig)}` }),
	});
	const j = await res.json().catch(() => ({}));
	if (!res.ok) throw new Error(`Google OAuth refused the service account: ${j.error_description || j.error || res.status}`);
	return j.access_token;
}

/** Play's report CSVs are UTF-16LE with a BOM; quoted fields, no embedded newlines. */
function parseCsv(buffer) {
	const text = buffer[0] === 0xff && buffer[1] === 0xfe ? buffer.subarray(2).toString('utf16le') : buffer.toString('utf8').replace(/^﻿/, '');
	const rows = text.trim().split(/\r?\n/).map((line) => {
		const out = []; let cur = '', quoted = false;
		for (const ch of line) {
			if (ch === '"') quoted = !quoted;
			else if (ch === ',' && !quoted) { out.push(cur); cur = ''; } else cur += ch;
		}
		out.push(cur);
		return out;
	});
	const [head, ...body] = rows;
	return body.map((r) => Object.fromEntries(head.map((h, i) => [h.trim(), (r[i] ?? '').trim()])));
}

async function play(from, to) {
	const need = ['PLAY_SERVICE_ACCOUNT_JSON', 'PLAY_REPORTS_BUCKET'].filter((k) => !process.env[k]);
	if (need.length) { notes.push(`- Google Play figures not fetched: ${need.join(', ')} not set.`); return null; }
	const bucket = process.env.PLAY_REPORTS_BUCKET.replace(/^gs:\/\//, '').replace(/\/.*$/, '');
	const token = await googleToken('https://www.googleapis.com/auth/devstorage.read_only');
	const totals = { downloads: 0, updates: 0, active: null };
	const num = (row, ...names) => { for (const n of names) if (row[n] !== undefined && row[n] !== '') return Number(row[n]) || 0; return 0; };
	let lastDate = '';
	for (const m of months(from, to)) {
		const object = `stats/installs/installs_${PLAY_PACKAGE}_${m.key.replace('-', '')}_overview.csv`;
		const res = await fetch(`https://storage.googleapis.com/storage/v1/b/${bucket}/o/${encodeURIComponent(object)}?alt=media`,
			{ headers: { authorization: `Bearer ${token}` } });
		if (res.status === 404) { notes.push(`- Google Play has no installs report for ${m.key} (counted as zero).`); continue; }
		if (!res.ok) throw new Error(`Play report ${object} → ${res.status}: ${(await res.text()).slice(0, 300)}`);
		for (const row of parseCsv(Buffer.from(await res.arrayBuffer()))) {
			const date = row.Date;
			if (!date || date < iso(m.first) || date > iso(m.last)) continue;
			totals.downloads += num(row, 'Daily User Installs', 'Install events');
			totals.updates += num(row, 'Update events', 'Daily Device Upgrades');
			if (date >= lastDate) { lastDate = date; totals.active = num(row, 'Active Device Installs'); }
		}
		log(`Play ${m.key}: read`);
	}
	return totals;
}

// ── Hopps: the local archive of the same store reports ───────────────────────────────
const HOPPS_APP = 'Scripture Alone';
/** Figures from Hopps for the counted range, per store only when Hopps covers every day/month of it. */
async function hopps(from, to) {
	const path = process.env.HOPPS_DB || join(homedir(), '.hopps', 'hopps.db');
	if (!existsSync(path)) return null;
	const { DatabaseSync } = await import('node:sqlite');
	const db = new DatabaseSync(path, { readOnly: true });
	const total = (source, metric) => db.prepare(`SELECT coalesce(sum(value), 0) v FROM facts WHERE source = ? AND app = ? AND metric = ? AND dim = '' AND date BETWEEN ? AND ?`)
		.get(source, HOPPS_APP, metric, iso(from), iso(to)).v;
	const last = (source, metric) => db.prepare(`SELECT date, value FROM facts WHERE source = ? AND app = ? AND metric = ? AND dim = '' AND date BETWEEN ? AND ? ORDER BY date DESC LIMIT 1`)
		.get(source, HOPPS_APP, metric, iso(from), iso(to)) || null;
	const kv = (k) => db.prepare('SELECT v FROM kv WHERE k = ?').get(k)?.v ?? null;
	const out = { apple: null, play: null, appleActive: null, web: null };

	// Apple: Hopps marks each day of Sales and Trends it has read ("done", or "empty" for no sales).
	const missingDays = [];
	for (let d = from; d <= to; d = addDays(d, 1)) if (!kv(`asc-sales:${iso(d)}`)) missingDays.push(iso(d));
	if (!missingDays.length) out.apple = { downloads: total('asc-sales', 'downloads'), redownloads: total('asc-sales', 'redownloads'), updates: total('asc-sales', 'updates') };
	else out.appleGap = missingDays;

	// Google Play: Hopps marks each monthly installs report it has read.
	const missingMonths = months(from, to).filter((m) => !kv(`play-rep:${PLAY_PACKAGE}:${m.key.replace('-', '')}`)).map((m) => m.key);
	if (!missingMonths.length) {
		const active = last('play', 'play_active_device_installs');
		out.play = { downloads: total('play', 'play_daily_user_installs'), updates: total('play', 'play_update_events'), active: active ? active.value : null };
	} else out.playGap = missingMonths;

	// Apple active devices (App Analytics, "Unique Devices" with sessions) on the last day Hopps has.
	const act = last('asc-analytics', 'active_devices');
	if (act) out.appleActive = act;

	// The app's page on the website: page views from people (Hopps sets crawlers aside), Cloudflare edge data.
	const web = db.prepare(`SELECT coalesce(sum(value), 0) v, min(date) first FROM facts WHERE source = 'web' AND app = ? AND metric = 'page_views' AND dim = '' AND date BETWEEN ? AND ?`)
		.get(HOPPS_APP, iso(from), iso(to));
	if (web.first) out.web = { views: web.v, since: web.first };
	db.close();
	return out;
}

// ── fill the template ─────────────────────────────────────────────────────────────────
const fmt = (n) => (n == null ? '—' : Number(n).toLocaleString('en-US'));
const sum = (...xs) => (xs.some((x) => x == null) ? (xs.every((x) => x == null) ? null : xs.reduce((a, x) => a + (x ?? 0), 0)) : xs.reduce((a, x) => a + x, 0));

async function main() {
	const args = parseArgs(process.argv.slice(2));
	const period = defaultPeriod();
	const from = args.from ? day(args.from) : period.from;
	const to = args.to ? day(args.to) : period.to;
	const availableRaw = args['available-from'] || process.env.NASB_AVAILABLE_FROM || '';
	const available = availableRaw ? day(availableRaw) : null;
	const countFrom = available && available > from ? available : from;
	if (!available) notes.push('- NASB_AVAILABLE_FROM is not set, so the whole period is counted. Set it to the date the first NASB build went live.');
	if (countFrom > to) throw new Error(`the NASB was not available during ${iso(from)}–${iso(to)}`);
	log(`period ${iso(from)}–${iso(to)}, counting from ${iso(countFrom)}`);

	const h = args['no-hopps'] ? null : await hopps(countFrom, to);
	if (h) log(`Hopps archive found — Apple ${h.apple ? 'covered' : `missing ${h.appleGap.length} day(s)`}, Play ${h.play ? 'covered' : `missing ${h.playGap.join(', ')}`}`);
	const a = h?.apple || await apple(countFrom, to);
	const p = h?.play || await play(countFrom, to);
	if (h && !h.apple) notes.push(`- Hopps is missing Apple's daily sales for ${h.appleGap.length} day(s) (${h.appleGap[0]}…${h.appleGap.at(-1)}), so Apple's figures came from the App Store Connect API${a ? '' : ' — which was not configured'}.`);
	if (h && !h.play) notes.push(`- Hopps is missing Google Play's installs report for ${h.playGap.join(', ')}, so Play's figures came from the Play bucket${p ? '' : ' — which was not configured'}.`);
	if (h?.apple || h?.play) notes.push(`- ${[h.apple && 'Apple', h.play && 'Google Play'].filter(Boolean).join(' and ')} figures are from Hopps' archive of the stores' own reports (Sales and Trends; Play installs report).`);
	if (a && a.downloads + a.redownloads + a.updates === 0) notes.push('- Apple reported no units for this app in the counted range; check the vendor number and the key\'s Sales access.');
	const appleActive = process.env.APPLE_ACTIVE ? Number(process.env.APPLE_ACTIVE.replace(/,/g, '')) : (h?.appleActive?.value ?? null);
	if (!process.env.APPLE_ACTIVE && h?.appleActive) notes.push(`- Apple active devices is App Analytics' unique devices with sessions on ${h.appleActive.date} (from Hopps).`);
	const webUsage = process.env.WEB_USAGE || (h?.web
		? `The NASB is not offered on the website; it is distributed only inside the app. The app's page on the website had ${fmt(h.web.views)} page views from people${h.web.since > iso(countFrom) ? ` between ${longDate(day(h.web.since))} (when measurement began) and ${longDate(to)}` : ' during the counted period'} (automated crawlers excluded).`
		: null);

	const values = {
		PERIOD_LABEL: `${longDate(from)} – ${longDate(to)}`,
		PERIOD_START: longDate(from),
		PERIOD_END: longDate(to),
		NASB_AVAILABLE_FROM: available ? longDate(available) : '—',
		COUNTED_FROM: longDate(countFrom),
		APPLE_DOWNLOADS: fmt(a?.downloads), APPLE_REDOWNLOADS: fmt(a?.redownloads), APPLE_UPDATES: fmt(a?.updates),
		APPLE_ACTIVE: fmt(appleActive),
		PLAY_DOWNLOADS: fmt(p?.downloads), PLAY_UPDATES: fmt(p?.updates), PLAY_ACTIVE: fmt(p?.active),
		TOTAL_DOWNLOADS: fmt(sum(a?.downloads ?? null, p?.downloads ?? null)),
		TOTAL_UPDATES: fmt(sum(a?.updates ?? null, p?.updates ?? null)),
		TOTAL_ACTIVE: fmt(sum(appleActive, p?.active ?? null)),
		WEB_USAGE: webUsage || 'The NASB is not offered on the website; it is distributed only inside the app.',
		EXTRA_NOTES: (process.env.EXTRA_NOTES || '').trim(),
	};
	const template = readFileSync(TEMPLATE, 'utf8').replace(/^<!--[\s\S]*?-->\s*/, '');
	const report = template.replace(/\{\{([A-Z0-9_]+)\}\}/g, (m, k) => (k in values ? values[k] : m)).replace(/\n{3,}/g, '\n\n');
	const out = args.out || 'lockman-report.md';
	writeFileSync(out, report);
	log(`wrote ${out}`);

	summary(`## Lockman NASB report: ${values.PERIOD_LABEL}\n`);
	summary(`Due by **${longDate(addDays(day(`${to.getUTCFullYear()}-10-01`), 60))}**. The filled-in report is attached to this run as \`lockman-report\`.\n`);
	if (notes.length) { summary('### Check before sending\n'); notes.forEach((n) => summary(n)); }
	notes.forEach((n) => console.log(n.replace(/^- /, '! ')));
}

main().catch((e) => { console.error(`✗ ${e.message}`); summary(`- ❌ ${e.message}`); process.exit(1); });
