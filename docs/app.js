(function () {
  'use strict';
  var state = { apps: [], filter: 'all', status: 'all', q: '', doc: null };
  var $ = function (id) { return document.getElementById(id); };

  function fmtDate(iso) {
    if (!iso) return '';
    var d = new Date(iso);
    if (isNaN(d)) return '';
    return d.toLocaleString(undefined, { timeZone: 'America/Los_Angeles', year: 'numeric', month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit' }) + ' PT';
  }
  function el(tag, cls, text) { var e = document.createElement(tag); if (cls) e.className = cls; if (text != null) e.textContent = text; return e; }
  function link(cls, text, href) { var a = el('a', cls, text); a.href = href; a.target = '_blank'; a.rel = 'noopener'; return a; }

  window.AppsHub = { matches: matches };
  function matches(a, q, f, st) {
    if (st && st !== 'all' && (a.status || 'Live') !== st) return false;
    if (f === 'apk' && !a.apk_url) return false;
    if (f === 'web' && !a.web_url) return false;
    if (f === 'both' && !(a.apk_url && a.web_url)) return false;
    q = (q || '').trim().toLowerCase();
    if (!q) return true;
    var hay = [a.name, a.description, a.platform, a.version, a.status, (a.tags || []).join(' ')].join(' ').toLowerCase();
    return q.split(/\s+/).every(function (w) { return hay.indexOf(w) >= 0; });
  }

  function card(a) {
    var c = el('article', 'card');
    var head = el('div', 'head');
    var av = el('div', 'avatar', (a.name || '?').replace(/[^A-Za-z0-9]/, '').charAt(0).toUpperCase() || '?');
    av.style.background = a.color || '#4F46E5';
    var t = el('div');
    t.appendChild(el('div', 'name', a.name));
    var b = el('div', 'badges');
    var st = a.status || 'Live';
    if (st === 'Live') b.appendChild(el('span', 'badge ver', /^\d/.test(a.version) ? 'v' + a.version : a.version));
    else b.appendChild(el('span', 'badge st st-' + st.toLowerCase().replace(/\s+/g, '-'), st));
    b.appendChild(el('span', 'badge', a.platform));
    t.appendChild(b);
    head.appendChild(av); head.appendChild(t); c.appendChild(head);
    c.appendChild(el('p', 'desc', a.description));
    if (a.notes) c.appendChild(el('p', 'note', a.notes));
    if (a.status_note) c.appendChild(el('p', 'note', a.status_note));
    var act = el('div', 'actions');
    if (a.web_url) act.appendChild(link('btn', 'Open web', a.web_url));
    if (a.apk_url) act.appendChild(link('btn' + (a.web_url ? ' ghost' : ''), a.apk_private ? 'Get APK (GitHub login)' : 'Download APK', a.apk_url));
    (a.extra_downloads || []).forEach(function (x) { act.appendChild(link('btn ghost sm', x.label, x.url)); });
    if (a.repo_url) act.appendChild(link('btn ghost sm', 'GitHub', a.repo_url));
    if (act.children.length) c.appendChild(act);
    if (a.last_updated) c.appendChild(el('p', 'upd', 'Updated ' + fmtDate(a.last_updated)));
    return c;
  }

  function render() {
    var list = $('list'); list.textContent = '';
    var shown = state.apps.filter(function (a) { return matches(a, state.q, state.filter, state.status); });
    shown.forEach(function (a) { list.appendChild(card(a)); });
    $('empty').hidden = shown.length > 0;
    $('meta').textContent = shown.length + ' of ' + state.apps.length + ' apps' + (state.doc && state.doc.generated_at ? ' · list refreshed ' + fmtDate(state.doc.generated_at) : '');
    $('aboutMeta').textContent = state.doc && state.doc.generated_at ? 'List refreshed ' + fmtDate(state.doc.generated_at) + '.' : '';
  }

  function load() {
    return fetch('apps.json', { cache: 'no-cache' }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status); return r.json();
    }).then(function (d) { state.doc = d; state.apps = d.apps || []; render(); })
      .catch(function (e) { $('meta').textContent = 'Could not load apps.json (' + e.message + ').'; });
  }

  function applyTheme(t) {
    var root = document.documentElement;
    if (t === 'light' || t === 'dark') root.setAttribute('data-theme', t); else root.removeAttribute('data-theme');
    $('themeBtn').title = 'Theme: ' + (t || 'system');
  }
  var theme = localStorage.getItem('appshub.theme') || 'system';
  applyTheme(theme);
  $('themeBtn').addEventListener('click', function () {
    theme = theme === 'system' ? 'light' : theme === 'light' ? 'dark' : 'system';
    localStorage.setItem('appshub.theme', theme); applyTheme(theme);
  });
  $('aboutBtn').addEventListener('click', function () { var d = $('about'); if (d.showModal) d.showModal(); else d.setAttribute('open', ''); });
  $('q').addEventListener('input', function (e) { state.q = e.target.value; render(); });
  $('statusChips').addEventListener('click', function (e) {
    var s = e.target && e.target.getAttribute('data-s'); if (!s) return;
    state.status = s;
    Array.prototype.forEach.call($('statusChips').children, function (c) { c.classList.toggle('on', c === e.target); });
    render();
  });
  $('chips').addEventListener('click', function (e) {
    var f = e.target && e.target.getAttribute('data-f'); if (!f) return;
    state.filter = f;
    Array.prototype.forEach.call($('chips').children, function (c) { c.classList.toggle('on', c === e.target); });
    render();
  });
  load();
  if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(function () {});
})();
