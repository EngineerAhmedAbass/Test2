'use strict';
/* ريموت التليفزيون — الواجهة. الاتصال نفسه في Java (window.TV). */

const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => Array.from(el.querySelectorAll(s));

const SHORT = 3, START_LONG = 1, END_LONG = 2;

/* -------- لو الصفحة مفتوحة في متصفح عادي (للتجربة) بنعمل TV وهمي -------- */
if (!window.TV) {
  const fire = (o, ms = 300) => setTimeout(() => window.onTv(o), ms);
  let devices = [];
  let paired = false;
  window.TV = {
    saved: () => JSON.stringify(devices),
    lastHost: () => '',
    isReady: () => true,
    discover() {
      fire({ type: 'found', host: '192.168.1.23', name: 'Mi Box S' }, 700);
      fire({ type: 'found', host: '192.168.1.31', name: '[LG] webOS TV OLED55C1', kind: 'lg' }, 1100);
      fire({ type: 'found', host: '192.168.1.40', name: '' }, 1500);
      fire({ type: 'scanDone' }, 2500);
    },
    connect(host, name, kind) {
      kind = kind || (devices.find(d => d.host === host) || {}).kind || (host.endsWith('.31') ? 'lg' : 'atv');
      devices = [{ host, name, kind }].concat(devices.filter(d => d.host !== host));
      fire({ type: 'status', state: 'connecting', host, name }, 50);
      fire({ type: 'kind', kind }, 80);
      if (kind === 'lg') {
        if (!this._lgOk) { this._lgOk = true; fire({ type: 'lgPrompt' }, 500); fire({ type: 'paired' }, 2500); }
        const d = this._lgOk === true ? 2600 : 600; this._lgOk = 1;
        fire({ type: 'status', state: 'connected', host, name, vendor: 'LG', model: 'OLED55C1', kind }, d);
        fire({ type: 'volume', level: '12', max: '100', muted: '' }, d + 100);
        fire({ type: 'apps', list: [
          { id: 'youtube.leanback.v4', title: 'YouTube', icon: '' }, { id: 'netflix', title: 'Netflix', icon: '' },
          { id: 'shahid', title: 'Shahid', icon: '' }, { id: 'com.webos.app.livetv', title: 'Live TV', icon: '' },
          { id: 'com.webos.app.hdmi1', title: 'HDMI 1', icon: '' }, { id: 'com.webos.app.browser', title: 'Web Browser', icon: '' },
          { id: 'amazon', title: 'Prime Video', icon: '' }, { id: 'com.palm.app.settings', title: 'Settings', icon: '' }] }, d + 150);
        fire({ type: 'app', pkg: 'com.webos.app.hdmi2' }, d + 200);
        fire({ type: 'inputs', list: [
          { id: 'HDMI_1', label: 'HDMI 1', appId: 'com.webos.app.hdmi1', connected: true },
          { id: 'HDMI_2', label: 'PlayStation', appId: 'com.webos.app.hdmi2', connected: true },
          { id: 'HDMI_3', label: 'HDMI 3', appId: 'com.webos.app.hdmi3', connected: false },
          { id: 'HDMI_4', label: 'HDMI 4', appId: 'com.webos.app.hdmi4', connected: false }] }, d + 220);
        return;
      }
      if (!paired) return fire({ type: 'needPair', host, name }, 600);
      fire({ type: 'status', state: 'connected', host, name, vendor: 'Xiaomi', model: 'MIBOX4' }, 600);
      fire({ type: 'volume', level: '9', max: '15', muted: '' }, 800);
      fire({ type: 'app', pkg: 'com.google.android.youtube.tv' }, 900);
    },
    disconnect() {}, forget() {},
    pairStart(h, n) { state.host = h; state.name = n; fire({ type: 'pairCode' }, 500); },
    pairFinish(code) {
      if (code !== 'A1B2C3') return fire({ type: 'pairError', msg: 'الكود غلط، اتأكد منه', retry: '1' });
      paired = true; fire({ type: 'paired' }); this.connect(state.host, state.name);
    },
    pairCancel() {},
    key(c) { console.log('key', c); }, keyDir(c, d) { console.log('key', c, d); },
    text(t) { console.log('text', t); }, launch(l) { console.log('launch', l); },
    getMac: () => '', setMac: (m) => /^([0-9a-f]{2}[:-]){5}[0-9a-f]{2}$/i.test(m.trim()),
    diagnostics: () => 'app 1.0.x • lg • 192.168.1.31\n16:20:01  main: wss:3001 OK\n16:20:01  buttons socket OK', share() {},
    switchInput(id) { console.log('input', id); },
    pointer(dx, dy) { console.log('pointer', dx, dy); }, click() { console.log('click'); }, scroll() {}, kind: () => state.kind,
    vibrate() {}, setVibrate() {}, getVibrate: () => true, resetIdentity() {},
    setBars() {}, appVersion: () => '1.0.1',
    checkUpdate(manual) { fire(manual || location.hash === '#update' ? { type: 'update', current: '1.0.1', latest: '1.0.2' } : { type: 'noUpdate', manual: '' }, 900); },
    startUpdate() { let p = 0; const t = setInterval(() => { p += 20; window.onTv({ type: 'updateProgress', pct: String(p) }); if (p >= 100) clearInterval(t); }, 300); },
  };
}

const state = { host: '', name: '', kind: '', connected: false, sheet: null, ready: false, tvApps: [], tvInputs: [] };

/* ----------------------------------------------------------- رسايل Java */
window.onTv = (e) => {
  switch (e.type) {
    case 'ready': {
      if (state.ready) break;
      state.ready = true;
      const last = TV.lastHost();
      const saved = savedDevices();
      if (last && !state.host) {
        const d = saved.find(x => x.host === last) || { host: last, name: last };
        connect(d.host, d.name, d.kind);
      }
      break;
    }
    case 'found': addFound(e.host, niceName(e.name), e.kind); break;
    case 'kind': setKind(e.kind); break;
    case 'lgPrompt': openLgPrompt(); break;
    case 'lgRejected':
      if (state.sheet !== 'pairSheet') openLgPrompt();
      $('#pairMsg').textContent = '';
      $('#pairErr').textContent = (e.msg || 'التليفزيون رفض') + ' — اضغط "جرّب تاني" ووافق على التليفزيون.';
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
      if (!$('#foundList').children.length) $('#foundEmpty').textContent = 'ملقيناش تليفزيون. اتأكد إن الموبايل على نفس الواي فاي، أو اكتب الـ IP.';
      break;
    case 'status': setStatus(e); break;
    case 'needPair': openPair(); break;
    case 'pairCode':
      $('#pairMsg').textContent = 'الكود ظاهر دلوقتي على شاشة التليفزيون (حروف وأرقام).';
      $('#pairErr').textContent = '';
      break;
    case 'pairError':
      $('#pairErr').textContent = e.msg;
      if (e.retry) { code = ''; drawCode(); }
      else $('#pairMsg').textContent = 'اضغط "كود جديد" عشان تجرب تاني.';
      break;
    case 'paired':
      closeSheet();
      toast('تم الاقتران ✔');
      break;
    case 'volume': {
      const max = +e.max || 0, lvl = +e.level || 0;
      if (!max) break;
      $('#volWrap').hidden = false;
      $('#volFill').style.width = (lvl / max * 100) + '%';
      $('#volText').textContent = e.muted ? 'مكتوم 🔇' : 'الصوت ' + lvl;
      break;
    }
    case 'app': state.curApp = e.pkg; $('#appName').textContent = appLabel(e.pkg); break;
    case 'power':
      $('.power').classList.toggle('is-off', !e.on);
      break;
    case 'notConnected':
      toast(state.connected ? 'بيوصل…' : 'مش متوصل بالتليفزيون');
      break;
    case 'error': toast(e.msg); break;
    case 'needMac':
      openSheet('settingsSheet');
      toast('اكتب عنوان MAC بتاع التليفزيون عشان نقدر نشغّله');
      setTimeout(() => $('#macInput').focus(), 300);
      break;
    case 'resume': break;
    case 'update':
      state.update = e;
      $('#updText').textContent = 'النسخة ' + e.latest + ' متاحة — عندك ' + e.current + '.';
      $('#updErr').textContent = '';
      $('#updProg').hidden = true;
      $('#updNow').disabled = false;
      $('#updNow').textContent = 'حدّث دلوقتي';
      if (state.sheet !== 'pairSheet') openSheet('updateSheet');
      break;
    case 'noUpdate':
      if (e.manual) toast('عندك آخر نسخة ✔');
      break;
    case 'updateProgress':
      $('#updProg').hidden = false;
      $('#updBar').style.width = (+e.pct || 0) + '%';
      $('#updNow').textContent = +e.pct >= 100 ? 'بيفتح التثبيت…' : 'بينزل… ' + (+e.pct || 0) + '%';
      break;
    case 'updateError':
      $('#updErr').textContent = e.msg;
      $('#updNow').disabled = false;
      $('#updNow').textContent = 'جرّب تاني';
      break;
  }
};

function setStatus(e) {
  const dot = $('#dot');
  state.connected = e.state === 'connected';
  dot.className = 'dot' + (e.state === 'connected' ? ' on' : e.state === 'offline' ? ' off' : '');
  if (e.name) $('#tvName').textContent = e.name;
  $('#tvState').textContent =
    e.state === 'connected' ? 'متوصل' + (e.model ? ' • ' + e.model : '') :
    e.state === 'connecting' ? 'بيوصل…' : 'مش متوصل' + (e.msg ? ' — بيحاول تاني' : '');
  if (e.state === 'connected') {
    $('#tvInfo').textContent = [e.vendor, e.model, e.host].filter(Boolean).join(' • ');
  }
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
  $('b', b).textContent = name || (kind === 'lg' ? 'LG TV' : 'Android TV');
  $('small', b).textContent = host;
  if (kind) {
    const tag = document.createElement('em');
    tag.className = 'kind ' + kind;
    tag.textContent = kind === 'lg' ? 'LG' : 'Android TV';
    $('.meta', b).appendChild(tag);
  }
  b.onclick = () => connect(host, name || host, kind || '');
  if (saved) {
    const x = document.createElement('span');
    x.className = 'forget';
    x.setAttribute('role', 'button');
    x.setAttribute('aria-label', 'امسح');
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
}

function scan() {
  foundHosts.clear();
  $('#foundList').innerHTML = '';
  $('#foundEmpty').hidden = false;
  $('#foundEmpty').textContent = 'بندوّر…';
  $('#scanSpin').hidden = false;
  $('#scanBtn').disabled = true;
  TV.discover();
}

$('#scanBtn').onclick = scan;
$('#ipForm').onsubmit = (ev) => {
  ev.preventDefault();
  const ip = $('#ipInput').value.trim();
  if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(ip)) return toast('اكتب IP صحيح زي 192.168.1.20');
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
  $('#padHint').textContent = kind === 'lg' ? 'حرّك صباعك = ماوس • دوسة = كليك • صباعين = سكرول' : 'اسحب للتنقل • دوسة = OK';
  $('#repairBtn').hidden = kind === 'lg';
  if (kind !== 'lg') { state.tvApps = []; state.tvInputs = []; }
}

function show(id) {
  $$('.view').forEach(v => v.classList.toggle('active', v.id === id));
  if (id === 'devices') renderSaved();
}

$('#tvNameBtn').onclick = () => { if (!state.connected) TV.connect(state.host, state.name, state.kind); else openSheet('settingsSheet'); };
$('#settingsBtn').onclick = () => openSheet('settingsSheet');
$('#switchTv').onclick = () => {
  closeSheet();
  TV.disconnect();
  state.host = ''; state.connected = false;
  show('devices');
  if (!$('#foundList').children.length) scan();
};
$('#repairBtn').onclick = () => { closeSheet(); TV.disconnect(); openPair(); };

/* ------------------------------------------------------------ الاقتران */
let code = '';
function pairMode(lg) {
  $('#pairSheet').classList.toggle('lg-mode', lg);
  $('#pairTitle').textContent = lg ? 'وافق على الاتصال من التليفزيون' : 'اكتب الكود اللي ظاهر على التليفزيون';
  $('#pairRetry').textContent = lg ? 'جرّب تاني' : 'كود جديد';
}
function openLgPrompt() {
  pairMode(true);
  $('#pairErr').textContent = '';
  $('#pairMsg').textContent = 'هتظهر رسالة على شاشة الـ LG — اختار «سماح / Accept» بريموت التليفزيون.';
  if (state.sheet !== 'pairSheet') openSheet('pairSheet');
}
function openPair() {
  pairMode(false);
  code = '';
  drawCode();
  $('#pairErr').textContent = '';
  $('#pairMsg').textContent = 'بنطلب الكود من التليفزيون…';
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
  del.setAttribute('aria-label', 'مسح');
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
    $('#pairMsg').textContent = 'بنتأكد من الكود…';
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
$('#pairCancel').onclick = () => { TV.pairCancel(); closeSheet(); show('devices'); TV.disconnect(); state.host = ''; };
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
  toast('اتبعت ✔');
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
  { label: 'Shahid', link: 'market://launch?id=net.mbc.shahidTV', color: '#18c37e', glyph: 'ش' },
  { label: 'STREAM IPTV', link: 'market://launch?id=com.stream.iptv', color: '#7b5cff', glyph: 'TV' },
  { label: 'Prime Video', link: 'https://app.primevideo.com', color: '#1399ff', glyph: 'P' },
  { label: 'Spotify', link: 'spotify://', color: '#1db954', glyph: '♫' },
  { label: 'Google Play', link: 'market://launch?id=com.android.vending', color: '#01875f', glyph: '◆' },
];
const PKG_NAMES = {
  'com.google.android.youtube.tv': 'YouTube', 'com.netflix.ninja': 'Netflix', 'net.mbc.shahidTV': 'Shahid',
  'com.stream.iptv': 'STREAM IPTV', 'com.amazon.amazonvideo.livingroom': 'Prime Video', 'com.spotify.tv.android': 'Spotify',
  'com.google.android.tvlauncher': 'الشاشة الرئيسية', 'com.google.android.apps.tv.launcherx': 'الشاشة الرئيسية',
  'com.android.vending': 'Google Play', 'com.android.tv.settings': 'الإعدادات', 'com.mitv.tvhome': 'الشاشة الرئيسية',
  'com.google.android.katniss': 'بحث جوجل', 'com.webos.app.home': 'الشاشة الرئيسية', 'com.webos.app.livetv': 'Live TV',
  'com.webos.app.hdmi1': 'HDMI 1', 'com.webos.app.hdmi2': 'HDMI 2', 'com.webos.app.hdmi3': 'HDMI 3', 'com.webos.app.browser': 'المتصفح',
  'com.palm.app.settings': 'الإعدادات', 'youtube.leanback.v4': 'YouTube', 'netflix': 'Netflix', 'amazon': 'Prime Video', 'org.videolan.vlc': 'VLC', 'com.disney.disneyplus': 'Disney+',
};
/* ------------------------------------------------------------- المصادر */
function renderInputs() {
  const grid = $('#inputGrid');
  grid.innerHTML = '';
  let list;
  if (state.kind === 'lg') {
    if (!state.tvInputs.length) {
      grid.innerHTML = '<p class="muted small">بنجيب المصادر من التليفزيون…</p>';
      return;
    }
    list = state.tvInputs.map(i => ({ label: i.label || i.id, sub: i.connected ? 'متوصل بيه جهاز' : 'مفيش جهاز', on: i.connected,
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
    $('small', b).textContent = i.active ? 'شغال دلوقتي' : i.sub;
    b.onclick = () => { buzz(); i.go(); closeSheet(); toast('بيغيّر لـ ' + i.label + '…'); };
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
    grid.innerHTML = '<p class="muted small">بنجيب التطبيقات من التليفزيون…</p>';
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
    b.onclick = () => { buzz(); TV.launch('lg:' + a.id); closeSheet(); toast('بيفتح ' + (a.title || a.id) + '…'); };
    grid.appendChild(b);
  });
}
function renderApps() {
  const grid = $('#appsGrid');
  grid.innerHTML = '';
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
    b.onclick = () => { buzz(); TV.launch(a.link); closeSheet(); toast('بيفتح ' + a.label + '…'); };
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
  if (!link) return toast('اكتب اسم الباكدج أو الرابط');
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
    $('#tvActions').hidden = !state.host;
    $('#tvInfo').hidden = !state.host;
    let v = ''; try { v = TV.appVersion(); } catch (_) {}
    $('#verText').textContent = v ? 'نسخة التطبيق ' + v : '';
    renderThemes();
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
  toast(ok ? 'اتحفظ ✔ — دلوقتي زرار ⏻ يشغّل التليفزيون' : 'العنوان مش صحيح — لازم يكون زي a8:23:fe:12:34:56');
  if (ok) $('#macInput').blur();
};
$('#diagShare').onclick = () => { try { TV.share(TV.diagnostics()); } catch (_) {} };
$('#diagBox').ontoggle = () => { try { $('#diagText').textContent = TV.diagnostics(); } catch (_) {} };
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
  { id: 'midnight', name: 'ليلي برتقالي', bg: '#0b0d12', panel: '#1c2230', accent: '#ff6a1f', bar: '#0b0d12' },
  { id: 'glass', name: 'زجاج', bg: 'linear-gradient(150deg,#6d3cff,#0b1530 60%,#00b3ff)', panel: 'rgba(255,255,255,.22)', accent: '#5ee7ff', bar: '#1d0f45' },
  { id: 'light', name: 'فاتح', bg: '#e6eaf1', panel: '#f4f6fa', accent: '#3d6bff', bar: '#e6eaf1', light: true },
  { id: 'neon', name: 'نيون', bg: '#04050a', panel: '#0a0d16', accent: '#00f0ff', ring: '#ff2bd6', bar: '#04050a' },
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
  $('#updNow').textContent = 'بيبدأ التحميل…';
  TV.startUpdate();
};
$('#checkUpdBtn').onclick = () => { toast('بنشوف لو فيه تحديث…'); TV.checkUpdate(true); };

/* --------------------------------------------------------------- البداية */
renderSaved();
if (!savedDevices().length) scan();
// Java ممكن يكون خلص تجهيز قبل ما الصفحة تحمل
// LG مش محتاج مفتاح الأمان بتاع Android TV، فبنوصل على طول من غير ما نستنى
(function connectLgNow() {
  let last = '';
  try { last = TV.lastHost(); } catch (_) {}
  const d = last && savedDevices().find(x => x.host === last);
  if (d && d.kind === 'lg') connect(d.host, d.name, 'lg');
})();
try { if (TV.isReady()) window.onTv({ type: 'ready' }); } catch (_) {}
// كل ما التطبيق يتفتح بيشوف لو فيه نسخة أحدث
setTimeout(() => { try { TV.checkUpdate(false); } catch (_) {} }, 1200);
