'use strict';
/* ريموت التليفزيون — الواجهة. الاتصال نفسه في Java (window.TV). */

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));

const SHORT = 3, START_LONG = 1, END_LONG = 2;

/* -------- اللغة: العربي هو الأصل، والإنجليزي من i18n.js -------- */
const LANG = (() => { try { return localStorage.getItem('lang') === 'en' ? 'en' : 'ar'; } catch (_) { return 'ar'; } })();
const EN_MAP = window.I18N_EN || {};
function T(s) {
  if (LANG !== 'en' || typeof s !== 'string') return s;
  const m = s.match(/^(\s*)([\s\S]*?)(\s*)$/);
  const v = EN_MAP[m[2].replace(/\s+/g, ' ')];
  return v === undefined ? s : m[1] + v + m[3];
}
// النصوص الثابتة في الـ HTML
function translateDom(root) {
  if (LANG !== 'en') return;
  const ar = /[؀-ۿ]/;
  const w = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const nodes = [];
  while (w.nextNode()) nodes.push(w.currentNode);
  nodes.forEach(n => { if (ar.test(n.nodeValue) && n.parentNode.nodeName !== 'SCRIPT') n.nodeValue = T(n.nodeValue); });
  root.querySelectorAll('[placeholder],[aria-label],[title]').forEach(el => {
    ['placeholder', 'aria-label', 'title'].forEach(a => { const v = el.getAttribute(a); if (v && ar.test(v)) el.setAttribute(a, T(v)); });
  });
}
translateDom(document.body);
document.title = T(document.title);

/* -------- لو الصفحة مفتوحة في متصفح عادي (للتجربة) بنعمل TV وهمي -------- */
if (!window.TV) {
  const fire = (o, ms = 300) => setTimeout(() => window.onTv(o), ms);
  let devices = [];
  let tabs = [];
  let activeHost = '';
  let paired = false;
  let lgOk = false;
  const guess = (h) => h.endsWith('.31') ? 'lg' : h.endsWith('.50') ? 'rcv' : 'atv';
  const LG_APPS = [
    { id: 'youtube.leanback.v4', title: 'YouTube', icon: '' }, { id: 'netflix', title: 'Netflix', icon: '' },
    { id: 'shahid', title: 'Shahid', icon: '' }, { id: 'com.webos.app.livetv', title: 'Live TV', icon: '' },
    { id: 'com.webos.app.hdmi1', title: 'HDMI 1', icon: '' }, { id: 'amazon', title: 'Prime Video', icon: '' }];
  const LG_INPUTS = [
    { id: 'HDMI_1', label: 'HDMI 1', appId: 'com.webos.app.hdmi1', connected: true },
    { id: 'HDMI_2', label: 'PlayStation', appId: 'com.webos.app.hdmi2', connected: true },
    { id: 'HDMI_3', label: 'HDMI 3', appId: 'com.webos.app.hdmi3', connected: false }];
  const emitTabs = () => fire({ type: 'tabs', list: tabs.map(t => Object.assign({}, t, { active: t.host === activeHost })) }, 0);
  const ensure = (host, name, kind) => {
    let t = tabs.find(x => x.host === host);
    if (t) return t;
    if (tabs.length >= 4) { fire({ type: 'error', msg: T('التابات مليانة (4 أجهزة)') }, 0); return null; }
    t = { host, name, kind: kind || (devices.find(d => d.host === host) || {}).kind || guess(host), state: 'connecting', volume: -1 };
    tabs.push(t);
    devices = [{ host, name, kind: t.kind }].concat(devices.filter(d => d.host !== host));
    return t;
  };
  const replay = (t) => {
    const h = t.host;
    fire({ type: 'kind', kind: t.kind, host: h }, 0);
    fire({ type: 'status', state: t.state, host: h, name: t.name, vendor: t.vendor || '', model: t.model || '', kind: t.kind }, 0);
    if (t.volume >= 0) fire({ type: 'volume', level: String(t.volume), max: '100', muted: '', host: h }, 0);
    fire({ type: 'app', pkg: t.app || '', host: h }, 0);
    fire({ type: 'apps', list: t.kind === 'lg' && t.state === 'connected' ? LG_APPS : [], host: h }, 0);
    fire({ type: 'inputs', list: t.kind === 'lg' && t.state === 'connected' ? LG_INPUTS : [], host: h }, 0);
    if (t.state === 'pair') fire({ type: 'needPair', host: h, name: t.name }, 0);
  };
  const run = (t) => {
    if (t.state === 'connected') return;
    t.state = 'connecting'; emitTabs();
    let delay = 600;
    if (t.kind === 'lg' && !lgOk) { lgOk = true; delay = 2600; fire({ type: 'lgPrompt', host: t.host, name: t.name }, 500); fire({ type: 'paired', host: t.host }, 2500); }
    setTimeout(() => {
      if (t.kind === 'atv' && !paired) { t.state = 'pair'; emitTabs(); if (t.host === activeHost) fire({ type: 'needPair', host: t.host, name: t.name }, 0); return; }
      t.state = 'connected';
      t.vendor = t.kind === 'lg' ? 'LG' : t.kind === 'rcv' ? 'Receiver' : 'Xiaomi';
      t.model = t.kind === 'lg' ? 'OLED55C1' : t.kind === 'rcv' ? t.name : 'MIBOX4';
      t.volume = t.kind === 'rcv' ? -1 : t.kind === 'lg' ? 12 : 9;
      t.app = t.kind === 'lg' ? 'com.webos.app.hdmi2' : t.kind === 'atv' ? 'com.google.android.youtube.tv' : '';
      emitTabs();
      if (t.host === activeHost) replay(t);
    }, delay);
  };
  window.TV = {
    saved: () => JSON.stringify(devices),
    lastHost: () => '',
    tabs: () => JSON.stringify(tabs.map(t => ({ host: t.host, name: t.name, kind: t.kind }))),
    isReady: () => true,
    discover() {
      fire({ type: 'found', host: '192.168.1.23', name: 'Mi Box S' }, 700);
      fire({ type: 'found', host: '192.168.1.31', name: '[LG] webOS TV OLED55C1', kind: 'lg' }, 1100);
      fire({ type: 'found', host: '192.168.1.50', name: 'TIGER T8 HIGH CLASS V2', kind: 'rcv' }, 1300);
      fire({ type: 'found', host: '192.168.1.40', name: '' }, 1500);
      fire({ type: 'scanDone' }, 2500);
    },
    connect(host, name, kind) {
      const t = ensure(host, name, kind); if (!t) return;
      activeHost = host; emitTabs(); replay(t); run(t);
    },
    addTab(host, name, kind) { const t = ensure(host, name, kind); if (!t) return; emitTabs(); run(t); },
    activate(host) { const t = tabs.find(x => x.host === host); if (!t) return; activeHost = host; emitTabs(); replay(t); },
    removeDevice(host) {
      tabs = tabs.filter(x => x.host !== host);
      if (activeHost === host) { activeHost = tabs.length ? tabs[0].host : ''; if (activeHost) replay(tabs[0]); }
      emitTabs();
    },
    disconnect() { if (activeHost) this.removeDevice(activeHost); }, forget() {},
    pairStart(h, n) { fire({ type: 'pairCode' }, 500); },
    pairFinish(code) {
      if (code !== 'A1B2C3') return fire({ type: 'pairError', msg: T('الكود غلط، اتأكد منه'), retry: '1' });
      paired = true; fire({ type: 'paired' }); const t = tabs.find(x => x.host === activeHost); if (t) { t.state = 'offline'; run(t); }
    },
    pairCancel() {},
    key(c) { console.log('key', c); }, keyDir(c, d) { console.log('key', c, d); },
    text(t) { console.log('text', t); }, launch(l) { console.log('launch', l); },
    getMac: () => '', setMac: (m) => /^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i.test(m.trim()),
    diagnostics: () => 'app 1.0.x • lg • 192.168.1.31\n16:20:01  main: wss:3001 OK\n16:20:01  buttons socket OK', share() {},
    switchInput(id) { console.log('input', id); },
    inputsOf(h) { const t = tabs.find(x => x.host === h); return JSON.stringify(t && t.kind === 'lg' && t.state === 'connected' ? LG_INPUTS : []); },
    switchInputOn(h, id) { const t = tabs.find(x => x.host === h); if (!t || t.state !== 'connected') return false; console.log('switchOn', h, id); return true; }, rawKey(k) { console.log('raw', k); },
    pointer(dx, dy) { console.log('pointer', dx, dy); }, click() { console.log('click'); }, scroll() {}, kind: () => state.kind,
    getNotif: () => true, setNotif() {},
    vibrate() {}, setVibrate() {}, getVibrate: () => true, resetIdentity() {},
    setBars() {}, appVersion: () => '1.0.1',
    checkUpdate(manual) { fire(manual || location.hash === '#update' ? { type: 'update', current: '1.0.1', latest: '1.0.2' } : { type: 'noUpdate', manual: '' }, 900); },
    startUpdate() { let p = 0; const t = setInterval(() => { p += 20; window.onTv({ type: 'updateProgress', pct: String(p) }); if (p >= 100) clearInterval(t); }, 300); },
  };
}

const state = { host: '', name: '', kind: '', connected: false, sheet: null, ready: false, tvApps: [], tvInputs: [], tabs: [] };

// الرسايل دي تخص جهاز معيّن؛ بنطبقها بس لو تابه هو المفتوح
const PER_DEVICE = ['status', 'kind', 'volume', 'app', 'power', 'apps', 'inputs', 'needPair', 'lgRejected'];

/* ----------------------------------------------------------- رسايل Java */
window.onTv = (e) => {
  if (e.host && state.host && e.host !== state.host && PER_DEVICE.indexOf(e.type) >= 0) return;
  switch (e.type) {
    case 'ready':
      state.ready = true; // أجهزة Android TV بتستنى المفتاح ده لوحدها
      break;
    case 'tabs': onTabs(e.list || []); break;
    case 'found': addFound(e.host, niceName(e.name), e.kind); break;
    case 'kind': setKind(e.kind); break;
    case 'lgPrompt':
      if (e.host && e.host !== state.host) { toast(T('وافق على الاتصال من ') + niceName(e.name)); break; }
      openLgPrompt();
      break;
    case 'lgRejected':
      if (state.sheet !== 'pairSheet') openLgPrompt();
      $('#pairMsg').textContent = '';
      $('#pairErr').textContent = (e.msg || T('التليفزيون رفض')) + T(' — اضغط "جرّب تاني" ووافق على التليفزيون.');
      break;
    case 'inputs':
      state.tvInputs = e.list || [];
      if (state.sheet === 'inputSheet') renderInputs();
      break;
    case 'apps':
      state.tvApps = e.list || [];
      if (state.sheet === 'appsSheet') renderApps();
      break;
    case 'scanDone':
      $('#scanSpin').hidden = true;
      $('#scanBtn').disabled = false;
      if (!$('#foundList').children.length) $('#foundEmpty').textContent = T('ملقيناش تليفزيون. اتأكد إن الموبايل على نفس الواي فاي، أو اكتب الـ IP.');
      break;
    case 'status': setStatus(e); break;
    case 'needPair': openPair(); break;
    case 'pairCode':
      $('#pairMsg').textContent = T('الكود ظاهر دلوقتي على شاشة التليفزيون (حروف وأرقام).');
      $('#pairErr').textContent = '';
      break;
    case 'pairError':
      $('#pairErr').textContent = e.msg;
      if (e.retry) { code = ''; drawCode(); }
      else $('#pairMsg').textContent = T('اضغط "كود جديد" عشان تجرب تاني.');
      break;
    case 'paired':
      if (e.host && e.host !== state.host) { toast(T('تم الاقتران ✔')); break; }
      if (state.sheet === 'pairSheet') closeSheet(true);
      toast(T('تم الاقتران ✔'));
      break;
    case 'volume': {
      const vt = state.tabs.find(x => x.host === state.host);
      if (vt && vt.volume !== +e.level) { vt.volume = +e.level; renderTabs(); }
      const max = +e.max || 0, lvl = +e.level || 0;
      if (!max) break;
      $('#volWrap').hidden = false;
      $('#volFill').style.width = (lvl / max * 100) + '%';
      $('#volText').textContent = e.muted ? T('مكتوم 🔇') : T('الصوت ') + lvl;
      break;
    }
    case 'app': state.curApp = e.pkg; $('#appName').textContent = e.pkg ? appLabel(e.pkg) : ''; break;
    case 'power':
      $('.power').classList.toggle('is-off', !e.on);
      break;
    case 'notConnected':
      toast(state.connected ? T('بيوصل…') : T('مش متوصل بالتليفزيون'));
      break;
    case 'error': toast(e.msg); break;
    case 'needMac':
      openSheet('settingsSheet');
      toast(T('اكتب عنوان MAC بتاع التليفزيون عشان نقدر نشغّله'));
      setTimeout(() => $('#macInput').focus(), 300);
      break;
    case 'resume': break;
    case 'update':
      state.update = e;
      $('#updText').textContent = T('النسخة ') + e.latest + T(' متاحة — عندك ') + e.current + '.';
      $('#updErr').textContent = '';
      $('#updProg').hidden = true;
      $('#updNow').disabled = false;
      $('#updNow').textContent = T('حدّث دلوقتي');
      if (state.sheet !== 'pairSheet') openSheet('updateSheet');
      break;
    case 'noUpdate':
      if (e.manual) toast(T('عندك آخر نسخة ✔'));
      break;
    case 'updateProgress':
      $('#updProg').hidden = false;
      $('#updBar').style.width = (+e.pct || 0) + '%';
      $('#updNow').textContent = +e.pct >= 100 ? T('بيفتح التثبيت…') : T('بينزل… ') + (+e.pct || 0) + '%';
      break;
    case 'updateError':
      $('#updErr').textContent = e.msg;
      $('#updNow').disabled = false;
      $('#updNow').textContent = T('جرّب تاني');
      break;
  }
};

function setStatus(e) {
  const dot = $('#dot');
  state.connected = e.state === 'connected';
  dot.className = 'dot' + (e.state === 'connected' ? ' on' : e.state === 'offline' ? ' off' : '');
  if (e.name) $('#tvName').textContent = e.name;
  $('#tvState').textContent =
    e.state === 'connected' ? T('متوصل') + (e.model && e.model !== e.name ? ' • ' + e.model : '') :
    e.state === 'connecting' ? T('بيوصل…') : T('مش متوصل') + (e.msg ? T(' — بيحاول تاني') : '');
  if (e.state === 'connected') {
    $('#tvInfo').textContent = [e.vendor, e.model, e.host].filter(Boolean).join(' • ');
  }
}

/* ------------------------------------------------------- تابات الأجهزة */
const KIND_LABEL = { lg: 'LG', rcv: T('ريسيفر'), atv: 'TV' };
function tabStatus(t) {
  if (t.state === 'connected') return T('متوصل') + (t.volume >= 0 ? T(' • الصوت ') + t.volume : '');
  if (t.state === 'connecting') return T('بيوصل…');
  if (t.state === 'pair') return T('محتاج اقتران');
  if (t.state === 'rejected') return T('اترفض');
  return T('مش متوصل');
}
function onTabs(list) {
  state.tabs = list;
  const a = list.find(t => t.active);
  if (a && a.host !== state.host) {
    // Java غيّر الجهاز المفتوح (مثلًا اتشال تاب)
    state.host = a.host; state.name = a.name; setKind(a.kind || '');
    $('#appName').textContent = ''; $('#volWrap').hidden = true;
  }
  if (!list.length && state.host) {
    state.host = ''; state.connected = false;
    if ($('#remote').classList.contains('active')) show('devices');
  }
  renderTabs();
  markTabbed();
}
function renderTabs() {
  const nav = $('#devTabs');
  nav.innerHTML = '';
  $('#remote').classList.toggle('has-tabs', state.tabs.length > 0);
  const kinds = {};
  state.tabs.forEach(t => { kinds[t.kind] = (kinds[t.kind] || 0) + 1; });
  state.tabs.forEach(t => {
    const b = document.createElement('button');
    const dot = t.state === 'connected' ? '' : t.state === 'connecting' ? ' wait' : ' off';
    const nm = niceName(t.name) || t.host;
    if (t.host === state.host) {
      b.className = 'dtab on';
      b.innerHTML = '<span class="d' + dot + '"></span><span class="nm"><span class="t" dir="auto"></span><small></small></span>';
      $('.t', b).textContent = nm;
      $('small', b).textContent = tabStatus(t);
    } else {
      b.className = 'dtab';
      b.innerHTML = '<span class="k ' + (t.kind || 'atv') + '"></span><span class="d' + dot + '"></span>';
      $('.k', b).textContent = KIND_LABEL[t.kind] || 'TV';
      if (kinds[t.kind] > 1) { const n = document.createElement('span'); n.className = 'sn'; n.textContent = nm.slice(0, 8); b.insertBefore(n, b.lastChild); }
    }
    b.title = nm;
    bindTab(b, t);
    nav.appendChild(b);
  });
  if (state.tabs.length < 4) {
    const add = document.createElement('button');
    add.className = 'dtab add';
    add.setAttribute('aria-label', T('ضيف جهاز'));
    add.textContent = '＋';
    add.onclick = () => { buzz(); show('devices'); if (!$('#foundList').children.length) scan(); };
    nav.appendChild(add);
  }
  const cur = $('.dtab.on', nav);
  if (cur && cur.scrollIntoView) cur.scrollIntoView({ inline: 'nearest', block: 'nearest' });
}
function bindTab(b, t) {
  let timer = 0, long = false;
  b.addEventListener('pointerdown', () => {
    long = false;
    timer = setTimeout(() => { long = true; removeTab(t); }, 650);
  });
  ['pointerup', 'pointerleave', 'pointercancel'].forEach(ev => b.addEventListener(ev, () => clearTimeout(timer)));
  b.addEventListener('contextmenu', ev => ev.preventDefault());
  b.onclick = () => { if (!long) activateTab(t); };
}
function activateTab(t) {
  buzz();
  if (t.host === state.host) {
    if (t.state === 'pair') openPair();
    else if (t.state !== 'connected') TV.connect(t.host, t.name, t.kind || '');
    return;
  }
  state.host = t.host; state.name = t.name; state.connected = t.state === 'connected';
  setKind(t.kind || '');
  $('#appName').textContent = ''; $('#volWrap').hidden = true;
  state.tvApps = []; state.tvInputs = [];
  renderTabs();
  TV.activate(t.host);
  const link = getLink(t.host);
  if (link && (link.auto || t.state !== 'connected')) switchTvTo(link);
}

/* ---------------- ربط جهاز (شاومي/ريسيفر) بمدخل HDMI في تليفزيون LG ---------------- */
function getLink(host) {
  try { return JSON.parse(localStorage.getItem('link:' + host) || 'null'); } catch (_) { return null; }
}
function setLink(host, link) {
  try {
    if (link) localStorage.setItem('link:' + host, JSON.stringify(link));
    else localStorage.removeItem('link:' + host);
  } catch (_) {}
}
function switchTvTo(link) {
  let ok = false;
  try { ok = TV.switchInputOn(link.tv, link.input); } catch (_) {}
  toast(ok ? T('بقلب التليفزيون على ') + link.label + '…' : T('الـ LG مش متوصل — افتح تابه الأول'));
  return ok;
}
// ⏻ على جهاز نايم مربوط بمدخل: نقلب التليفزيون عليه بدل ما نبعت للجهاز (اللي مش سامعنا)
function wakeViaInput() {
  if (state.kind === 'lg') return false;
  const t = state.tabs.find(x => x.host === state.host);
  const link = getLink(state.host);
  if (!t || !link || t.state === 'connected') return false;
  switchTvTo(link);
  return true;
}
function renderLinkBox() {
  const box = $('#linkBox');
  const lgTabs = state.tabs.filter(t => t.kind === 'lg' && t.host !== state.host);
  box.hidden = !state.host || state.kind === 'lg' || !lgTabs.length;
  if (box.hidden) return;
  const sel = $('#linkSel');
  const link = getLink(state.host);
  sel.innerHTML = '';
  const none = document.createElement('option');
  none.value = ''; none.textContent = T('مش متوصل بالتليفزيون');
  sel.appendChild(none);
  lgTabs.forEach(tv => {
    let ins = [];
    try { ins = JSON.parse(TV.inputsOf(tv.host)) || []; } catch (_) {}
    if (!ins.length && link && link.tv === tv.host) ins = [{ id: link.input, label: link.label }];
    ins.forEach(i => {
      const o = document.createElement('option');
      o.value = tv.host + '|' + i.id;
      o.textContent = (lgTabs.length > 1 ? niceName(tv.name) + ' • ' : '') + (i.label || i.id) + (i.connected === false ? T(' (مفيش جهاز)') : '');
      o.dataset.label = i.label || i.id;
      sel.appendChild(o);
    });
    if (!ins.length) {
      const o = document.createElement('option');
      o.disabled = true; o.textContent = niceName(tv.name) + T(': وصّله الأول عشان تظهر المداخل');
      sel.appendChild(o);
    }
  });
  sel.value = link ? link.tv + '|' + link.input : '';
  $('#linkAuto').checked = !link || link.auto !== false;
}
function saveLinkFromUi() {
  const sel = $('#linkSel');
  if (!sel.value) { setLink(state.host, null); return; }
  const [tv, input] = sel.value.split('|');
  const opt = sel.selectedOptions && sel.selectedOptions[0];
  setLink(state.host, { tv, input, label: (opt && opt.dataset.label) || input, auto: $('#linkAuto').checked });
  toast(T('اتحفظ ✔'));
}
$('#linkSel').onchange = saveLinkFromUi;
$('#linkAuto').onchange = saveLinkFromUi;
function removeTab(t) {
  buzz();
  TV.removeDevice(t.host);
  toast(T('اتشال ') + (niceName(t.name) || t.host) + T(' من التابات'));
}
/* علامة «في التابات» أو زرار «＋ ضيف» جنب كل جهاز في شاشة الأجهزة */
function markTabbed() {
  $$('#devices .dev[data-host]').forEach(b => {
    const h = b.dataset.host;
    let tag = $('.tabmark', b);
    if (!tag) {
      tag = document.createElement('span');
      tag.className = 'tabmark';
      tag.setAttribute('role', 'button');
      b.insertBefore(tag, $('.forget', b));
      tag.onclick = (ev) => {
        ev.stopPropagation();
        if (state.tabs.some(t => t.host === h)) return;
        if (state.tabs.length >= 4) return toast(T('التابات مليانة — شيل جهاز بضغطة طويلة على تابه'));
        buzz();
        TV.addTab(h, b.dataset.name, b.dataset.kind || '');
        toast(T('اتضاف للتابات ✔'));
      };
    }
    const inTabs = state.tabs.some(t => t.host === h);
    tag.classList.toggle('in', inTabs);
    tag.textContent = inTabs ? T('✓ في التابات') : T('＋ ضيف');
  });
}

/* ------------------------------------------------------- شاشة الأجهزة */
function savedDevices() {
  try { return JSON.parse(TV.saved()) || []; } catch (_) { return []; }
}

function devItem(host, name, saved, kind) {
  const li = document.createElement('li');
  const b = document.createElement('button');
  b.className = 'dev';
  b.innerHTML = '<span class="ico"><svg><use href="#i-tv"/></svg></span><span class="meta"><b></b><small></small></span>';
  $('b', b).textContent = name || (kind === 'lg' ? 'LG TV' : kind === 'rcv' ? T('ريسيفر') : 'Android TV');
  $('small', b).textContent = host;
  if (kind) {
    const tag = document.createElement('em');
    tag.className = 'kind ' + kind;
    tag.textContent = kind === 'lg' ? 'LG' : kind === 'rcv' ? T('ريسيفر') : 'Android TV';
    $('.meta', b).appendChild(tag);
  }
  b.dataset.host = host;
  b.dataset.name = name || host;
  b.dataset.kind = kind || '';
  b.onclick = () => connect(host, name || host, kind || '');
  if (saved) {
    const x = document.createElement('span');
    x.className = 'forget';
    x.setAttribute('role', 'button');
    x.setAttribute('aria-label', T('امسح'));
    x.innerHTML = '<svg><use href="#i-x"/></svg>';
    x.onclick = (ev) => { ev.stopPropagation(); TV.forget(host); renderSaved(); };
    b.appendChild(x);
  }
  li.appendChild(b);
  return li;
}

function renderSaved() {
  const list = $('#savedList');
  list.innerHTML = '';
  const saved = savedDevices();
  saved.forEach(d => list.appendChild(devItem(d.host, d.name, true, d.kind)));
  $('#savedCard').hidden = !saved.length;
  markTabbed();
}

const foundHosts = new Map();
// "[LG] webOS TV OLED55C1" → "LG OLED55C1"
function niceName(n) {
  return (n || '').replace(/^\s*\[LG\]\s*/i, 'LG ').replace(/webOS\s*TV\s*/i, '').replace(/\s+/g, ' ').trim();
}
function addFound(host, name, kind) {
  const prev = foundHosts.get(host);
  if (prev !== undefined && (prev || !name)) return; // عندنا اسمه بالفعل
  foundHosts.set(host, name);
  $('#foundEmpty').hidden = true;
  const list = $('#foundList');
  const old = $$('li', list).find(li => li.dataset.host === host);
  const li = devItem(host, name, false, kind);
  li.dataset.host = host;
  if (old) list.replaceChild(li, old); else list.appendChild(li);
  markTabbed();
}

function scan() {
  foundHosts.clear();
  $('#foundList').innerHTML = '';
  $('#foundEmpty').hidden = false;
  $('#foundEmpty').textContent = T('بندوّر…');
  $('#scanSpin').hidden = false;
  $('#scanBtn').disabled = true;
  TV.discover();
}

$('#scanBtn').onclick = scan;
$('#ipForm').onsubmit = (ev) => {
  ev.preventDefault();
  const ip = $('#ipInput').value.trim();
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) return toast(T('اكتب IP صحيح زي 192.168.1.20'));
  $('#ipInput').blur();
  connect(ip, ip, '');
};

function connect(host, name, kind) {
  state.host = host;
  state.name = name;
  setKind(kind || '');
  $('#tvName').textContent = name;
  $('#appName').textContent = '';
  $('#volWrap').hidden = true;
  setStatus({ state: 'connecting', name });
  show('remote');
  TV.connect(host, name, kind || '');
}

/* نوع التليفزيون بيغيّر شوية حاجات في الواجهة */
function setKind(kind) {
  state.kind = kind;
  document.body.classList.toggle('kind-lg', kind === 'lg');
  document.body.classList.toggle('kind-rcv', kind === 'rcv');
  const appsTab = $('[data-sheet="appsSheet"] span');
  if (appsTab) appsTab.textContent = kind === 'rcv' ? T('زراير') : T('تطبيقات');
  $('#appsTitle').textContent = kind === 'rcv' ? T('زراير الريسيفر') : T('افتح تطبيق');
  $('#padHint').textContent = kind === 'lg' ? T('حرّك صباعك = ماوس • دوسة = كليك • صباعين = سكرول') : T('اسحب للتنقل • دوسة = OK');
  $('#repairBtn').hidden = kind === 'lg' || kind === 'rcv';
  if (kind !== 'lg') { state.tvApps = []; state.tvInputs = []; }
}

function show(id) {
  $$('.view').forEach(v => v.classList.toggle('active', v.id === id));
  if (id === 'devices') renderSaved();
}

$('#tvNameBtn').onclick = () => { if (!state.connected) TV.connect(state.host, state.name, state.kind); else openSheet('settingsSheet'); };
$('#settingsBtn').onclick = () => openSheet('settingsSheet');
$('#switchTv').onclick = () => {
  // الأجهزة بتفضل متوصلة في التابات؛ هنا بس بتضيف أو تختار جهاز تاني
  closeSheet();
  show('devices');
  if (!$('#foundList').children.length) scan();
};
$('#repairBtn').onclick = () => { closeSheet(); openPair(); };

/* ------------------------------------------------------------ الاقتران */
let code = '';
function pairMode(lg) {
  $('#pairSheet').classList.toggle('lg-mode', lg);
  $('#pairTitle').textContent = lg ? T('وافق على الاتصال من التليفزيون') : T('اكتب الكود اللي ظاهر على التليفزيون');
  $('#pairRetry').textContent = lg ? T('جرّب تاني') : T('كود جديد');
}
function openLgPrompt() {
  pairMode(true);
  $('#pairErr').textContent = '';
  $('#pairMsg').textContent = T('هتظهر رسالة على شاشة الـ LG — اختار «سماح / Accept» بريموت التليفزيون.');
  if (state.sheet !== 'pairSheet') openSheet('pairSheet');
}
function openPair() {
  pairMode(false);
  code = '';
  drawCode();
  $('#pairErr').textContent = '';
  $('#pairMsg').textContent = T('بنطلب الكود من التليفزيون…');
  openSheet('pairSheet');
  TV.pairStart(state.host, state.name);
}
function drawCode() {
  const boxes = $('#codeBoxes');
  boxes.innerHTML = '';
  for (let i = 0; i < 6; i++) {
    const s = document.createElement('span');
    s.textContent = code[i] || '';
    if (i === code.length) s.className = 'cur';
    boxes.appendChild(s);
  }
}
(function buildHexpad() {
  const pad = $('#hexpad');
  '0123456789ABCDEF'.split('').forEach(ch => {
    const b = document.createElement('button');
    b.textContent = ch;
    b.onclick = () => typeCode(ch);
    pad.appendChild(b);
  });
  const del = document.createElement('button');
  del.className = 'del';
  del.innerHTML = '<svg><use href="#i-del"/></svg>';
  del.setAttribute('aria-label', T('مسح'));
  del.onclick = () => { code = code.slice(0, -1); drawCode(); };
  pad.appendChild(del);
})();
function typeCode(ch) {
  if (code.length >= 6) return;
  buzz();
  code += ch;
  drawCode();
  $('#pairErr').textContent = '';
  if (code.length === 6) {
    $('#pairMsg').textContent = T('بنتأكد من الكود…');
    TV.pairFinish(code);
  }
}
// كيبورد حقيقي (لو متوصل كيبورد أو بتجرب من الكمبيوتر)
document.addEventListener('keydown', (ev) => {
  if (state.sheet !== 'pairSheet') return;
  const k = ev.key.toUpperCase();
  if (/^[0-9A-F]$/.test(k)) typeCode(k);
  else if (ev.key === 'Backspace') { code = code.slice(0, -1); drawCode(); }
});
$('#pairCancel').onclick = () => {
  TV.pairCancel();
  closeSheet();
  // جهاز لسه ماتقرنش: نشيله من التابات
  const h = state.host;
  const t = state.tabs.find(x => x.host === h);
  if (t && t.state !== 'connected') {
    const others = state.tabs.filter(x => x.host !== h);
    TV.removeDevice(h);
    if (!others.length) { state.host = ''; show('devices'); }
  }
};
$('#pairRetry').onclick = () => {
  if (state.kind === 'lg') { closeSheet(true); TV.connect(state.host, state.name, 'lg'); return; }
  TV.pairCancel(); openPair();
};

/* -------------------------------------------------------------- الزراير */
function buzz() { try { TV.vibrate(12); } catch (_) {} }

function bindKeys(root) {
  $$('[data-key]', root).forEach(el => {
    if (el._bound) return;
    el._bound = true;
    const key = +el.dataset.key;
    const repeat = el.hasAttribute('data-repeat');
    const long = el.hasAttribute('data-long');
    let t1 = 0, t2 = 0, longSent = false, down = false;

    const start = (ev) => {
      ev.preventDefault();
      if (down) return;
      down = true;
      el.classList.add('pressed');
      buzz();
      longSent = false;
      if (key === 26 && wakeViaInput()) return; // الجهاز نايم: نصحّيه من مدخل التليفزيون
      if (long) {
        t1 = setTimeout(() => { longSent = true; TV.keyDir(key, START_LONG); buzz(); }, 550);
      } else {
        TV.key(key);
        if (repeat) t1 = setTimeout(() => { t2 = setInterval(() => TV.key(key), 110); }, 380);
      }
    };
    const end = () => {
      if (!down) return;
      down = false;
      el.classList.remove('pressed');
      clearTimeout(t1); clearInterval(t2);
      if (long) TV.keyDir(key, longSent ? END_LONG : SHORT);
    };
    el.addEventListener('pointerdown', start);
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', end);
    el.addEventListener('pointerleave', end);
    el.addEventListener('contextmenu', ev => ev.preventDefault());
  });
}
bindKeys(document);

/* ---------------------------------------------- لوحة اللمس (Touchpad) */
let padMode = false;
try { padMode = localStorage.getItem('padMode') === '1'; } catch (_) {}
function applyMode() {
  $('#dpad').hidden = padMode;
  $('#touchpad').hidden = !padMode;
  $('#modeToggle use').setAttribute('href', padMode ? '#i-dpad' : '#i-pad');
}
$('#modeToggle').onclick = () => {
  padMode = !padMode;
  try { localStorage.setItem('padMode', padMode ? '1' : '0'); } catch (_) {}
  applyMode();
};
applyMode();

(function touchpad() {
  const pad = $('#touchpad');
  const STEP = 42;
  let sx = 0, sy = 0, ax = 0, ay = 0, moved = false, t0 = 0, id = null;
  // LG: الماوس — بنجمّع الحركة ونبعتها كل 30ms
  let mx = 0, my = 0, flushT = 0, fingers = 0;
  const flush = () => {
    flushT = 0;
    const dx = Math.round(mx), dy = Math.round(my);
    if (!dx && !dy) return;
    mx -= dx; my -= dy;
    if (fingers > 1) TV.scroll(-dx, -dy); else TV.pointer(dx, dy);
  };
  pad.addEventListener('touchstart', (e) => { fingers = e.touches.length; }, { passive: true });
  pad.addEventListener('touchend', (e) => { fingers = e.touches.length; }, { passive: true });
  let lx = 0, ly = 0;
  pad.addEventListener('pointerdown', (e) => {
    if (id !== null && state.kind === 'lg') return; // الصباع التاني (سكرول)
    id = e.pointerId; pad.setPointerCapture(id);
    sx = ax = lx = e.clientX; sy = ay = ly = e.clientY; moved = false; t0 = Date.now();
  });
  pad.addEventListener('pointermove', (e) => {
    if (e.pointerId !== id) return;
    if (state.kind === 'lg') {
      mx += (e.clientX - lx) * 1.6; my += (e.clientY - ly) * 1.6;
      lx = e.clientX; ly = e.clientY;
      if (Math.hypot(e.clientX - sx, e.clientY - sy) > 10) moved = true;
      if (!flushT) flushT = setTimeout(flush, 30);
      return;
    }
    const dx = e.clientX - ax, dy = e.clientY - ay;
    if (Math.abs(dx) >= STEP || Math.abs(dy) >= STEP) {
      moved = true;
      if (Math.abs(dx) > Math.abs(dy)) TV.key(dx > 0 ? 22 : 21);
      else TV.key(dy > 0 ? 20 : 19);
      buzz();
      ax = e.clientX; ay = e.clientY;
    }
  });
  const up = (e) => {
    if (e.pointerId !== id) return;
    id = null;
    const far = Math.hypot(e.clientX - sx, e.clientY - sy);
    if (!moved && far < 14 && Date.now() - t0 < 500) {
      if (state.kind === 'lg') TV.click(); else TV.key(23);
      buzz();
      const r = $('#ripple'), b = pad.getBoundingClientRect();
      r.style.left = (e.clientX - b.left) + 'px'; r.style.top = (e.clientY - b.top) + 'px';
      r.classList.remove('go'); void r.offsetWidth; r.classList.add('go');
    }
  };
  pad.addEventListener('pointerup', up);
  pad.addEventListener('pointercancel', up);
})();

/* ------------------------------------------------------------ الكيبورد */
$('#kbdForm').onsubmit = (ev) => {
  ev.preventDefault();
  const inp = $('#kbdInput');
  if ($('#liveType').checked) { TV.key(66); return; }
  if (!inp.value) return TV.key(66);
  TV.text(inp.value);
  inp.value = '';
  toast(T('اتبعت ✔'));
};
let lastLive = '';
$('#kbdInput').addEventListener('input', (ev) => {
  if (!$('#liveType').checked) return;
  const v = ev.target.value;
  if (v.startsWith(lastLive)) {
    const add = v.slice(lastLive.length);
    if (add) TV.text(add);
  } else if (lastLive.startsWith(v)) {
    for (let i = 0; i < lastLive.length - v.length; i++) TV.key(67);
  } else {
    TV.text(v);
  }
  lastLive = v;
});
$('#liveType').onchange = () => { lastLive = $('#kbdInput').value = ''; try { localStorage.setItem('live', $('#liveType').checked ? '1' : '0'); } catch (_) {} };
try { $('#liveType').checked = localStorage.getItem('live') === '1'; } catch (_) {}

/* ----------------------------------------------------------- التطبيقات */
const BUILTIN_APPS = [
  { label: 'YouTube', link: 'https://www.youtube.com', color: '#ff0033', glyph: '▶' },
  { label: 'Netflix', link: 'https://www.netflix.com/title', color: '#e50914', glyph: 'N' },
  { label: 'Shahid', link: 'market://launch?id=net.mbc.shahidTV', color: '#18c37e', glyph: T('ش') },
  { label: 'STREAM IPTV', link: 'market://launch?id=com.stream.iptv', color: '#7b5cff', glyph: 'TV' },
  { label: 'Prime Video', link: 'https://app.primevideo.com', color: '#1399ff', glyph: 'P' },
  { label: 'Spotify', link: 'spotify://', color: '#1db954', glyph: '♫' },
  { label: 'Google Play', link: 'market://launch?id=com.android.vending', color: '#01875f', glyph: '◆' },
];
const PKG_NAMES = {
  'com.google.android.youtube.tv': 'YouTube', 'com.netflix.ninja': 'Netflix', 'net.mbc.shahidTV': 'Shahid',
  'com.stream.iptv': 'STREAM IPTV', 'com.amazon.amazonvideo.livingroom': 'Prime Video', 'com.spotify.tv.android': 'Spotify',
  'com.google.android.tvlauncher': T('الشاشة الرئيسية'), 'com.google.android.apps.tv.launcherx': T('الشاشة الرئيسية'),
  'com.android.vending': 'Google Play', 'com.android.tv.settings': T('الإعدادات'), 'com.mitv.tvhome': T('الشاشة الرئيسية'),
  'com.google.android.katniss': T('بحث جوجل'), 'com.webos.app.home': T('الشاشة الرئيسية'), 'com.webos.app.livetv': 'Live TV',
  'com.webos.app.hdmi1': 'HDMI 1', 'com.webos.app.hdmi2': 'HDMI 2', 'com.webos.app.hdmi3': 'HDMI 3', 'com.webos.app.browser': T('المتصفح'),
  'com.palm.app.settings': T('الإعدادات'), 'youtube.leanback.v4': 'YouTube', 'netflix': 'Netflix', 'amazon': 'Prime Video', 'org.videolan.vlc': 'VLC', 'com.disney.disneyplus': 'Disney+',
};
/* ------------------------------------------------------------- المصادر */
function renderInputs() {
  const grid = $('#inputGrid');
  grid.innerHTML = '';
  let list;
  if (state.kind === 'lg') {
    if (!state.tvInputs.length) {
      grid.innerHTML = T('<p class="muted small">بنجيب المصادر من التليفزيون…</p>');
      return;
    }
    list = state.tvInputs.map(i => ({ label: i.label || i.id, sub: i.connected ? T('متوصل بيه جهاز') : T('مفيش جهاز'), on: i.connected,
      active: state.curApp && state.curApp === i.appId, go: () => TV.switchInput(i.id) }));
  } else {
    // Android TV: مفاتيح HDMI المباشرة (تليفزيونات شاومي)؛ أجهزة زي Mi Box مالهاش مداخل
    list = [1, 2, 3, 4].map(n => ({ label: 'HDMI ' + n, sub: '', on: true, active: false, go: () => TV.key(242 + n) }));
  }
  list.forEach(i => {
    const b = document.createElement('button');
    b.className = 'input' + (i.active ? ' active' : '') + (i.on ? '' : ' off');
    b.innerHTML = '<svg><use href="#i-input"/></svg><span><b></b><small></small></span>';
    $('b', b).textContent = i.label;
    $('small', b).textContent = i.active ? T('شغال دلوقتي') : i.sub;
    b.onclick = () => { buzz(); i.go(); closeSheet(); toast(T('بيغيّر لـ ') + i.label + '…'); };
    grid.appendChild(b);
  });
}

function appLabel(pkg) {
  const tvApp = state.tvApps.find(a => a.id === pkg);
  if (tvApp) return '▶ ' + tvApp.title;
  const custom = customApps().find(a => a.link === 'market://launch?id=' + pkg);
  return '▶ ' + (PKG_NAMES[pkg] || (custom && custom.label) || pkg);
}
function customApps() {
  try { return JSON.parse(localStorage.getItem('apps') || '[]'); } catch (_) { return []; }
}
function saveCustom(list) {
  try { localStorage.setItem('apps', JSON.stringify(list)); } catch (_) {}
}
const APP_COLORS = ['#ff0033', '#e50914', '#18c37e', '#7b5cff', '#1399ff', '#f2994a', '#2fbf71', '#eb5757'];
function renderTvApps(grid) {
  if (!state.tvApps.length) {
    grid.innerHTML = T('<p class="muted small">بنجيب التطبيقات من التليفزيون…</p>');
    return;
  }
  state.tvApps.forEach((a, i) => {
    const b = document.createElement('button');
    b.className = 'app';
    b.innerHTML = '<i></i><span></span>';
    const ic = $('i', b);
    const letter = (a.title || '?').trim().charAt(0).toUpperCase();
    ic.style.background = APP_COLORS[i % APP_COLORS.length];
    if (a.icon) {
      const img = document.createElement('img');
      img.alt = '';
      img.onerror = () => { img.remove(); ic.textContent = letter; };
      img.src = a.icon;
      ic.classList.add('has-img');
      ic.appendChild(img);
    } else {
      ic.textContent = letter;
    }
    $('span', b).textContent = a.title || a.id;
    b.onclick = () => { buzz(); TV.launch('lg:' + a.id); closeSheet(); toast(T('بيفتح ') + (a.title || a.id) + '…'); };
    grid.appendChild(b);
  });
}
// زراير الريسيفر اللي مش موجودة في الريموت العادي (أكواد G-MScreen)
const RCV_KEYS = [
  { label: T('المفضلة'), code: 33, glyph: '★' }, { label: T('TV / راديو'), code: 22, glyph: '📻' },
  { label: T('القناة اللي فاتت'), code: 29, glyph: '↺' }, { label: T('دليل البرامج'), code: 32, glyph: 'EPG' },
  { label: T('معلومات'), code: 57, glyph: 'i' }, { label: T('الصوت/اللغة'), code: 54, glyph: '♪' },
  { label: T('الترجمة'), code: 31, glyph: 'CC' }, { label: T('القمر'), code: 30, glyph: 'SAT' },
  { label: T('بحث'), code: 39, glyph: '🔍' }, { label: T('التسجيلات'), code: 66, glyph: 'PVR' },
  { label: T('تسجيل'), code: 58, glyph: '●' }, { label: T('زووم'), code: 56, glyph: '⤢' },
  { label: T('صفحة +'), code: 37, glyph: '⇞' }, { label: T('صفحة −'), code: 38, glyph: '⇟' },
  { label: T('النوم'), code: 41, glyph: 'Zz' }, { label: 'YouTube', code: 74, glyph: '▶' },
];
function renderRcvKeys(grid) {
  RCV_KEYS.forEach((k, i) => {
    const b = document.createElement('button');
    b.className = 'app';
    b.innerHTML = '<i></i><span></span>';
    $('i', b).textContent = k.glyph;
    $('i', b).style.background = APP_COLORS[i % APP_COLORS.length];
    $('span', b).textContent = k.label;
    b.onclick = () => { buzz(); TV.rawKey(k.code); };
    grid.appendChild(b);
  });
}
function renderApps() {
  const grid = $('#appsGrid');
  grid.innerHTML = '';
  if (state.kind === 'rcv') return renderRcvKeys(grid);
  if (state.kind === 'lg') return renderTvApps(grid);
  const custom = customApps();
  BUILTIN_APPS.concat(custom.map(a => Object.assign({ custom: true }, a))).forEach(a => {
    const b = document.createElement('button');
    b.className = 'app';
    b.innerHTML = '<i></i><span></span>';
    const ic = $('i', b);
    ic.textContent = a.glyph || a.label.trim().charAt(0).toUpperCase();
    ic.style.background = a.color || '#3a4256';
    $('span', b).textContent = a.label;
    b.onclick = () => { buzz(); TV.launch(a.link); closeSheet(); toast(T('بيفتح ') + a.label + '…'); };
    if (a.custom) {
      const rm = document.createElement('span');
      rm.className = 'rm';
      rm.innerHTML = '<svg><use href="#i-x"/></svg>';
      rm.onclick = (ev) => { ev.stopPropagation(); saveCustom(customApps().filter(x => x.link !== a.link)); renderApps(); };
      b.appendChild(rm);
    }
    grid.appendChild(b);
  });
}
$('#addAppForm').onsubmit = (ev) => {
  ev.preventDefault();
  let link = $('#appLink').value.trim();
  const label = $('#appLabel').value.trim() || link;
  if (!link) return toast(T('اكتب اسم الباكدج أو الرابط'));
  const m = link.match(/[?&]id=([\w.]+)/); // رابط Google Play
  if (m) link = m[1];
  if (/^[a-zA-Z][\w]*(\.[\w]+)+$/.test(link)) link = 'market://launch?id=' + link;
  const list = customApps().filter(a => a.link !== link);
  list.push({ label, link });
  saveCustom(list);
  $('#appLabel').value = $('#appLink').value = '';
  $('.add-app').open = false;
  renderApps();
};
renderApps();

/* ------------------------------------------------------------ النوافذ */
function openSheet(id) {
  closeSheet(true);
  state.sheet = id;
  $('#' + id).classList.add('show');
  $('#scrim').classList.add('show');
  if (id === 'appsSheet') renderApps();
  if (id === 'inputSheet') renderInputs();
  if (id === 'settingsSheet') {
    $('#vibrateChk').checked = !!TV.getVibrate();
    try { $('#notifChk').checked = !!TV.getNotif(); } catch (_) {}
    $('#tvActions').hidden = !state.host;
    $('#tvInfo').hidden = !state.host;
    let v = ''; try { v = TV.appVersion(); } catch (_) {}
    $('#verText').textContent = v ? T('نسخة التطبيق ') + v : '';
    renderThemes();
    renderLinkBox();
    $('#wolBox').hidden = state.kind !== 'lg' || !state.host;
    try { $('#macInput').value = TV.getMac(); } catch (_) {}
    try { $('#diagText').textContent = TV.diagnostics(); } catch (_) { $('#diagText').textContent = ''; }
  }
}
function closeSheet(silent) {
  if (state.sheet === 'pairSheet' && !silent) TV.pairCancel();
  $$('.sheet.show').forEach(s => s.classList.remove('show'));
  $('#scrim').classList.remove('show');
  if (document.activeElement && document.activeElement.blur) document.activeElement.blur();
  state.sheet = null;
}
$('#scrim').onclick = () => {
  if (state.sheet === 'pairSheet') return; // الاقتران بيتقفل من زرار إلغاء بس
  closeSheet();
};
$$('[data-sheet]').forEach(b => b.onclick = () => {
  openSheet(b.dataset.sheet);
  if (b.dataset.sheet === 'kbdSheet') setTimeout(() => $('#kbdInput').focus(), 250);
});
$('#macForm').onsubmit = (ev) => {
  ev.preventDefault();
  const v = $('#macInput').value.trim();
  let ok = false;
  try { ok = TV.setMac(v); } catch (_) {}
  toast(ok ? T('اتحفظ ✔ — دلوقتي زرار ⏻ يشغّل التليفزيون') : T('العنوان مش صحيح — لازم يكون زي a8:23:fe:12:34:56'));
  if (ok) $('#macInput').blur();
};
$$('#langSeg button').forEach(b => {
  b.classList.toggle('on', b.dataset.lang === LANG);
  b.onclick = () => {
    if (b.dataset.lang === LANG) return;
    try { localStorage.setItem('lang', b.dataset.lang); } catch (_) {}
    try { TV.setLang(b.dataset.lang); } catch (_) {}
    location.reload();
  };
});
try { TV.setLang(LANG); } catch (_) {}
$('#diagShare').onclick = () => { try { TV.share(TV.diagnostics()); } catch (_) {} };
$('#diagBox').ontoggle = () => { try { $('#diagText').textContent = TV.diagnostics(); } catch (_) {} };
$('#notifChk').onchange = (e) => { try { TV.setNotif(e.target.checked); } catch (_) {} };
$('#vibrateChk').onchange = (e) => TV.setVibrate(e.target.checked);

// زرار الرجوع في الموبايل
window.onBack = () => {
  if (state.sheet === 'pairSheet') { $('#pairCancel').click(); return true; }
  if (state.sheet) { closeSheet(); return true; }
  if ($('#devices').classList.contains('active') && state.host) { show('remote'); return true; }
  return false;
};

let toastT = 0;
function toast(msg) {
  const t = $('#toast');
  t.textContent = msg;
  t.classList.add('show');
  clearTimeout(toastT);
  toastT = setTimeout(() => t.classList.remove('show'), 2200);
}

/* ------------------------------------------------------------ التصميمات */
const THEMES = [
  { id: 'midnight', name: T('ليلي برتقالي'), bg: '#0b0d12', panel: '#1c2230', accent: '#ff6a1f', bar: '#0b0d12' },
  { id: 'glass', name: T('زجاج'), bg: 'linear-gradient(150deg,#6d3cff,#0b1530 60%,#00b3ff)', panel: 'rgba(255,255,255,.22)', accent: '#5ee7ff', bar: '#1d0f45' },
  { id: 'light', name: T('فاتح'), bg: '#e6eaf1', panel: '#f4f6fa', accent: '#3d6bff', bar: '#e6eaf1', light: true },
  { id: 'neon', name: T('نيون'), bg: '#04050a', panel: '#0a0d16', accent: '#00f0ff', ring: '#ff2bd6', bar: '#04050a' },
  { id: 'material', name: 'Material', bg: '#15131b', panel: '#2a2636', accent: '#cfb4ff', bar: '#15131b' },
];
function currentTheme() {
  return document.documentElement.getAttribute('data-theme') || 'midnight';
}
function applyTheme(id) {
  const t = THEMES.find(x => x.id === id) || THEMES[0];
  if (t.id === 'midnight') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.setAttribute('data-theme', t.id);
  try { localStorage.setItem('theme', t.id); } catch (_) {}
  try { TV.setBars(t.bar, !!t.light); } catch (_) {}
}
function renderThemes() {
  const grid = $('#themeGrid');
  grid.innerHTML = '';
  THEMES.forEach(t => {
    const b = document.createElement('button');
    b.className = 'theme' + (currentTheme() === t.id ? ' sel' : '');
    b.innerHTML = '<span class="pv"><b><i></i></b><s></s></span><span></span>';
    $('.pv', b).style.background = t.bg;
    $('.pv b', b).style.background = t.panel;
    if (t.ring) $('.pv b', b).style.boxShadow = '0 0 0 2px ' + t.ring;
    $('.pv i', b).style.background = t.accent;
    $('.pv s', b).style.background = t.panel;
    b.lastChild.textContent = t.name;
    b.onclick = () => { buzz(); applyTheme(t.id); renderThemes(); };
    grid.appendChild(b);
  });
}
$('#devSettingsBtn').onclick = () => openSheet('settingsSheet');
applyTheme(currentTheme());

/* -------------------------------------------------------------- التحديث */
$('#updLater').onclick = () => closeSheet();
$('#updNow').onclick = () => {
  $('#updNow').disabled = true;
  $('#updErr').textContent = '';
  $('#updNow').textContent = T('بيبدأ التحميل…');
  TV.startUpdate();
};
$('#checkUpdBtn').onclick = () => { toast(T('بنشوف لو فيه تحديث…')); TV.checkUpdate(true); };

/* --------------------------------------------------------------- البداية */
renderSaved();
if (!savedDevices().length) scan();
// Java ممكن يكون خلص تجهيز قبل ما الصفحة تحمل
// رجّع التابات اللي كانت مفتوحة، وافتح آخر جهاز كنت بتستخدمه
(function restoreTabs() {
  let tabs = [], last = '';
  try { tabs = JSON.parse(TV.tabs()) || []; } catch (_) {}
  try { last = TV.lastHost(); } catch (_) {}
  if (!tabs.length && last) {
    const d = savedDevices().find(x => x.host === last);
    if (d) tabs = [d];
  }
  if (!tabs.length) return;
  const act = tabs.find(t => t.host === last) || tabs[0];
  tabs.forEach(t => { if (t.host !== act.host) TV.addTab(t.host, t.name, t.kind || ''); });
  connect(act.host, act.name, act.kind || '');
})();
try { if (TV.isReady()) window.onTv({ type: 'ready' }); } catch (_) {}
// كل ما التطبيق يتفتح بيشوف لو فيه نسخة أحدث
setTimeout(() => { try { TV.checkUpdate(false); } catch (_) {} }, 1200);
