// The coach layer for getting an ESV API key on Crossway's own pages. ONE copy, used by iOS
// (ScriptureAlone/Translations/ESVKeyGuideScript.swift) and Android (copied into assets by
// android/app/build.gradle.kts). The host app sets `window.__saT` (the card text) first and, in
// debug builds only, `window.__saDev` so a local test page can stand in for Crossway.
// Messages go to `window.webkit.messageHandlers.guide.postMessage(obj)`; Android shims that.
(function () {
  if (window.__saGuide) { window.__saGuide.schedule(); return; }
  var T = window.__saT || {};
  var KEY_RE = /\b[0-9a-f]{40}\b/i;

  var host, root, spot, arrow, card, titleEl, bodyEl, btnEl, skipEl, blockers = [];
  var current = null, signature = '', off = false, locked = false, force = false, raf = 0, timer = 0, pin = null, pinAt = 0, typedEl = null, sawCaptcha = false, autoDone = false;
  var sentKey = '';

  function vis(e) {
    if (!e || !e.getClientRects || !e.getClientRects().length) return false;
    var s = getComputedStyle(e);
    return s.visibility !== 'hidden' && s.display !== 'none';
  }
  function labelOf(e) {
    var t = '';
    if (e.id) { var l = document.querySelector('label[for="' + e.id + '"]'); if (l) t = l.textContent; }
    if (!t) { var c = e.closest('label'); if (c) t = c.textContent; }
    if (!t) t = e.getAttribute('aria-label') || e.placeholder || e.name || '';
    return t.replace(/\s+/g, ' ').replace(/[*:]/g, '').trim();
  }
  function setValue(e, v) {
    var proto = e.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype
      : e.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, v);
    e.dispatchEvent(new Event('input', { bubbles: true }));
    e.dispatchEvent(new Event('change', { bubbles: true }));
  }
  function mainForm() {
    return [].slice.call(document.forms).filter(function (f) { return !/search/i.test(f.action); })[0];
  }
  function submitOf(f) {
    return f && (f.querySelector('[type=submit]') || f.querySelector('button:not([type=button])'));
  }
  function captchaBox(f) {
    var box = f && f.querySelector('.g-recaptcha, [data-sitekey]');
    var resp = f && f.querySelector('[name=g-recaptcha-response]');
    if (!box || !vis(box) || box.getBoundingClientRect().width < 10) return null;
    sawCaptcha = true;
    return resp && resp.value ? null : box;
  }
  function firstEmpty(f, names) {
    for (var i = 0; i < names.length; i++) {
      var e = f.elements[names[i]];
      if (e && vis(e) && !e.value) return e;
    }
    return null;
  }
  function findKey() {
    var inputs = document.querySelectorAll('input, textarea');
    for (var i = 0; i < inputs.length; i++) {
      var m = inputs[i].value && inputs[i].value.match(KEY_RE);
      if (m && inputs[i].type !== 'hidden') return { el: inputs[i], value: m[0] };
    }
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    var n;
    while ((n = w.nextNode())) {
      var mm = n.nodeValue.match(KEY_RE);
      if (mm && n.parentElement && !/SCRIPT|STYLE/.test(n.parentElement.tagName)) {
        return { el: n.parentElement, value: mm[0] };
      }
    }
    return null;
  }

  /// One automatic submit per page per session: if Crossway sends the form back with an error, the
  /// reader fixes it themselves instead of the guide resubmitting in a loop.
  function alreadyAuto() {
    var k = 'saAuto:' + location.pathname;
    try { if (sessionStorage.getItem(k)) return true; sessionStorage.setItem(k, '1'); } catch (e) {}
    return false;
  }
  function agree(box) {
    if (box && vis(box) && !box.checked && !box.dataset.saAgreed) { box.dataset.saAgreed = '1'; box.click(); }
  }

  // MARK: Stepping back and forth

  /// Every stop on this page's form, in reading order, shaped like a plan's `el`.
  function stops() {
    var f = mainForm();
    if (!f) return [];
    var out = [];
    [].slice.call(f.elements).forEach(function (e) {
      if (!/^(INPUT|TEXTAREA|SELECT)$/.test(e.tagName) || !vis(e)) return;
      if (['hidden', 'submit', 'button'].indexOf(e.type) >= 0 || e.name === 'g-recaptcha-response') return;
      out.push({ el: e.type === 'checkbox' ? (e.closest('label') || e) : e, input: e });
    });
    var box = f.querySelector('.g-recaptcha, [data-sitekey]');
    if (box && vis(box) && box.getBoundingClientRect().width >= 10) out.push({ el: box, captcha: true });
    var sub = submitOf(f);
    if (sub) out.push({ el: sub, submit: true });
    return out;
  }
  function stopPlan(st) {
    if (st.captcha) return { id: 'pin-captcha', el: st.el, title: T.captchaTitle, body: T.captchaBody, open: true };
    if (st.submit) {
      return /^\/login/.test(location.pathname)
        ? { id: 'pin-submit', el: st.el, title: T.signInTitle, body: T.tapThis, button: accountButton() }
        : { id: 'pin-submit', el: st.el, title: T.submitTitle, body: T.submitBody };
    }
    var i = st.input, id = 'pin-' + i.name;
    if (i.type === 'checkbox') return { id: id, el: st.el, title: T.tosTitle, body: T.tosBody };
    if (/^\/login/.test(location.pathname)) {
      var email = i.name === 'email';
      return { id: id, el: st.el, title: email ? T.emailTitle : T.passTitle,
               body: email ? T.emailBody : T.passBody, button: accountButton() };
    }
    return { id: id, el: st.el, title: T.fillTitle, body: T.fillBody.replace('%@', labelOf(i)) };
  }
  function accountButton() { return { label: T.needAccount, href: T.registerURL }; }
  /// Moves the guide one stop back (-1) or forward (+1). Past the end it hands control back to the
  /// automatic plan, which goes to the first thing still left to do.
  function step(dir) {
    var list = stops(), at = -1;
    for (var i = 0; i < list.length; i++) if (current && list[i].el === current.el) at = i;
    if (at < 0) at = dir < 0 ? list.length : -1;
    var to = at + dir;
    if (to < 0) return;
    if (to >= list.length) { pin = null; force = true; schedule(); return; }
    pin = stopPlan(list[to]); pin.path = location.pathname; pinAt = Date.now();
    force = true; schedule();
  }
  function atEdge(a, key) {
    // Some input types (email) expose no caret; treat only an empty one as at either edge.
    if (a.selectionStart === null || a.selectionStart === undefined) return a.value === '';
    var start = a.selectionStart === 0 && a.selectionEnd === 0;
    var end = a.selectionStart === a.value.length && a.selectionEnd === a.value.length;
    return key === 'ArrowLeft' ? start : end;
  }

  // MARK: What to do on this page

  function loginPlan(f) {
    var email = firstEmpty(f, ['email']);
    var more = { label: T.needAccount, href: T.registerURL };
    if (email) return { id: 'email', el: email, title: T.emailTitle, body: T.emailBody, button: more };
    var pass = firstEmpty(f, ['password']);
    if (pass) return { id: 'pass', el: pass, title: T.passTitle, body: T.passBody, button: more };
    return { id: 'signin', el: submitOf(f), title: T.signInTitle, body: T.tapThis, button: more };
  }
  function registerPlan(f) {
    var e = firstEmpty(f, ['first_name', 'last_name', 'email', 'confirm_email', 'password1', 'password2']);
    if (e) return { id: 'reg-' + e.name, el: e, title: T.fillTitle, body: T.fillBody.replace('%@', labelOf(e)) };
    // Turning on "Use the ESV online" is the reader's consent to agree to Crossway's terms for them.
    agree(f.elements['tos']);
    var c = captchaBox(f);
    if (c) return { id: 'captcha', el: c, title: T.captchaTitle, body: T.captchaBody, open: true };
    // Submit on their behalf once the check is solved. Without a check, the reader presses it, so
    // the form is never sent while they are still typing.
    return { id: 'reg-submit', el: submitOf(f), title: T.submitTitle, body: T.submitBody, auto: sawCaptcha };
  }
  /// Crossway's application asks three multiple-choice questions. The answers are true of this app:
  /// it is for personal reading, it stays within the API guidelines, and it has no AI in it.
  /// The order matters: the AI question also contains "use", and the guidelines question "use" too.
  function answer(sel) {
    if (sel.value || sel.dataset.saFilled) return;
    var l = (labelOf(sel) + ' ' + sel.name).toLowerCase(), want = null;
    if (/\bai\b/.test(l)) want = /^no$/i;
    else if (/guideline|api3/.test(l)) want = /^yes$/i;
    else if (/intended use|intended_use/.test(l)) want = /personal study/i;
    if (!want) return;
    for (var i = 0; i < sel.options.length; i++) {
      if (want.test(sel.options[i].text.trim())) {
        sel.dataset.saFilled = '1';
        setValue(sel, sel.options[i].value);
        return;
      }
    }
  }

  function applyPlan(f) {
    var fields = [].slice.call(f.elements).filter(function (e) {
      return /^(INPUT|TEXTAREA|SELECT)$/.test(e.tagName) &&
        ['hidden', 'submit', 'button'].indexOf(e.type) < 0 && e.name !== 'g-recaptcha-response' && vis(e);
    });
    // Facts about this app are filled in once; the reader can still change them.
    fields.forEach(function (e) {
      if (e.tagName === 'SELECT') { answer(e); return; }
      if (e.dataset.saFilled || e.value || e.type === 'checkbox' || e.type === 'radio') return;
      var l = (labelOf(e) + ' ' + e.name).toLowerCase(), v = '';
      if (/url|website|site/.test(l)) v = T.appURL;
      else if (/descri|purpose|about|intend|use/.test(l) && e.tagName === 'TEXTAREA') v = T.appDescription;
      else if (/descri|purpose|about|intend/.test(l)) v = T.appDescription;
      else if (/name|title/.test(l) && !/first|last|user|email/.test(l)) v = T.appName;
      if (v) { e.dataset.saFilled = '1'; setValue(e, v); }
    });
    for (var i = 0; i < fields.length; i++) {
      var e = fields[i];
      if (e.type === 'checkbox') {
        if (!e.checked && (e.required || /term|agree|accept/i.test(labelOf(e)))) agree(e);
      } else if (e.tagName === 'SELECT') {
        if (!e.value) return { id: 'app-' + e.name, el: e, title: T.fillTitle, body: T.fillBody.replace('%@', labelOf(e)) };
      } else if (e.type !== 'radio' && !e.value) {
        return { id: 'app-' + e.name, el: e, title: T.fillTitle, body: T.fillBody.replace('%@', labelOf(e)) };
      }
    }
    var c = captchaBox(f);
    if (c) return { id: 'captcha', el: c, title: T.captchaTitle, body: T.captchaBody, open: true };
    return { id: 'app-submit', el: submitOf(f), title: T.submitTitle, body: T.submitBody, auto: true };
  }

  function plan() {
    var h = location.hostname, p = location.pathname;
    if (!window.__saDev && !/(^|\.)esv\.org$/.test(h)) return null;
    if (locked) return null;
    var onApi = window.__saDev ? !/^\/account\/register/.test(p) : h === 'api.esv.org';
    var f = mainForm();
    // Browsers hide the caret of type=email fields, which the arrow keys need to know. A text field
    // with the email keyboard behaves the same for the reader, and Crossway still validates it.
    if (f) [].slice.call(f.querySelectorAll('input[type=email]')).forEach(function (e) {
      e.type = 'text'; e.inputMode = 'email'; e.setAttribute('autocapitalize', 'off'); e.setAttribute('autocorrect', 'off');
    });
    if (onApi && !/^\/login/.test(p)) {
      var k = findKey();
      if (k) return { id: 'key', el: k.el, key: k.value, title: T.keyTitle, body: T.keyBody };
    }
    if (pin && pin.el && vis(pin.el) && pin.path === p) return pin;
    // Follow the field the reader is typing in, even if they got there before the guide did.
    if (typedEl && typedEl === document.activeElement && vis(typedEl)) {
      var here = stops().filter(function (st) { return st.input === typedEl; })[0];
      if (here) return stopPlan(here);
    }
    if (f && /^\/login/.test(p)) return loginPlan(f);
    if (f && /\/account\/register/.test(p)) return registerPlan(f);
    if (f && onApi && /create-application/.test(p)) return applyPlan(f);
    var link = onApi && document.querySelector('a[href*="create-application"]');
    if (link && vis(link)) return { id: 'link', el: link, title: T.fillTitle, body: T.tapThis };
    return { id: 'unknown', el: null, title: T.verifyTitle, body: T.verifyBody,
             button: { label: T.continue, href: T.createURL } };
  }

  // MARK: Drawing

  function build() {
    host = document.createElement('div');
    host.style.cssText = 'all:initial;position:fixed;inset:0;z-index:1000000;pointer-events:none;';
    root = host.attachShadow({ mode: 'open' });
    root.innerHTML =
      '<style>' +
      ':host{all:initial}' +
      '*{box-sizing:border-box;font-family:-apple-system,BlinkMacSystemFont,system-ui,sans-serif}' +
      '.spot{position:fixed;border-radius:12px;box-shadow:0 0 0 200vmax rgba(6,9,20,.72);pointer-events:none;' +
      'transition:top .35s cubic-bezier(.2,.8,.2,1),left .35s cubic-bezier(.2,.8,.2,1),width .35s cubic-bezier(.2,.8,.2,1),height .35s cubic-bezier(.2,.8,.2,1)}' +
      '.spot::after{content:"";position:absolute;inset:-4px;border:3px solid #ffd35a;border-radius:15px;animation:pulse 1.3s ease-in-out infinite}' +
      '@keyframes pulse{0%,100%{box-shadow:0 0 0 0 rgba(255,211,90,.75)}50%{box-shadow:0 0 0 12px rgba(255,211,90,0)}}' +
      '.block{position:fixed;pointer-events:auto;background:transparent}' +
      '.arrow{position:fixed;font-size:30px;line-height:1;color:#ffd35a;text-shadow:0 2px 6px rgba(0,0,0,.6);pointer-events:none;animation:bob .85s ease-in-out infinite}' +
      '@keyframes bob{0%,100%{transform:translateY(0)}50%{transform:translateY(7px)}}' +
      '.arrow.up{animation-name:bobup}@keyframes bobup{0%,100%{transform:translateY(0)}50%{transform:translateY(-7px)}}' +
      '.card{z-index:5;position:fixed;left:12px;right:12px;margin:0 auto;max-width:460px;padding:14px 16px;border-radius:16px;' +
      'background:#fff;color:#14161c;box-shadow:0 10px 36px rgba(0,0,0,.45);pointer-events:auto;font-size:15px;line-height:1.35}' +
      '.card h1{margin:0 0 3px;font-size:17px;font-weight:700}.card p{margin:0}' +
      '.row{display:flex;gap:10px;align-items:center;justify-content:space-between;margin-top:10px}' +
      'button{font:inherit;border:0;border-radius:10px;padding:8px 14px;background:#2f6bff;color:#fff;font-weight:600}' +
      '.skip{background:none;color:#6b7280;font-weight:500;padding:8px 2px}' +
      '.nav{background:#e8ecf5;color:#2f6bff;min-width:44px;min-height:44px;padding:0 12px;font-size:24px;line-height:1;touch-action:manipulation}' +
      'button{touch-action:manipulation;min-height:44px}' +
      '.navs{display:flex;gap:8px;align-items:center}' +
      '@media (prefers-color-scheme:dark){.card{background:#1d2028;color:#f2f4f8}.skip{color:#9aa3b2}.nav{background:#2b3140}}' +
      '</style>' +
      '<div class="spot" hidden></div><div class="arrow" hidden></div>' +
      '<div class="card" hidden><h1></h1><p></p><div class="row"><div class="navs"><button class="nav back" aria-label="Back">\u2039</button><button class="nav fwd" aria-label="Next">\u203A</button><button class="skip"></button></div><button class="go" hidden></button></div></div>';
    spot = root.querySelector('.spot'); arrow = root.querySelector('.arrow'); card = root.querySelector('.card');
    titleEl = card.querySelector('h1'); bodyEl = card.querySelector('p');
    skipEl = card.querySelector('.skip'); btnEl = card.querySelector('.go');
    skipEl.textContent = T.skip;
    card.querySelector('.back').addEventListener('click', function () { step(-1); });
    card.querySelector('.fwd').addEventListener('click', function () { step(1); });
    skipEl.addEventListener('click', function () { post({ event: 'skip' }); destroy(); });
    btnEl.addEventListener('click', function () {
      var b = current && current.button;
      if (current && current.retry) { sentKey = ''; locked = false; schedule(); }
      else if (b && b.href) location.href = b.href;
    });
    for (var i = 0; i < 4; i++) {
      var d = document.createElement('div'); d.className = 'block'; d.hidden = true;
      root.appendChild(d); blockers.push(d);
    }
    (document.body || document.documentElement).appendChild(host);
  }
  function post(m) {
    try { window.webkit.messageHandlers.guide.postMessage(m); } catch (e) {}
  }

  function bottomGap() { return 'max(12px, env(safe-area-inset-bottom))'; }
  function place() {
    raf = 0;
    if (!current || off) return;
    var el = current.el, vw = innerWidth, vh = innerHeight;
    var r = el && vis(el) ? el.getBoundingClientRect() : null;
    if (r) {
      var pad = 6;
      var top = Math.max(0, r.top - pad), left = Math.max(0, r.left - pad);
      var w = Math.min(vw - left, r.width + pad * 2), h = Math.min(vh - top, r.height + pad * 2);
      spot.hidden = false;
      spot.style.top = top + 'px'; spot.style.left = left + 'px'; spot.style.width = w + 'px'; spot.style.height = h + 'px';
      // Everything outside the spotlight is inert, except while a third-party challenge must be reachable.
      var rects = current.open ? [] : [
        [0, 0, vw, top], [0, top + h, vw, vh - top - h], [0, top, left, h], [left + w, top, vw - left - w, h]];
      blockers.forEach(function (b, i) {
        var q = rects[i];
        b.hidden = !q;
        if (q) { b.style.left = q[0] + 'px'; b.style.top = q[1] + 'px'; b.style.width = q[2] + 'px'; b.style.height = q[3] + 'px'; }
      });
      var below = top < vh * 0.45;
      arrow.hidden = false;
      arrow.className = 'arrow' + (below ? ' up' : '');
      arrow.textContent = below ? '\u25B2' : '\u25BC';
      arrow.style.left = Math.min(vw - 30, Math.max(6, left + w / 2 - 10)) + 'px';
      arrow.style.top = (below ? top + h + 8 : Math.max(4, top - 40)) + 'px';
      card.style.top = below ? 'auto' : 'max(12px, env(safe-area-inset-top))';
      card.style.bottom = below ? bottomGap() : 'auto';
    } else {
      spot.hidden = true; arrow.hidden = true;
      blockers.forEach(function (b) { b.hidden = true; });
      card.style.top = 'auto'; card.style.bottom = bottomGap();
    }
    raf = requestAnimationFrame(place);
  }

  function render(p) {
    if (off) return;
    if (!host) build();
    if (!host.isConnected) (document.body || document.documentElement).appendChild(host);
    current = p;
    if (!p) { host.style.display = 'none'; return; }
    host.style.display = '';
    var sig = p.id + '|' + location.pathname;
    titleEl.textContent = p.title || '';
    bodyEl.textContent = p.body || '';
    var b = p.button;
    btnEl.hidden = !b;
    if (b) btnEl.textContent = b.label;
    card.hidden = false;
    if (sig !== signature) {
      signature = sig;
      if (p.el) {
        try { p.el.scrollIntoView({ block: 'center', behavior: 'smooth' }); } catch (e) {}
        if (/^(INPUT|TEXTAREA)$/.test(p.el.tagName) && p.el.type !== 'checkbox') {
          try { p.el.focus({ preventScroll: true }); } catch (e) {}
        }
      }
    }
    if (p.auto && !autoDone) {
      autoDone = true;
      setTimeout(function () {
        var f = mainForm(), go = submitOf(f);
        if (off || !f || !go || pin) return;
        // A button that is still disabled means the form is not ready yet: try again later,
        // without spending the one automatic submit.
        if (go.disabled) { autoDone = false; return; }
        if (alreadyAuto()) return;
        try { if (f.requestSubmit) f.requestSubmit(go); else go.click(); } catch (e) { go.click(); }
      }, 900);
    }
    if (p.key && p.key !== sentKey) {
      sentKey = p.key; locked = false;
      post({ event: 'key', value: p.key });
    }
    if (!raf) raf = requestAnimationFrame(place);
  }

  // A field the reader is typing in stays the current step until they leave it (blur) or something
  // such as a password manager commits a value (`change`), so the guide never jumps mid-word.
  function typing() {
    var a = document.activeElement, e = current && current.el;
    return !force && a && a === e && /^(INPUT|TEXTAREA)$/.test(a.tagName) &&
      ['checkbox', 'radio', 'submit', 'button'].indexOf(a.type) < 0;
  }
  function tick() {
    timer = 0;
    var hold = typing();
    force = false;
    if (!off && !locked && !hold) { try { render(plan()); } catch (e) { post({ event: 'error', message: String(e) }); } } }
  function schedule() { if (!timer) timer = setTimeout(tick, 120); }
  function destroy() {
    off = true; if (raf) cancelAnimationFrame(raf);
    if (host && host.parentNode) host.parentNode.removeChild(host);
  }

  ['input', 'click', 'keyup', 'load'].forEach(function (t) { addEventListener(t, schedule, true); });
  // Only real keystrokes and pastes count as typing; a password manager's fill does not, so a
  // filled-in field is still moved past.
  addEventListener('input', function (ev) {
    if (ev.isTrusted && /^(insertText|insertFromPaste|deleteContent)/.test(ev.inputType || '')) typedEl = ev.target;
  }, true);
  ['change', 'focusout'].forEach(function (t) {
    addEventListener(t, function (ev) {
      var moved = function () {
        if (Date.now() - pinAt > 400) pin = null;   // focus moved by stepping is not "moving on"
        force = true; schedule();
      };
      if (ev.type === 'focusout') {
        // Switching to another window or app also fires focusout, but the field is still the
        // active element. Only a real move to something else counts as leaving it.
        setTimeout(function () { if (document.activeElement !== ev.target) moved(); }, 0);
      } else {
        moved();
      }
    }, true);
  });
  // The reader just chose these for their new Crossway account. They go to the app, which keeps them
  // in memory only long enough to sign in on the developer site, then forgets them.
  function reportCredentials() {
    var f = mainForm();
    if (!f || !/^\/account\/register\/?$/.test(location.pathname)) return;
    var email = f.elements['email'], pass = f.elements['password1'];
    if (email && pass && email.value && pass.value) post({ event: 'creds', email: email.value, password: pass.value });
  }
  addEventListener('submit', reportCredentials, true);
  addEventListener('click', function (ev) {
    var b = ev.target && ev.target.closest && ev.target.closest('button[type=submit], input[type=submit]');
    if (b) reportCredentials();
  }, true);
  addEventListener('keydown', function (ev) {
    var horizontal = ev.key === 'ArrowLeft' || ev.key === 'ArrowRight';
    var vertical = ev.key === 'ArrowUp' || ev.key === 'ArrowDown';
    if (off || !(horizontal || vertical)) return;
    if (ev.shiftKey || ev.metaKey || ev.altKey || ev.ctrlKey) return;
    var a = document.activeElement;
    // Menus and multi-line boxes use these keys themselves.
    if (a && /^(SELECT|TEXTAREA)$/.test(a.tagName)) return;
    // Left/right also move the caret, so inside a text field they only step at its edges.
    // Up/down do nothing in a one-line field, so they always step.
    if (horizontal && a && a.tagName === 'INPUT' &&
        ['checkbox', 'radio', 'submit', 'button'].indexOf(a.type) < 0 && !atEdge(a, ev.key)) return;
    ev.preventDefault();
    step(ev.key === 'ArrowLeft' || ev.key === 'ArrowUp' ? -1 : 1);
  }, true);
  new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
  setInterval(schedule, 700);   // a solved reCAPTCHA changes a value without any event
  window.__saGuide = {
    schedule: schedule, destroy: destroy,
    /// Types the credentials into the developer-site login and submits it. Returns false when the
    /// page is not that login form, so the app can leave the credentials unused.
    fillLogin: function (email, password) {
      var f = mainForm();
      if (!f || !f.elements['email'] || !f.elements['password']) return false;
      setValue(f.elements['email'], email);
      setValue(f.elements['password'], password);
      locked = true;
      render({ id: 'signing', el: null, title: T.signingTitle, body: T.signingBody });
      var go = submitOf(f);
      if (f.requestSubmit) f.requestSubmit(go); else f.submit();
      return true;
    },
    badKey: function () {
      locked = true;
      current = { id: 'bad', el: null, title: T.badKeyTitle, body: T.badKeyBody, button: { label: T.retry }, retry: true };
      render(current);
    },
  };
  schedule();
})();
