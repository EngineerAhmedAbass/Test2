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
      fire({ type: 'found', host: '192.168.1.40', name: '' }, 1500);
      fire({ type: 'scanDone' }, 2500);
    },
    connect(host, name) {
      devices = [{ host, name }].concat(devices.filter(d => d.host !== host));
      fire({ type: 'status', state: 'connecting', host, name }, 50);
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
    vibrate() {}, setVibrate() {}, getVibrate: () => true, resetIdentity() {},
  };
}

const state = { host: '', name: '', connected: false, sheet: null, ready: false };

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
        connect(d.host, d.name);
      }
      break;
    }
    case 'found': addFound(e.host, e.name); break;
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
    case 'app': $('#appName').textContent = appLabel(e.pkg); break;
    case 'power':
      $('.power').classList.toggle('is-off', !e.on);
      break;
    case 'notConnected':
      toast(state.connected ? 'بيوصل…' : 'مش متوصل بالتليفزيون');
      break;
    case 'error': toast(e.msg); break;
    case 'resume': break;
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

function devItem(host, name, saved) {
  const li = document.createElement('li');
  const b = document.createElement('button');
  b.className = 'dev';
  b.innerHTML = '<span class="ico"><svg><use href="#i-tv"/></svg></span><span class="meta"><b></b><small></small></span>';
  $('b', b).textContent = name || 'Android TV';
  $('small', b).textContent = host;
  b.onclick = () => connect(host, name || host);
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
  saved.forEach(d => list.appendChild(devItem(d.host, d.name, true)));
  $('#savedCard').hidden = !saved.length;
}

const foundHosts = new Map();
function addFound(host, name) {
  const prev = foundHosts.get(host);
  if (prev !== undefined && (prev || !name)) return; // عندنا اسمه بالفعل
  foundHosts.set(host, name);
  $('#foundEmpty').hidden = true;
  const list = $('#foundList');
  const old = $$('li', list).find(li => li.dataset.host === host);
  const li = devItem(host, name, false);
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
  connect(ip, ip);
};

function connect(host, name) {
  state.host = host;
  state.name = name;
  $('#tvName').textContent = name;
  $('#appName').textContent = '';
  $('#volWrap').hidden = true;
  setStatus({ state: 'connecting', name });
  show('remote');
  TV.connect(host, name);
}

function show(id) {
  $$('.view').forEach(v => v.classList.toggle('active', v.id === id));
  if (id === 'devices') renderSaved();
}

$('#tvNameBtn').onclick = () => { if (!state.connected) TV.connect(state.host, state.name); else openSheet('settingsSheet'); };
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
function openPair() {
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
$('#pairRetry').onclick = () => { TV.pairCancel(); openPair(); };

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
  pad.addEventListener('pointerdown', (e) => {
    id = e.pointerId; pad.setPointerCapture(id);
    sx = ax = e.clientX; sy = ay = e.clientY; moved = false; t0 = Date.now();
  });
  pad.addEventListener('pointermove', (e) => {
    if (e.pointerId !== id) return;
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
      TV.key(23); buzz();
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
  'com.google.android.katniss': 'بحث جوجل', 'org.videolan.vlc': 'VLC', 'com.disney.disneyplus': 'Disney+',
};
function appLabel(pkg) {
  const custom = customApps().find(a => a.link === 'market://launch?id=' + pkg);
  return '▶ ' + (PKG_NAMES[pkg] || (custom && custom.label) || pkg);
}
function customApps() {
  try { return JSON.parse(localStorage.getItem('apps') || '[]'); } catch (_) { return []; }
}
function saveCustom(list) {
  try { localStorage.setItem('apps', JSON.stringify(list)); } catch (_) {}
}
function renderApps() {
  const grid = $('#appsGrid');
  grid.innerHTML = '';
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
  if (id === 'settingsSheet') $('#vibrateChk').checked = !!TV.getVibrate();
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

/* --------------------------------------------------------------- البداية */
renderSaved();
if (!savedDevices().length) scan();
// Java ممكن يكون خلص تجهيز قبل ما الصفحة تحمل
try { if (TV.isReady()) window.onTv({ type: 'ready' }); } catch (_) {}
