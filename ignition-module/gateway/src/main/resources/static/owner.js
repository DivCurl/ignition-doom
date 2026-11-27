/*
 * Ignition DOOM dashboard — owner features: first-run setup, master-password login,
 * Admin Settings panel (play password, max engines, master password), and ending games.
 * Loaded by the landing page (DoomLandingPage). Talks to /system/doom/owner/*.
 *
 * Copyright (C) 2026 Mike Dillmann (DivCurl) — GNU GPL v3 or later.
 */
(function () {
  'use strict';

  var API = '/system/doom/owner/';
  var setupDismissed = false;
  var lastState = null;

  // ── helpers ────────────────────────────────────────────────────────────────
  function $(id) { return document.getElementById(id); }

  function post(action, params) {
    return fetch(API + action, {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams(params || {})
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (d) {
        if (!r.ok) throw new Error(d.error || ('Request failed (' + r.status + ')'));
        return d;
      });
    });
  }

  function getState() {
    var tok = window._playToken;
    return fetch(API + 'state' + (tok ? '?auth=' + encodeURIComponent(tok) : ''), { credentials: 'same-origin' })
      .then(function (r) { if (!r.ok) throw new Error('state ' + r.status); return r.json(); });
  }

  function el(tag, attrs, children) {
    var e = document.createElement(tag);
    Object.keys(attrs || {}).forEach(function (k) {
      if (k === 'text') e.textContent = attrs[k];
      else if (k === 'onclick') e.addEventListener('click', attrs[k]);
      else e.setAttribute(k, attrs[k]);
    });
    (children || []).forEach(function (c) { if (c) e.appendChild(c); });
    return e;
  }

  function field(label, input, hint) {
    return el('div', { 'class': 'field' }, [
      el('label', { text: label }), input,
      hint ? el('div', { 'class': 'o-hint', text: hint }) : null
    ]);
  }

  function input(id, type, attrs) {
    var a = { id: id, type: type || 'text', 'class': 'o-input', autocomplete: 'off' };
    Object.keys(attrs || {}).forEach(function (k) { a[k] = attrs[k]; });
    return el('input', a);
  }

  function msg(id) { return el('div', { id: id, 'class': 'o-msg' }); }

  function showMsg(id, text, ok) {
    var m = $(id);
    if (!m) return;
    m.textContent = text || '';
    m.className = 'o-msg' + (ok ? ' ok' : '');
  }

  function openModal(content) {
    var box = $('ownerBox');
    box.innerHTML = '';
    box.appendChild(content);
    $('ownerModal').classList.add('visible');
    var first = box.querySelector('input:not([type=checkbox])');
    if (first) setTimeout(function () { first.focus(); }, 50);
  }

  function closeModal() { $('ownerModal').classList.remove('visible'); }

  function onEnter(ids, fn) {
    ids.forEach(function (id) {
      var i = $(id);
      if (i) i.addEventListener('keydown', function (e) { if (e.key === 'Enter') fn(); });
    });
  }

  // ── first-run setup / reset (needs the setup code from the gateway log) ──────
  function openSetup(mode) {
    var reset = mode === 'reset';
    openModal(el('div', {}, [
      el('div', { 'class': 'auth-title', text: reset ? 'Reset master password' : 'Welcome to Ignition DOOM' }),
      el('p', { 'class': 'o-text', text: reset
        ? 'Enter the setup code from the gateway log and choose a new master password.'
        : 'Set a master password to finish setup. It unlocks Admin Settings, where you can require a password to play (off by default) and manage running games.' }),
      field('Setup code', input('oCode', 'text', { placeholder: 'XXXX-XXXX-XXXX' }),
        'Printed in the gateway log at every start: look for "DOOM setup code". In Docker: docker logs <container> | grep "setup code"'),
      field('New master password', input('oPw', 'password', { placeholder: 'at least 8 characters' })),
      field('Confirm password', input('oPw2', 'password')),
      msg('oMsg'),
      el('div', { 'class': 'o-actions' }, [
        el('button', { 'class': 'btn o-btn', text: 'Set password', onclick: submitSetup }),
        el('button', { 'class': 'o-link', text: reset ? 'Cancel' : 'Not now', onclick: function () { setupDismissed = true; closeModal(); } })
      ])
    ]));
    onEnter(['oCode', 'oPw', 'oPw2'], submitSetup);
  }

  function submitSetup() {
    var pw = $('oPw').value;
    if (pw !== $('oPw2').value) { showMsg('oMsg', 'Passwords do not match.'); return; }
    showMsg('oMsg', 'Saving…', true);
    post('setup', { code: $('oCode').value, password: pw })
      .then(function (d) { setupDismissed = true; return refreshState().then(function () { openSettings(d.warning); }); })
      .catch(function (e) { showMsg('oMsg', e.message); });
  }

  // ── login ────────────────────────────────────────────────────────────────────
  function openLogin() {
    openModal(el('div', {}, [
      el('div', { 'class': 'auth-title', text: 'Admin Settings' }),
      el('p', { 'class': 'o-text', text: 'Enter the master password.' }),
      input('oLoginPw', 'password', { placeholder: 'MASTER PASSWORD', 'class': 'auth-input' }),
      msg('oMsg'),
      el('div', { 'class': 'o-actions' }, [
        el('button', { 'class': 'btn o-btn', text: 'Log in', onclick: submitLogin }),
        el('button', { 'class': 'o-link', text: 'Cancel', onclick: closeModal })
      ]),
      el('button', { 'class': 'o-link o-small', text: 'Forgot it? Reset with the setup code', onclick: function () { openSetup('reset'); } })
    ]));
    onEnter(['oLoginPw'], submitLogin);
  }

  function submitLogin() {
    showMsg('oMsg', 'Checking…', true);
    post('login', { password: $('oLoginPw').value })
      .then(function () { return refreshState().then(function () { openSettings(); }); })
      .catch(function (e) { showMsg('oMsg', e.message); $('oLoginPw').select(); });
  }

  // ── settings panel ───────────────────────────────────────────────────────────
  function openSettings(warning) {
    if (!lastState || lastState.setupRequired) { openSetup('setup'); return; }
    if (!lastState.owner) { openLogin(); return; }
    var s = lastState;

    var req = el('input', { type: 'checkbox', id: 'sReq' });
    req.checked = !!s.playRequired;
    var pw = input('sPlayPw', 'text', { placeholder: 'no password set', spellcheck: 'false' });
    pw.value = s.playPassword || '';
    var max = input('sMax', 'number', { min: '1', max: '64' });
    max.value = s.maxSessions;

    openModal(el('div', { 'class': 'o-settings' }, [
      el('div', { 'class': 'auth-title', text: 'Admin Settings' }),
      !s.persistent ? el('div', { 'class': 'o-msg', text: 'Settings could not be saved to disk on the gateway; changes last until the next restart.' }) : null,

      el('div', { 'class': 'o-section', text: 'Play password' }),
      el('label', { 'class': 'o-check' }, [req, document.createTextNode(' Require a password to start games')]),
      el('div', { 'class': 'o-row' }, [
        pw,
        el('button', { 'class': 'o-small-btn', text: 'Generate', onclick: function () {
          post('generate').then(function (d) { $('sPlayPw').value = d.password; showMsg('sMsg', 'New password generated - click Save to use it.', true); })
            .catch(function (e) { showMsg('sMsg', e.message); });
        } }),
        el('button', { 'class': 'o-small-btn', text: 'Copy', onclick: function () { copyText($('sPlayPw').value, 'sMsg'); } })
      ]),
      el('div', { 'class': 'o-hint', text: 'Needed to start a single-player game or create a match (case and dashes are ignored). Joining a match needs only its match ID. Changing the password asks players for the new one; games already running are not affected.' }),

      el('div', { 'class': 'o-section', text: 'Engines' }),
      field('Max concurrent games', max, 'Each single-player game or deathmatch player uses one engine.'),

      msg('sMsg'),
      el('div', { 'class': 'o-actions' }, [
        el('button', { 'class': 'btn o-btn', text: 'Save changes', onclick: saveSettings })
      ]),

      el('div', { 'class': 'o-section', text: 'Master password' }),
      el('div', { id: 'sMasterArea' }, [
        el('button', { 'class': 'o-small-btn', text: 'Change master password', onclick: showChangeMaster })
      ]),

      el('div', { 'class': 'o-footer' }, [
        el('button', { 'class': 'o-link', text: 'Log out', onclick: logout }),
        el('button', { 'class': 'o-link', text: 'Close', onclick: closeModal })
      ])
    ]));
    if (warning) showMsg('sMsg', warning);
  }

  function saveSettings() {
    var required = $('sReq').checked;
    var pw = $('sPlayPw').value.trim();
    showMsg('sMsg', 'Saving…', true);
    post('settings', { playRequired: required, playPassword: pw, maxSessions: $('sMax').value })
      .then(function (d) {
        return refreshState().then(function () {
          if (lastState.playPassword) $('sPlayPw').value = lastState.playPassword;
          showMsg('sMsg', d.warning || (required ? 'Saved. Players now need the password.' : 'Saved. No password is needed to play.'), !d.warning);
          if (typeof window.refresh === 'function') window.refresh();
        });
      })
      .catch(function (e) { showMsg('sMsg', e.message); });
  }

  function showChangeMaster() {
    var area = $('sMasterArea');
    area.innerHTML = '';
    area.appendChild(el('div', {}, [
      field('Current password', input('mCur', 'password')),
      field('New password', input('mNew', 'password', { placeholder: 'at least 8 characters' })),
      field('Confirm new password', input('mNew2', 'password')),
      msg('mMsg'),
      el('button', { 'class': 'o-small-btn', text: 'Update master password', onclick: function () {
        if ($('mNew').value !== $('mNew2').value) { showMsg('mMsg', 'Passwords do not match.'); return; }
        post('password', { current: $('mCur').value, password: $('mNew').value })
          .then(function (d) { showMsg('mMsg', d.warning || 'Master password changed. Other browsers were logged out.', !d.warning); })
          .catch(function (e) { showMsg('mMsg', e.message); });
      } })
    ]));
    $('mCur').focus();
  }

  function logout() {
    post('logout').finally(function () { refreshState(); closeModal(); });
  }

  function copyText(text, msgId) {
    if (!text) return;
    var done = function () { showMsg(msgId, 'Copied.', true); };
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(done).catch(function () { fallbackCopy(text); done(); });
    } else { fallbackCopy(text); done(); }
  }

  function fallbackCopy(text) {   // clipboard API needs HTTPS; plain-HTTP gateways use this
    var t = el('textarea', {}); t.value = text; document.body.appendChild(t); t.select();
    try { document.execCommand('copy'); } catch (e) { /* ignore */ }
    document.body.removeChild(t);
  }

  // ── ending games (owner only) ────────────────────────────────────────────────
  window.ownerEndButton = function (kind, id) {
    if (!window._owner) return '';
    return " <button class='o-end-btn' data-kind='" + kind + "' data-id='" + window.esc(id) + "'>End</button>";
  };

  document.addEventListener('click', function (e) {
    var b = e.target.closest ? e.target.closest('.o-end-btn') : null;
    if (!b) return;
    e.stopPropagation();
    var kind = b.getAttribute('data-kind');
    if (!confirm('End this ' + (kind === 'match' ? 'match' : 'game') + '? Anyone playing it will be disconnected.')) return;
    var p = {}; p[kind] = b.getAttribute('data-id');
    post('end', p).then(function () { if (window.refresh) window.refresh(); })
      .catch(function (err) { alert(err.message); });
  });

  // ── state: decides which prompts to show ─────────────────────────────────────
  function refreshState() {
    return getState().then(function (s) {
      var wasOwner = window._owner;
      lastState = s;
      window._owner = !!s.owner;
      window._playRequired = !!s.playRequired;
      if (!s.playTokenValid && window._playToken) {        // revoked or from before a restart
        window._playToken = null;
        sessionStorage.removeItem('doomPlayToken');
      }
      // The password prompt is shown only when someone starts a game (see needPlayPassword);
      // close it if the password was just turned off.
      if (!s.playRequired) window.hideAuthOverlay();
      if (wasOwner !== window._owner && window.refresh) window.refresh();   // show/hide End buttons
      return s;
    });
  }

  window.ownerRefreshState = refreshState;
  window.openOwnerSettings = function () {
    refreshState().then(function () { openSettings(); }).catch(function () { openLogin(); });
  };

  refreshState().then(function (s) {
    if (s.setupRequired && !setupDismissed) openSetup('setup');
    else if (/[?&]settings=1/.test(location.search)) openSettings();
  }).catch(function (e) { console.warn('Owner state unavailable:', e); });
})();
