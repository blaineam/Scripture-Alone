#!/usr/bin/env node
/**
 * play-listing.mjs — upload the Google Play store listing (text, and optionally images) for every
 * Play locale in ONE edit. Run by .github/workflows/play-listing.yml; see docs/RELEASING.md.
 *
 *   node scripts/play-listing.mjs [--dry-run] [--images <root>] [--locales en-US,de-DE]
 *                                 [--validate-only] [--package <id>] [--key-file <path>]
 *
 * Text: `## title` (≤ 30), `## short_description` (≤ 80) and `## full_description` (≤ 4000) from
 * android/play-metadata.md (en-US) and android/play-metadata.<locale>.md (the file's suffix is the
 * Play language code: de-DE, es-ES, fr-FR, it-IT, ja-JP, ko-KR, pt-BR, zh-CN). HTML comments are
 * dropped. Limits are counted in characters (Unicode code points, as Python's len), never bytes, and
 * no price words are allowed (the same list as cut-release.yml / android.yml).
 *
 * Images (only with --images <root>): per locale, <root>/<locale>/<type>/*.png|jpg, uploaded in
 * file-name order. <type> is the Play image type or its short alias:
 *     phoneScreenshots      (phone)          2–8, each side 320–3840 px, long side ≤ 2× short
 *     sevenInchScreenshots  (tablet-7in)     1–8, same size rules
 *     tenInchScreenshots    (tablet-10in)    1–8, same size rules
 *     wearScreenshots       (wear)           1–8, square, ≥ 384 px
 *     featureGraphic        (feature-graphic)  exactly one, 1024 × 500
 *     icon                  (icon)           exactly one, 512 × 512 PNG
 * featureGraphic and icon may also be single files in the locale folder: feature-graphic.png /
 * featureGraphic.png, icon.png / icon-512.png. For en-US only, when <root>/en-US does not exist,
 * <root> itself is read (so android/play-assets/{phone,tablet-7in,tablet-10in,wear} is the English
 * set). A type that has no folder is left as it is on Play; a locale with no folder keeps its
 * images (Play shows the default language's images for a locale that has none). An image type whose
 * files already match Play's (same SHA-256s in the same order) is not touched.
 *
 * --dry-run opens an edit, compares, prints what would change and deletes the edit — nothing is
 * committed. --validate-only checks the files and never talks to Play (no credentials needed).
 * Otherwise every change goes into one edit, which is validated and committed — or, on any error,
 * deleted, so Play is left exactly as it was.
 *
 * The service account needs the Play Console permission "Manage store presence" for this app.
 */
import { readFileSync, existsSync, readdirSync, statSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join, extname, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { UPLOAD, die, fail, log, summary, useServiceAccount, api, base, openEdit, deleteEdit } from './play-api.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const META_DIR = join(ROOT, 'android');
const LIMITS = { title: 30, shortDescription: 80, fullDescription: 4000 };
const PRICE = /(?<![-\w])(free|price|pricing|priced|discount|sale|cheap|gratis|kostenlos|gratuit|gratuite|gratuito|grátis)(?![-\w])|\$\s?\d|無料|免费|免費|무료|価格|价格|가격/i;
const chars = (s) => [...s].length;

const IMAGE_TYPES = {
	phoneScreenshots: { alias: 'phone', min: 2, max: 8, kind: 'screenshot' },
	sevenInchScreenshots: { alias: 'tablet-7in', min: 1, max: 8, kind: 'screenshot' },
	tenInchScreenshots: { alias: 'tablet-10in', min: 1, max: 8, kind: 'screenshot' },
	wearScreenshots: { alias: 'wear', min: 1, max: 8, kind: 'wear' },
	featureGraphic: { alias: 'feature-graphic', min: 1, max: 1, kind: 'feature', files: ['feature-graphic.png', 'featureGraphic.png', 'feature-graphic.jpg', 'featureGraphic.jpg'] },
	icon: { alias: 'icon', min: 1, max: 1, kind: 'icon', files: ['icon.png', 'icon-512.png'] },
};

function parseArgs(argv) {
	const a = { pkg: 'com.blainemiller.scripturealone', dryRun: false, validateOnly: false, images: null, locales: null };
	for (let i = 2; i < argv.length; i++) {
		const v = () => argv[++i];
		switch (argv[i]) {
			case '--package': a.pkg = v(); break;
			case '--key-file': a.keyFile = v(); break;
			case '--images': a.images = v(); break;
			case '--locales': a.locales = v().split(',').map((x) => x.trim()).filter(Boolean); break;
			case '--dry-run': a.dryRun = true; break;
			case '--validate-only': a.validateOnly = true; break;
			case '-h': case '--help': console.log(readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/')[0]); process.exit(0);
			default: die(`unknown arg ${argv[i]} (see the header of scripts/play-listing.mjs)`);
		}
	}
	if (a.images && !existsSync(a.images)) die(`--images ${a.images} does not exist`);
	return a;
}

// ── text ──────────────────────────────────────────────────────────────────────────────
function section(md, key) {
	const m = md.match(new RegExp(`^##\\s+${key}\\s*$\\n([\\s\\S]*?)(?=^##\\s|(?![\\s\\S]))`, 'm'));
	return m ? m[1].replace(/<!--[\s\S]*?-->/g, '').trim() : null;
}

function readListings(only) {
	const out = [];
	for (const f of readdirSync(META_DIR).sort()) {
		const m = f.match(/^play-metadata(?:\.([A-Za-z]{2,3}(?:-[A-Za-z0-9]+)*))?\.md$/);
		if (!m) continue;
		const language = m[1] || 'en-US';
		if (only && !only.includes(language)) continue;
		const md = readFileSync(join(META_DIR, f), 'utf8');
		out.push({
			language, file: `android/${f}`,
			title: section(md, 'title'), shortDescription: section(md, 'short_description'), fullDescription: section(md, 'full_description'),
		});
	}
	out.sort((x, y) => (x.language === 'en-US' ? -1 : y.language === 'en-US' ? 1 : x.language.localeCompare(y.language)));
	return out;
}

function checkText(l, errors) {
	for (const [k, cap] of Object.entries(LIMITS)) {
		const v = l[k];
		if (!v) { errors.push(`${l.file}: no ${k} section`); continue; }
		if (chars(v) > cap) errors.push(`${l.file}: ${k} is ${chars(v)} characters (max ${cap})`);
		const hit = v.match(PRICE);
		if (hit) errors.push(`${l.file}: ${k} mentions ${JSON.stringify(hit[0])} — no price words in store text`);
	}
	if (l.title && /\n/.test(l.title)) errors.push(`${l.file}: title spans more than one line`);
	if (l.shortDescription && /\n/.test(l.shortDescription)) errors.push(`${l.file}: short_description spans more than one line`);
}

// ── images ────────────────────────────────────────────────────────────────────────────
function imageInfo(file) {
	const b = readFileSync(file);
	const sha256 = createHash('sha256').update(b).digest('hex');
	if (b.length > 24 && b.readUInt32BE(0) === 0x89504e47 && b.toString('ascii', 12, 16) === 'IHDR') {
		return { file, bytes: b.length, sha256, type: 'image/png', w: b.readUInt32BE(16), h: b.readUInt32BE(20), alpha: b[25] === 6 || b[25] === 4 };
	}
	if (b[0] === 0xff && b[1] === 0xd8) {
		for (let i = 2; i + 9 < b.length;) {
			if (b[i] !== 0xff) { i++; continue; }
			const marker = b[i + 1];
			if (marker >= 0xc0 && marker <= 0xcf && ![0xc4, 0xc8, 0xcc].includes(marker)) {
				return { file, bytes: b.length, sha256, type: 'image/jpeg', w: b.readUInt16BE(i + 7), h: b.readUInt16BE(i + 5), alpha: false };
			}
			i += 2 + b.readUInt16BE(i + 2);
		}
	}
	return { file, bytes: b.length, sha256, type: null };
}

function localeImageDir(root, language) {
	const own = join(root, language);
	if (existsSync(own) && statSync(own).isDirectory()) return own;
	if (language === 'en-US' && Object.values(IMAGE_TYPES).some((t) => existsSync(join(root, t.alias)))) return root;
	return null;
}

function collectImages(dir) {
	const found = {};
	for (const [type, spec] of Object.entries(IMAGE_TYPES)) {
		const sub = [join(dir, type), join(dir, spec.alias)].find((d) => existsSync(d) && statSync(d).isDirectory());
		let files = sub ? readdirSync(sub).filter((f) => /\.(png|jpe?g)$/i.test(f) && !f.startsWith('.')).sort().map((f) => join(sub, f)) : null;
		if (!files && spec.files) {
			const single = spec.files.map((f) => join(dir, f)).find((p) => existsSync(p));
			if (single) files = [single];
		}
		if (files) found[type] = files.map(imageInfo);
	}
	return found;
}

function checkImages(language, type, imgs, errors) {
	const spec = IMAGE_TYPES[type];
	const where = `${language} ${type}`;
	if (imgs.length < spec.min || imgs.length > spec.max) errors.push(`${where}: ${imgs.length} file(s) — Play takes ${spec.min === spec.max ? spec.min : `${spec.min}–${spec.max}`}`);
	for (const im of imgs) {
		const name = im.file.replace(ROOT + '/', '');
		if (!im.type) { errors.push(`${where}: ${name} is not a PNG or JPEG`); continue; }
		const { w, h } = im, lo = Math.min(w, h), hi = Math.max(w, h);
		if (im.bytes > (spec.kind === 'icon' ? 1 : 8) * 1048576) errors.push(`${where}: ${name} is ${(im.bytes / 1048576).toFixed(1)} MB (max ${spec.kind === 'icon' ? 1 : 8} MB)`);
		if (spec.kind === 'screenshot' && (lo < 320 || hi > 3840 || hi > 2 * lo)) errors.push(`${where}: ${name} is ${w}×${h} — sides 320–3840 px, long side at most twice the short`);
		if (spec.kind === 'wear' && (w !== h || w < 384)) errors.push(`${where}: ${name} is ${w}×${h} — Wear OS screenshots are square, at least 384 px`);
		if (spec.kind === 'feature' && (w !== 1024 || h !== 500)) errors.push(`${where}: ${name} is ${w}×${h} — the feature graphic is 1024×500`);
		if (spec.kind === 'icon' && (w !== 512 || h !== 512 || im.type !== 'image/png')) errors.push(`${where}: ${name} must be a 512×512 PNG (is ${w}×${h} ${im.type})`);
	}
}

// ── main ──────────────────────────────────────────────────────────────────────────────
async function main() {
	const a = parseArgs(process.argv);
	const listings = readListings(a.locales);
	if (!listings.length) die(`no android/play-metadata*.md files${a.locales ? ` for ${a.locales.join(', ')}` : ''}`);
	if (a.locales) for (const l of a.locales) if (!listings.some((x) => x.language === l)) die(`no android/play-metadata file for ${l}`);

	const errors = [];
	for (const l of listings) {
		checkText(l, errors);
		log(`${l.language}: title ${chars(l.title || '')}/30, short ${chars(l.shortDescription || '')}/80, full ${chars(l.fullDescription || '')}/4000 characters`);
		if (a.images) {
			const dir = localeImageDir(a.images, l.language);
			l.images = dir ? collectImages(dir) : {};
			for (const [type, imgs] of Object.entries(l.images)) checkImages(l.language, type, imgs, errors);
			const got = Object.entries(l.images).map(([t, i]) => `${t} ${i.length}`).join(', ');
			log(`${l.language}: images ${dir ? `from ${dir.replace(ROOT + '/', '')}: ${got || '(no image folders)'}` : '— no folder, Play keeps its images'}`);
		}
	}
	if (errors.length) {
		for (const e of errors) { console.error(`✗ ${e}`); summary(`- ❌ ${e}`); }
		die(`${errors.length} problem(s) in the listing files — nothing was sent to Play`);
	}
	if (a.validateOnly) { console.log(`✓ ${listings.length} listing(s) valid (validate-only: Play not contacted)`); return; }

	useServiceAccount(a.keyFile);
	const editId = await openEdit(a);
	let committed = false;
	try {
		const remote = new Map(((await api('GET', `${base(a)}/edits/${editId}/listings`))?.listings || []).map((x) => [x.language, x]));
		const changes = [];
		for (const l of listings) {
			const r = remote.get(l.language);
			const fields = Object.keys(LIMITS).filter((k) => (r?.[k] || '').trim() !== l[k]);
			if (!r) changes.push({ l, what: 'text', how: 'PUT', fields });
			else if (fields.length) changes.push({ l, what: 'text', how: 'PATCH', fields });
			for (const [type, imgs] of Object.entries(l.images || {})) {
				const have = (await api('GET', `${base(a)}/edits/${editId}/listings/${l.language}/${type}`))?.images || [];
				const same = have.length === imgs.length && have.every((h, i) => h.sha256 === imgs[i].sha256);
				if (!same) changes.push({ l, what: 'images', type, imgs, had: have.length });
			}
		}
		const line = (c) => c.what === 'text'
			? `${c.l.language}: ${c.how === 'PUT' ? 'new listing' : `update ${c.fields.join(', ')}`}`
			: `${c.l.language}: replace ${c.type} (${c.had} on Play → ${c.imgs.length})`;
		if (!changes.length) {
			console.log('✓ Play already matches the files — nothing to change');
			summary('- ✅ Play listing already matches the files');
			return;
		}
		if (a.dryRun) {
			for (const c of changes) log(`(dry run) would ${line(c)}`);
			summary(`### Play listing — dry run (${changes.length} change(s), nothing committed)`);
			for (const c of changes) summary(`- ${line(c)}`);
			return;
		}

		for (const c of changes) {
			const lang = encodeURIComponent(c.l.language);
			if (c.what === 'text') {
				const body = { language: c.l.language, title: c.l.title, shortDescription: c.l.shortDescription, fullDescription: c.l.fullDescription };
				// PATCH keeps what the files don't carry (the promo video); PUT creates a missing language.
				await api(c.how, `${base(a)}/edits/${editId}/listings/${lang}`, body);
			} else {
				await api('DELETE', `${base(a)}/edits/${editId}/listings/${lang}/${c.type}`);
				for (const im of c.imgs) {
					try {
						await api('POST', `${UPLOAD}/${encodeURIComponent(a.pkg)}/edits/${editId}/listings/${lang}/${c.type}?uploadType=media`,
							readFileSync(im.file), { headers: { 'content-type': im.type }, raw: true });
					} catch (e) { fail(`uploading ${im.file.replace(ROOT + '/', '')} to ${c.l.language} ${c.type}: ${e.message}`); }
				}
			}
			log(line(c));
		}
		await api('POST', `${base(a)}/edits/${editId}:validate`);
		await api('POST', `${base(a)}/edits/${editId}:commit`);
		committed = true;
		console.log(`✓ Play listing: ${changes.length} change(s) committed`);
		summary(`### Play listing — ${changes.length} change(s) committed`);
		for (const c of changes) summary(`- ${line(c)}`);
	} finally {
		if (!committed) await deleteEdit(a, editId);
	}
}

main().catch((e) => die(e.message));
