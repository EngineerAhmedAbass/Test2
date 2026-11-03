/* STREAM — مشغل IPTV (تصميم D) */
(() => {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const PAGE = 60;

  const ICON = {
    live: '<svg viewBox="0 0 24 24"><rect x="3" y="6" width="18" height="13" rx="2.5"/><path d="m8 2 4 4 4-4"/></svg>',
    movie: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="2.5"/><path d="M7 3v18M17 3v18M3 12h18"/></svg>',
    series: '<svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="13" rx="2.5"/><path d="M7 3h10"/></svg>',
    favs: '<svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/></svg>',
    star: '<svg class="star" viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/></svg>',
    play: '<svg class="fill" viewBox="0 0 24 24"><path d="M7 4.5v15l12.5-7.5z"/></svg>',
  };

  const state = {
    source: null,
    userInfo: null,
    view: null, // live | movie | series | favs
    cache: new Map(),
    // القنوات
    live: { cats: [], cat: null, items: [], filtered: [], current: null },
    // الشبكة
    grid: { cats: [], cat: null, items: [], filtered: [], shown: 0 },
    m3u: null,
    current: null, // اللي شغال في المشغل دلوقتي
    playerMode: null, // live | modal
  };

  // =====================================================================
  //                               أدوات
  // =====================================================================
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
    del(k) { try { localStorage.removeItem(k); } catch {} },
  };
  const acct = () => state.source.type === 'xtream' ? state.source.server + '|' + state.source.username : state.source.url;
  const getFavs = () => store.get('iptv.favs.' + acct(), []);
  const getRecent = () => store.get('iptv.recent.' + acct(), []);
  const isFav = (it) => !!it && getFavs().some((f) => f.key === it.key);
  function toggleFav(it) {
    const on = isFav(it);
    store.set('iptv.favs.' + acct(), on ? getFavs().filter((f) => f.key !== it.key) : [it, ...getFavs()]);
    toast(on ? 'اتشال من المفضلة' : 'اتضاف للمفضلة');
    return !on;
  }
  function addRecent(it) {
    if (isLive(it)) return;
    store.set('iptv.recent.' + acct(), [it, ...getRecent().filter((r) => r.key !== it.key)].slice(0, 40));
  }
  const isLive = (it) => it && (it.type === 'live' || it.type === 'stream');

  function toast(text) {
    const el = document.createElement('div');
    el.className = 'toast';
    el.textContent = text;
    $('#toasts').appendChild(el);
    setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 300); }, 2000);
  }
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const hue = (s) => { let h = 0; for (const c of String(s)) h = (h * 31 + c.charCodeAt(0)) % 360; return h; };
  const mono = (name) => String(name || '?').replace(/[^\p{L}\p{N} ]/gu, '').trim().split(/\s+/).slice(0, 2).map((w) => w[0]).join('').toUpperCase() || '?';
  function img(src, onFail) {
    const i = new Image();
    i.loading = 'lazy';
    i.decoding = 'async';
    i.referrerPolicy = 'no-referrer';
    i.onerror = () => { i.remove(); onFail && onFail(); };
    i.src = src;
    return i;
  }
  function logoInto(box, it) {
    box.textContent = '';
    const fb = () => { box.textContent = mono(it.name); box.style.background = `hsl(${hue(it.name)} 45% 28%)`; };
    if (it.logo) box.appendChild(img(it.logo, fb)); else fb();
  }
  const pad = (n) => String(n).padStart(2, '0');
  const hhmm = (ms) => { const d = new Date(ms); return pad(d.getHours()) + ':' + pad(d.getMinutes()); };
  const fmtRating = (r) => { const n = parseFloat(r); return n > 0 ? (n > 10 ? n / 10 : n).toFixed(1) : ''; };
  const year = (s) => (String(s || '').match(/(19|20)\d{2}/) || [''])[0];
  const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

  // =====================================================================
  //                                API
  // =====================================================================
  async function xtream(params = {}) {
    const s = state.source;
    const q = new URLSearchParams({ server: s.server, username: s.username, password: s.password, ...params });
    const r = await fetch('/api/xtream?' + q);
    const text = await r.text();
    let data;
    try { data = JSON.parse(text); } catch { throw new Error('رد غير متوقع من السيرفر'); }
    if (!r.ok) throw new Error((data && data.error) || 'خطأ ' + r.status);
    return data;
  }
  function cached(key, fn) {
    if (!state.cache.has(key)) {
      const p = fn();
      state.cache.set(key, p);
      p.catch(() => state.cache.delete(key));
    }
    return state.cache.get(key);
  }

  function parseM3u(text) {
    const groups = new Map();
    let meta = null;
    let n = 0;
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line) continue;
      if (line.startsWith('#EXTINF')) {
        const attr = (k) => (line.match(new RegExp(k + '="([^"]*)"', 'i')) || [])[1] || '';
        const comma = line.lastIndexOf(',');
        meta = { name: (comma >= 0 ? line.slice(comma + 1) : '').trim() || attr('tvg-name') || 'بدون اسم', logo: attr('tvg-logo'), group: attr('group-title') || 'أخرى' };
      } else if (!line.startsWith('#')) {
        const m = meta || { name: line, logo: '', group: 'أخرى' };
        if (!groups.has(m.group)) groups.set(m.group, []);
        groups.get(m.group).push({ key: 'm3u:' + line, type: 'stream', name: m.name, logo: m.logo, url: line, group: m.group, num: ++n });
        meta = null;
      }
    }
    return { groups };
  }

  function liveExt() {
    const f = state.userInfo?.allowed_output_formats || [];
    return f.length && !f.includes('m3u8') ? 'ts' : 'm3u8';
  }
  function streamUrl(it) {
    if (!it) return null;
    if (it.url) return it.url;
    const s = state.source;
    const cred = `${encodeURIComponent(s.username)}/${encodeURIComponent(s.password)}`;
    if (it.type === 'live') return `${s.server}/live/${cred}/${it.id}.${liveExt()}`;
    if (it.type === 'movie') return `${s.server}/movie/${cred}/${it.id}.${it.ext || 'mp4'}`;
    if (it.type === 'episode') return `${s.server}/series/${cred}/${it.id}.${it.ext || 'mp4'}`;
    return null;
  }

  // ---------- جدول البرامج (EPG) ----------
  const epg = { cache: new Map(), pending: new Map(), queue: [], active: 0 };
  const b64 = (s) => {
    if (!s) return '';
    try { return new TextDecoder().decode(Uint8Array.from(atob(s), (c) => c.charCodeAt(0))); } catch { return s; }
  };
  const toMs = (ts, str) => (Number(ts) > 0 ? Number(ts) * 1000 : Date.parse(String(str || '').replace(' ', 'T')) || 0);
  function getEpg(it) {
    if (!it || it.type !== 'live') return Promise.resolve([]);
    const hit = epg.cache.get(it.id);
    if (hit && Date.now() - hit.t < 10 * 60 * 1000 && !(hit.list.length && hit.list[hit.list.length - 1].end < Date.now())) return Promise.resolve(hit.list);
    if (epg.pending.has(it.id)) return epg.pending.get(it.id);
    const p = new Promise((resolve) => { epg.queue.push({ id: it.id, resolve }); pumpEpg(); });
    epg.pending.set(it.id, p);
    p.then(() => epg.pending.delete(it.id));
    return p;
  }
  function pumpEpg() {
    while (epg.active < 4 && epg.queue.length) {
      const job = epg.queue.pop(); // الأحدث الأول (اللي ظاهر على الشاشة دلوقتي)
      epg.active++;
      xtream({ action: 'get_short_epg', stream_id: job.id, limit: 8 })
        .then((d) => (d && d.epg_listings) || [])
        .catch(() => [])
        .then((arr) => {
          const list = arr.map((e) => ({
            title: b64(e.title), desc: b64(e.description),
            start: toMs(e.start_timestamp, e.start), end: toMs(e.stop_timestamp, e.end || e.stop),
          })).filter((x) => x.end > x.start).sort((a, b) => a.start - b.start);
          epg.cache.set(job.id, { t: Date.now(), list });
          job.resolve(list);
        })
        .finally(() => { epg.active--; pumpEpg(); });
    }
  }
  function epgNow(it) {
    const hit = it && epg.cache.get(it.id);
    if (!hit) return null;
    const t = Date.now();
    const i = hit.list.findIndex((p) => p.start <= t && p.end > t);
    return { now: hit.list[i] || null, next: i >= 0 ? hit.list[i + 1] || null : hit.list.find((p) => p.start > t) || null };
  }
  const pct = (p) => Math.max(0, Math.min(100, ((Date.now() - p.start) / (p.end - p.start)) * 100));

  // =====================================================================
  //                               المشغل
  // =====================================================================
  const video = $('#video');
  const pwrap = $('#player-wrap');
  let hls = null;
  let mts = null;

  function attachPlayer(slot) {
    if (pwrap.parentElement !== slot) slot.appendChild(pwrap);
    pwrap.classList.add('attached');
  }
  function stopPlayer() {
    if (hls) { hls.destroy(); hls = null; }
    if (mts) { try { mts.destroy(); } catch {} mts = null; }
    video.removeAttribute('src');
    video.load();
  }
  function pmsg(text, error) {
    const el = $('#p-msg');
    el.classList.toggle('hidden', !text);
    el.classList.toggle('error', !!error);
    $('span', el).textContent = text || '';
  }
  function kindOf(url) {
    const p = url.split('?')[0].toLowerCase();
    if (/\.m3u8?$/.test(p)) return 'hls';
    if (p.endsWith('.ts')) return 'ts';
    if (/\.[a-z0-9]{2,4}$/.test(p)) return 'file';
    return 'ts';
  }

  function play(it, opts = {}) {
    const url = opts.url || streamUrl(it);
    if (!url) return;
    state.current = it;
    stopPlayer();
    pmsg('جاري التحميل...');
    addRecent(it);
    $('#p-name').textContent = it.name;
    $('#p-live').classList.toggle('hidden', !isLive(it));
    $('#p-quality').textContent = '';
    $('#prev-btn').classList.toggle('hidden', state.playerMode !== 'live');
    $('#next-btn').classList.toggle('hidden', state.playerMode !== 'live');
    updateFavBtn();
    wake();

    const src = '/proxy?url=' + encodeURIComponent(url);
    const kind = kindOf(url);
    const fail = (why) => pmsg('القناة دي مش راضية تشتغل' + (why ? `\n(${why})` : '') + '\nجرّب قناة تانية، أو انسخ الرابط وشغّله على VLC.', true);

    if (kind === 'hls' && window.Hls && Hls.isSupported()) {
      hls = new Hls({ enableWorker: true, maxBufferLength: 20, backBufferLength: 30 });
      hls.loadSource(src);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
      hls.on(Hls.Events.ERROR, (_, d) => {
        if (!d.fatal) return;
        if (d.type === Hls.ErrorTypes.MEDIA_ERROR) return hls.recoverMediaError();
        if (it.type === 'live' && !opts.triedTs) return play(it, { url: url.replace(/\.m3u8$/, '.ts'), triedTs: true });
        fail(d.details);
      });
    } else if (kind === 'ts' && window.mpegts && mpegts.isSupported()) {
      mts = mpegts.createPlayer(
        { type: 'mpegts', isLive: isLive(it), url: new URL(src, location.href).href },
        { enableStashBuffer: true, stashInitialSize: 256 * 1024, liveBufferLatencyChasing: false, lazyLoad: false }
      );
      mts.attachMediaElement(video);
      mts.load();
      mts.on(mpegts.Events.ERROR, (t, d) => fail(d || t));
      video.play().catch(() => {});
    } else {
      video.src = src;
      video.play().catch(() => {});
    }
  }

  video.addEventListener('playing', () => pmsg(''));
  video.addEventListener('waiting', () => { if (state.current) pmsg('جاري التحميل...'); });
  video.addEventListener('resize', () => { $('#p-quality').textContent = video.videoHeight ? video.videoHeight + 'p' : ''; });
  video.addEventListener('error', () => {
    if (!hls && !mts && state.current) pmsg('صيغة الفيديو مش مدعومة هنا.\nانسخ الرابط وشغّله على VLC.', true);
  });

  // إخفاء أزرار المشغل لما مفيش حركة
  let idleT;
  function wake() {
    pwrap.classList.remove('idle');
    clearTimeout(idleT);
    idleT = setTimeout(() => { if (!video.paused) pwrap.classList.add('idle'); }, 3000);
  }
  ['mousemove', 'touchstart', 'click'].forEach((e) => pwrap.addEventListener(e, wake, { passive: true }));

  function updateFavBtn() {
    const on = isFav(state.current);
    $('#fav-btn').classList.toggle('on', on);
  }
  $('#fav-btn').onclick = () => {
    if (!state.current) return;
    toggleFav(state.current);
    updateFavBtn();
    if (isLive(state.current)) chList.refresh();
  };
  $('#copy-btn').onclick = async () => {
    const url = streamUrl(state.current);
    if (!url) return;
    try { await navigator.clipboard.writeText(url); toast('الرابط اتنسخ — افتحه في VLC'); }
    catch { prompt('انسخ الرابط:', url); }
  };
  $('#fs-btn').onclick = toggleFullscreen;
  function toggleFullscreen() {
    if (document.fullscreenElement || document.webkitFullscreenElement) {
      (document.exitFullscreen || document.webkitExitFullscreen).call(document);
    } else {
      const f = pwrap.requestFullscreen || pwrap.webkitRequestFullscreen;
      if (f) f.call(pwrap).then?.(() => screen.orientation?.lock?.('landscape').catch(() => {})).catch?.(() => {});
    }
  }
  $('#prev-btn').onclick = () => zap(-1);
  $('#next-btn').onclick = () => zap(1);

  // =====================================================================
  //                       لستة القنوات (Virtual list)
  // =====================================================================
  class VList {
    constructor(el, renderRow) {
      this.el = el;
      this.renderRow = renderRow;
      this.items = [];
      this.rows = new Map();
      this.spacer = document.createElement('div');
      this.spacer.className = 'spacer';
      el.appendChild(this.spacer);
      this.readRowH();
      let raf = 0;
      el.addEventListener('scroll', () => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; this.draw(); }); }, { passive: true });
      new ResizeObserver(() => { this.readRowH(); this.refresh(); }).observe(el);
    }
    readRowH() { this.rowH = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--row')) || 62; }
    setItems(items) {
      this.items = items;
      this.spacer.style.height = items.length * this.rowH + 'px';
      this.el.scrollTop = 0;
      this.refresh();
    }
    refresh() {
      for (const n of this.rows.values()) n.remove();
      this.rows.clear();
      this.spacer.style.height = this.items.length * this.rowH + 'px';
      this.draw();
    }
    range() {
      const top = this.el.scrollTop;
      const h = this.el.clientHeight || 600;
      return [Math.max(0, Math.floor(top / this.rowH) - 4), Math.min(this.items.length, Math.ceil((top + h) / this.rowH) + 4)];
    }
    draw() {
      const [a, b] = this.range();
      for (const [i, n] of this.rows) if (i < a || i >= b) { n.remove(); this.rows.delete(i); }
      for (let i = a; i < b; i++) {
        if (this.rows.has(i)) continue;
        const n = this.renderRow(this.items[i], i);
        n.style.top = i * this.rowH + 'px';
        this.el.appendChild(n);
        this.rows.set(i, n);
      }
      this.onDraw && this.onDraw(a, b);
    }
    scrollToIndex(i) {
      const y = i * this.rowH;
      const h = this.el.clientHeight;
      if (y < this.el.scrollTop || y + this.rowH > this.el.scrollTop + h) this.el.scrollTop = y - h / 2 + this.rowH / 2;
    }
  }

  const favKeySet = () => new Set(getFavs().map((f) => f.key));
  let favSet = new Set();
  const chList = new VList($('#ch-list'), (it, i) => {
    const row = document.createElement('div');
    row.className = 'ch-row' + (state.live.current?.key === it.key ? ' active' : '');
    row.dataset.key = it.key;
    row.innerHTML = '<span class="num"></span><span class="lg"></span><div class="tx"><div class="nm"></div><div class="pg"></div></div>';
    $('.num', row).textContent = it.num || i + 1;
    $('.nm', row).textContent = it.name;
    logoInto($('.lg', row), it);
    if (favSet.has(it.key)) $('.tx', row).insertAdjacentHTML('afterend', ICON.star);
    fillRowEpg(row, it);
    row.onclick = () => playLive(it);
    return row;
  });
  function fillRowEpg(row, it) {
    const nn = epgNow(it);
    const pg = $('.pg', row);
    if (nn && nn.now) {
      pg.textContent = nn.now.title;
      let bar = $('.bar', row);
      if (!bar) { bar = document.createElement('span'); bar.className = 'bar'; bar.innerHTML = '<i></i>'; row.appendChild(bar); }
      bar.firstChild.style.width = pct(nn.now) + '%';
    } else {
      pg.textContent = it.group && state.source.type === 'm3u' ? it.group : '';
    }
  }
  // نجيب جدول البرامج للقنوات اللي ظاهرة بس، بعد ما الاسكرول يهدى
  const loadVisibleEpg = debounce(() => {
    if (state.source?.type !== 'xtream') return;
    const [a, b] = chList.range();
    for (let i = a; i < b; i++) {
      const it = chList.items[i];
      if (!it || epg.cache.has(it.id)) continue;
      getEpg(it).then(() => {
        const row = chList.rows.get(i);
        if (row && row.dataset.key === it.key) fillRowEpg(row, it);
      });
    }
  }, 250);
  chList.onDraw = loadVisibleEpg;
  const refreshFavs = () => { favSet = favKeySet(); };

  // =====================================================================
  //                           التنقل بين الأقسام
  // =====================================================================
  const VIEWS = () => state.source.type === 'xtream'
    ? [['live', 'مباشر', ICON.live], ['movie', 'أفلام', ICON.movie], ['series', 'مسلسلات', ICON.series], ['favs', 'مكتبتي', ICON.favs]]
    : [['live', 'القنوات', ICON.live], ['favs', 'مكتبتي', ICON.favs]];

  function renderNav() {
    const nav = $('#nav');
    nav.innerHTML = '';
    for (const [id, label, icon] of VIEWS()) {
      const b = document.createElement('button');
      b.className = 'nav-btn';
      b.type = 'button';
      b.dataset.id = id;
      b.innerHTML = icon + `<span>${label}</span>`;
      b.onclick = () => go(id);
      nav.appendChild(b);
    }
  }

  function go(id) {
    if (state.view === id) return;
    const wasLive = state.view === 'live';
    state.view = id;
    $$('#nav .nav-btn').forEach((b) => b.classList.toggle('active', b.dataset.id === id));
    $('#view-live').classList.toggle('hidden', id !== 'live');
    $('#view-grid').classList.toggle('hidden', id === 'live');
    if (id === 'live') {
      enterLive();
    } else {
      if (wasLive && state.playerMode === 'live') { stopPlayer(); state.current = null; }
      openGrid(id);
    }
  }

  // =====================================================================
  //                             القنوات المباشرة
  // =====================================================================
  async function enterLive() {
    refreshFavs();
    if (state.live.cats.length) {
      chList.refresh();
      if (state.live.current) playLive(state.live.current, true);
      return;
    }
    $('#ch-list').insertAdjacentHTML('beforeend', '<div class="list-empty">جاري تحميل القنوات...</div>');
    try {
      let cats;
      if (state.source.type === 'm3u') {
        let total = 0;
        cats = [...state.m3u.groups].map(([name, list]) => { total += list.length; return { id: name, name, count: list.length }; });
        cats.unshift({ id: '__all', name: 'كل القنوات', count: total });
      } else {
        const data = await cached('get_live_categories', () => xtream({ action: 'get_live_categories' }));
        cats = [{ id: '__all', name: 'كل القنوات' }, ...(Array.isArray(data) ? data : []).map((c) => ({ id: c.category_id, name: c.category_name }))];
      }
      cats.unshift({ id: '__fav', name: '⭐ المفضلة' });
      state.live.cats = cats;
      const last = store.get('iptv.lastCat.' + acct(), null);
      const pick = cats.find((c) => c.id === last) || cats[2] || cats[1];
      await selectLiveCat(pick);
      const lastKey = store.get('iptv.lastLive.' + acct(), null);
      const lastIt = lastKey && state.live.items.find((x) => x.key === lastKey);
      if (lastIt) playLive(lastIt);
    } catch (e) {
      $('#ch-list').insertAdjacentHTML('beforeend', `<div class="list-empty">حصل خطأ: ${esc(e.message)}</div>`);
    } finally {
      $$('#ch-list .list-empty').forEach((n) => n.remove());
    }
  }

  async function selectLiveCat(cat) {
    state.live.cat = cat;
    store.set('iptv.lastCat.' + acct(), cat.id);
    $('#cat-name').textContent = cat.name;
    $('#ch-search').value = '';
    $('#ch-count').textContent = '';
    chList.setItems([]);
    $$('#ch-list .list-empty').forEach((n) => n.remove());
    $('#ch-list').insertAdjacentHTML('beforeend', '<div class="list-empty">جاري التحميل...</div>');
    let items = [];
    try {
      if (cat.id === '__fav') items = getFavs().filter(isLive);
      else if (state.source.type === 'm3u') items = cat.id === '__all' ? [...state.m3u.groups.values()].flat() : state.m3u.groups.get(cat.id) || [];
      else {
        const params = { action: 'get_live_streams' };
        if (cat.id !== '__all') params.category_id = cat.id;
        const data = await cached('live:' + cat.id, () => xtream(params));
        items = (Array.isArray(data) ? data : []).map((x) => ({
          key: 'live:' + x.stream_id, type: 'live', id: x.stream_id, name: x.name || '', logo: x.stream_icon, num: x.num,
        }));
      }
    } catch (e) {
      $$('#ch-list .list-empty').forEach((n) => n.remove());
      $('#ch-list').insertAdjacentHTML('beforeend', `<div class="list-empty">حصل خطأ: ${esc(e.message)}</div>`);
      return;
    }
    if (state.live.cat !== cat) return;
    $$('#ch-list .list-empty').forEach((n) => n.remove());
    state.live.items = items;
    filterLive();
  }

  function filterLive() {
    const q = $('#ch-search').value.trim().toLowerCase();
    const items = state.live.items;
    state.live.filtered = q ? items.filter((i) => i.name.toLowerCase().includes(q)) : items;
    $('#ch-count').textContent = `${state.live.filtered.length} قناة`;
    refreshFavs();
    chList.setItems(state.live.filtered);
    $$('#ch-list .list-empty').forEach((n) => n.remove());
    if (!state.live.filtered.length) {
      $('#ch-list').insertAdjacentHTML('beforeend', `<div class="list-empty">${state.live.cat?.id === '__fav' && !q ? 'مفيش قنوات في المفضلة لسه.<br>دوس على النجمة وانت بتتفرج.' : 'مفيش قنوات'}</div>`);
    }
    const idx = state.live.filtered.findIndex((x) => x.key === state.live.current?.key);
    if (idx >= 0) chList.scrollToIndex(idx);
    renderEpgGrid();
  }
  $('#ch-search').addEventListener('input', debounce(filterLive, 120));
  $('#cat-btn').onclick = () => openCats(state.live.cats, state.live.cat, selectLiveCat);

  function playLive(it, resume) {
    state.live.current = it;
    state.playerMode = 'live';
    store.set('iptv.lastLive.' + acct(), it.key);
    attachPlayer($('#live-slot'));
    if (!resume || state.current?.key !== it.key || video.paused) play(it);
    $$('#ch-list .ch-row').forEach((r) => r.classList.toggle('active', r.dataset.key === it.key));
    const idx = state.live.filtered.findIndex((x) => x.key === it.key);
    if (idx >= 0) chList.scrollToIndex(idx);
    updateNow();
    getEpg(it).then(() => { if (state.live.current === it) { updateNow(); renderEpgGrid(); } });
    renderEpgGrid();
  }

  function zap(step) {
    const list = state.live.filtered;
    if (state.view !== 'live' || list.length < 2) return;
    const i = list.findIndex((x) => x.key === state.live.current?.key);
    playLive(list[(i + step + list.length) % list.length]);
  }

  function updateNow() {
    const it = state.live.current;
    if (!it) return;
    const nn = epgNow(it);
    $('#now-title').textContent = nn?.now ? nn.now.title : it.name;
    $('#now-sub').textContent = nn?.now ? `${it.name} • ${hhmm(nn.now.start)} – ${hhmm(nn.now.end)}` : (state.live.cat?.name || '');
    $('#now-prog').classList.toggle('hidden', !nn?.now);
    if (nn?.now) $('#now-prog i').style.width = pct(nn.now) + '%';
    $('#now-next').textContent = nn?.next ? `${hhmm(nn.next.start)}  ${nn.next.title}` : '—';
  }

  // ---------- شبكة جدول البرامج ----------
  function renderEpgGrid() {
    const box = $('#epg');
    if (state.view !== 'live') return;
    if (state.source.type !== 'xtream') { box.classList.add('hidden'); return; }
    box.classList.remove('hidden');
    const list = state.live.filtered;
    if (!list.length) { box.innerHTML = '<div class="epg-empty">مفيش قنوات</div>'; return; }
    let i = list.findIndex((x) => x.key === state.live.current?.key);
    if (i < 0) i = 0;
    const rows = list.slice(i, i + 4);
    const span = 150 * 60 * 1000;
    const ws = Math.floor(Date.now() / (30 * 60 * 1000)) * 30 * 60 * 1000;
    const we = ws + span;
    const pos = (t) => ((t - ws) / span) * 100;

    let html = '<div class="epg-head"><div class="epg-name lab">جدول البرامج</div><div class="epg-track">';
    for (let k = 0; k < 5; k++) html += `<span style="right:${k * 20}%">${hhmm(ws + k * 30 * 60 * 1000)}</span>`;
    html += '</div></div>';
    for (const it of rows) {
      const hit = epg.cache.get(it.id);
      html += `<div class="epg-row${it.key === state.live.current?.key ? ' active' : ''}" data-key="${esc(it.key)}"><div class="epg-name">${esc(it.name)}</div><div class="epg-track">`;
      if (!hit) html += '<div class="epg-prog" style="right:0;width:100%"><div>جاري التحميل...</div></div>';
      else if (!hit.list.length) html += '<div class="epg-prog past" style="right:0;width:100%"><div>مفيش جدول برامج</div></div>';
      else {
        for (const p of hit.list) {
          if (p.end <= ws || p.start >= we) continue;
          const r = pos(Math.max(p.start, ws));
          const w = pos(Math.min(p.end, we)) - r;
          const cls = p.end <= Date.now() ? 'past' : p.start <= Date.now() ? 'now' : '';
          html += `<div class="epg-prog ${cls}" style="right:${r}%;width:${w}%" title="${esc(hhmm(p.start) + ' ' + p.title)}"><div>${esc(p.title)}</div></div>`;
        }
      }
      html += `<div class="epg-now" style="right:${pos(Date.now())}%"></div></div></div>`;
      if (!hit) getEpg(it).then(() => renderEpgGridSoon());
    }
    box.innerHTML = html;
    $$('.epg-row', box).forEach((r) => {
      r.onclick = () => { const it = list.find((x) => x.key === r.dataset.key); if (it) playLive(it); };
    });
  }
  const renderEpgGridSoon = debounce(renderEpgGrid, 120);

  // =====================================================================
  //                     الشبكة (أفلام / مسلسلات / مكتبتي)
  // =====================================================================
  const grid = $('#grid');
  const TITLES = { movie: 'أفلام', series: 'مسلسلات', favs: 'مكتبتي' };

  async function openGrid(id) {
    $('#grid-title').textContent = TITLES[id];
    $('#grid-search').value = '';
    $('#view-grid').scrollTop = 0;
    state.grid.cats = [];
    renderChips();
    skeleton();
    try {
      let cats;
      if (id === 'favs') cats = [{ id: 'favs', name: 'المفضلة' }, { id: 'recent', name: 'شوهد مؤخرًا' }];
      else {
        const action = id === 'movie' ? 'get_vod_categories' : 'get_series_categories';
        const data = await cached(action, () => xtream({ action }));
        cats = [{ id: '__all', name: 'الكل' }, ...(Array.isArray(data) ? data : []).map((c) => ({ id: c.category_id, name: c.category_name }))];
      }
      if (state.view !== id) return;
      state.grid.cats = cats;
      selectGridCat(id === 'favs' || cats.length < 2 ? cats[0] : cats[1]);
    } catch (e) {
      empty('حصل خطأ', e.message);
    }
  }

  function renderChips() {
    const box = $('#chips');
    box.innerHTML = '';
    for (const c of state.grid.cats.slice(0, 30)) {
      const b = document.createElement('button');
      b.className = 'chip' + (state.grid.cat?.id === c.id ? ' active' : '');
      b.type = 'button';
      b.textContent = c.name;
      b.onclick = () => selectGridCat(c);
      box.appendChild(b);
    }
    $('#grid-cat-btn').classList.toggle('hidden', state.grid.cats.length < 6);
  }
  $('#grid-cat-btn').onclick = () => openCats(state.grid.cats, state.grid.cat, (c) => {
    if (!state.grid.cats.slice(0, 30).includes(c)) state.grid.cats = [c, ...state.grid.cats.filter((x) => x !== c)];
    selectGridCat(c);
  });

  async function selectGridCat(cat) {
    state.grid.cat = cat;
    renderChips();
    $('#chips .chip.active')?.scrollIntoView({ inline: 'nearest', block: 'nearest' });
    $('#grid-search').value = '';
    const view = state.view;
    skeleton();
    try {
      let items;
      if (view === 'favs') items = cat.id === 'recent' ? getRecent() : getFavs();
      else {
        const action = view === 'movie' ? 'get_vod_streams' : 'get_series';
        const params = { action };
        if (cat.id !== '__all') params.category_id = cat.id;
        const data = await cached(action + ':' + cat.id, () => xtream(params));
        items = (Array.isArray(data) ? data : []).map((x) => normalize(view, x));
      }
      if (state.view !== view || state.grid.cat !== cat) return;
      state.grid.items = items;
      filterGrid();
    } catch (e) {
      empty('حصل خطأ', e.message);
    }
  }

  function normalize(view, x) {
    const rating = fmtRating(x.rating_5based ? x.rating_5based * 2 : x.rating);
    if (view === 'movie') {
      return { key: 'movie:' + x.stream_id, type: 'movie', id: x.stream_id, name: x.name || '', logo: x.stream_icon, ext: x.container_extension, rating, year: year(x.year || x.releaseDate || x.name) };
    }
    return { key: 'series:' + x.series_id, type: 'series', id: x.series_id, name: x.name || '', logo: x.cover, rating, year: year(x.releaseDate || x.year), plot: x.plot, genre: x.genre };
  }

  function skeleton() {
    grid.className = 'grid';
    grid.innerHTML = '<div class="skeleton"></div>'.repeat(14);
    $('#grid-count').textContent = '';
  }
  function empty(title, sub) {
    grid.className = 'grid';
    grid.innerHTML = `<div class="empty"><h4>${esc(title)}</h4><div>${esc(sub || '')}</div></div>`;
  }

  function filterGrid() {
    const q = $('#grid-search').value.trim().toLowerCase();
    const g = state.grid;
    g.filtered = q ? g.items.filter((i) => (i.name || '').toLowerCase().includes(q)) : g.items;
    $('#grid-count').textContent = g.items.length ? (q ? `${g.filtered.length} من ${g.items.length}` : String(g.items.length)) : '';
    g.shown = 0;
    grid.innerHTML = '';
    grid.className = 'grid' + (g.filtered.length && g.filtered.every(isLive) ? ' live' : '');
    if (!g.filtered.length) {
      if (q) return empty('مفيش نتايج', `مالقيناش حاجة باسم "${q}"`);
      if (state.view === 'favs') return empty(g.cat?.id === 'recent' ? 'لسه ماتفرجتش على حاجة' : 'المفضلة فاضية', 'دوس على النجمة في المشغل أو صفحة الفيلم عشان تضيف هنا');
      return empty('مفيش حاجة هنا', 'جرّب قسم تاني');
    }
    moreGrid();
  }
  $('#grid-search').addEventListener('input', debounce(filterGrid, 150));

  function moreGrid() {
    const g = state.grid;
    const slice = g.filtered.slice(g.shown, g.shown + PAGE);
    const favs = favKeySet();
    const frag = document.createDocumentFragment();
    slice.forEach((it, i) => {
      const el = isLive(it) ? chanCard(it) : posterCard(it);
      el.style.animationDelay = Math.min(i * 15, 300) + 'ms';
      if (favs.has(it.key)) el.insertAdjacentHTML('beforeend', `<span class="fav">${ICON.star}</span>`);
      frag.appendChild(el);
    });
    grid.appendChild(frag);
    g.shown += slice.length;
  }
  new IntersectionObserver((e) => {
    const g = state.grid;
    if (e[0].isIntersecting && g.shown && g.shown < g.filtered.length) moreGrid();
  }, { root: $('#view-grid'), rootMargin: '900px' }).observe($('#sentinel'));

  function posterCard(it) {
    const el = document.createElement('div');
    el.className = 'card';
    el.tabIndex = 0;
    el.innerHTML = '<div class="art"></div><div class="t"></div><div class="s"></div>';
    const art = $('.art', el);
    const fb = () => { art.insertAdjacentHTML('afterbegin', `<div class="fallback" style="background:hsl(${hue(it.name)} 40% 22%)">${esc(mono(it.name))}</div>`); };
    if (it.logo) art.appendChild(img(it.logo, fb)); else fb();
    if (it.rating) art.insertAdjacentHTML('beforeend', `<span class="rate">★ ${esc(it.rating)}</span>`);
    $('.t', el).textContent = it.name;
    $('.s', el).textContent = [it.type === 'series' ? 'مسلسل' : it.type === 'episode' ? 'حلقة' : 'فيلم', it.year].filter(Boolean).join(' • ');
    el.title = it.name;
    el.onclick = () => (it.type === 'episode' ? openModalPlayer(it) : openDetails(it));
    el.onkeydown = (e) => { if (e.key === 'Enter') el.click(); };
    return el;
  }
  function chanCard(it) {
    const el = document.createElement('div');
    el.className = 'card chan';
    el.tabIndex = 0;
    el.innerHTML = '<span class="lg"></span><div class="t"></div>';
    logoInto($('.lg', el), it);
    $('.t', el).textContent = it.name;
    el.onclick = async () => {
      go('live');
      const fav = state.live.cats.find((c) => c.id === '__fav');
      if (fav) await selectLiveCat(fav);
      playLive(it);
    };
    el.onkeydown = (e) => { if (e.key === 'Enter') el.click(); };
    return el;
  }

  // ---------- نافذة الأقسام ----------
  let catsCb = null;
  let catsList = [];
  let catsCurrent = null;
  function openCats(list, current, cb) {
    catsList = list; catsCurrent = current; catsCb = cb;
    $('#cats-modal').classList.remove('hidden');
    $('#cat-search').value = '';
    renderCatGrid();
    if (matchMedia('(min-width: 761px)').matches) setTimeout(() => $('#cat-search').focus(), 50);
  }
  function renderCatGrid() {
    const q = $('#cat-search').value.trim().toLowerCase();
    const g = $('#cat-grid');
    g.innerHTML = '';
    for (const c of catsList) {
      if (q && !c.name.toLowerCase().includes(q)) continue;
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'cat-item' + (catsCurrent?.id === c.id ? ' active' : '');
      b.innerHTML = `<span></span>${c.count != null ? `<span class="n">${c.count}</span>` : ''}`;
      b.firstChild.textContent = c.name;
      b.title = c.name;
      b.onclick = () => { $('#cats-modal').classList.add('hidden'); catsCb && catsCb(c); };
      g.appendChild(b);
    }
  }
  $('#cat-search').addEventListener('input', renderCatGrid);

  // ---------- التفاصيل ----------
  async function openDetails(it) {
    const modal = $('#details');
    const body = $('#details-body');
    modal.classList.remove('hidden');
    modal.dataset.key = it.key;
    $('.details-sheet', modal).scrollTop = 0;
    renderHero(body, it, {});
    try {
      if (it.type === 'movie') {
        const d = await cached('vod_info:' + it.id, () => xtream({ action: 'get_vod_info', vod_id: it.id }));
        if (modal.dataset.key === it.key) renderHero(body, it, d.info || {});
      } else {
        const d = await cached('series_info:' + it.id, () => xtream({ action: 'get_series_info', series_id: it.id }));
        if (modal.dataset.key !== it.key) return;
        renderHero(body, it, d.info || {});
        renderEpisodes(body, it, d);
      }
    } catch (e) {
      $('.extra', body).insertAdjacentHTML('beforeend', `<p class="meta">تعذر تحميل التفاصيل: ${esc(e.message)}</p>`);
    }
  }
  function renderHero(body, it, info) {
    const poster = info.movie_image || info.cover || it.logo || '';
    const backdrop = (Array.isArray(info.backdrop_path) ? info.backdrop_path[0] : info.backdrop_path) || '';
    const rating = fmtRating(info.rating) || it.rating;
    const yr = year(info.releasedate || info.releaseDate || info.release_date) || it.year;
    const plot = info.plot || info.description || it.plot || '';
    const genre = info.genre || it.genre || '';
    const dur = info.duration || (info.episode_run_time ? info.episode_run_time + ' دقيقة' : '');
    body.innerHTML = `
      <div class="hero"><div class="hero-bg ${backdrop ? '' : 'blur'}"></div>
        <div class="hero-in">
          ${poster ? '<img class="hero-poster" referrerpolicy="no-referrer" alt="" />' : ''}
          <div class="hero-info"><h2></h2>
            <div class="badges"><span class="badge">${it.type === 'series' ? 'مسلسل' : 'فيلم'}</span>
              ${rating ? `<span class="badge lime">★ ${esc(rating)}</span>` : ''}${yr ? `<span class="badge">${esc(yr)}</span>` : ''}${dur ? `<span class="badge">${esc(dur)}</span>` : ''}</div>
            ${plot ? '<p class="plot"></p>' : ''}
            <div class="hero-actions">
              ${it.type === 'movie' ? `<button class="btn lime" data-act="play" type="button">${ICON.play}تشغيل</button>` : ''}
              <button class="btn" data-act="fav" type="button"></button>
            </div>
          </div></div></div>
      <div class="extra">
        ${genre ? `<p class="meta">النوع: <b>${esc(genre)}</b></p>` : ''}
        ${info.cast ? `<p class="meta">التمثيل: <b>${esc(info.cast)}</b></p>` : ''}
        ${info.director ? `<p class="meta">إخراج: <b>${esc(info.director)}</b></p>` : ''}
        <div class="eps-box"></div>
      </div>`;
    $('h2', body).textContent = it.name;
    if (plot) $('.plot', body).textContent = plot;
    const bgUrl = backdrop || poster;
    if (bgUrl) $('.hero-bg', body).style.backgroundImage = `url("${encodeURI(bgUrl).replace(/"/g, '%22')}")`;
    const pi = $('.hero-poster', body);
    if (pi) { pi.onerror = () => pi.remove(); pi.src = poster; }
    const favBtn = $('[data-act=fav]', body);
    const sync = () => (favBtn.textContent = isFav(it) ? '★ في المفضلة' : '☆ أضف للمفضلة');
    sync();
    favBtn.onclick = () => { toggleFav(it); sync(); };
    const pb = $('[data-act=play]', body);
    if (pb) pb.onclick = () => openModalPlayer({ ...it, sub: [yr, genre].filter(Boolean).join(' • ') });
  }
  function renderEpisodes(body, series, data) {
    const box = $('.eps-box', body);
    const eps = data.episodes || {};
    const seasons = Object.keys(eps).sort((a, b) => Number(a) - Number(b));
    if (!seasons.length) { box.innerHTML = '<p class="meta">مفيش حلقات متاحة</p>'; return; }
    box.innerHTML = '<div class="seasons"></div><div class="eps"></div>';
    const tabs = $('.seasons', box);
    const list = $('.eps', box);
    const show = (s) => {
      $$('.chip', tabs).forEach((b) => b.classList.toggle('active', b.dataset.s === s));
      list.innerHTML = '';
      for (const ep of eps[s]) {
        const item = {
          key: 'ep:' + ep.id, type: 'episode', id: ep.id, ext: ep.container_extension,
          name: `${series.name} — م${s} ح${ep.episode_num}`, logo: ep.info?.movie_image || series.logo,
          sub: `الموسم ${s} • الحلقة ${ep.episode_num}${ep.title ? ' — ' + ep.title : ''}`,
        };
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'ep';
        b.innerHTML = `<div class="ep-thumb">${esc(ep.episode_num)}</div><div class="ep-b"><div class="ep-t"></div><div class="ep-p"></div><div class="ep-d"></div></div>`;
        if (ep.info?.movie_image) $('.ep-thumb', b).appendChild(img(ep.info.movie_image));
        $('.ep-t', b).textContent = `${ep.episode_num}. ${ep.title || 'الحلقة ' + ep.episode_num}`;
        $('.ep-p', b).textContent = ep.info?.plot || '';
        $('.ep-d', b).textContent = ep.info?.duration || '';
        b.onclick = () => openModalPlayer(item);
        list.appendChild(b);
      }
    };
    for (const s of seasons) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'chip';
      b.dataset.s = s;
      b.textContent = `الموسم ${s}`;
      b.onclick = () => show(s);
      tabs.appendChild(b);
    }
    show(seasons[0]);
  }

  // ---------- مشغل الأفلام ----------
  function openModalPlayer(it) {
    state.playerMode = 'modal';
    $('#player-modal').classList.remove('hidden');
    $('#pm-title').textContent = it.name;
    $('#pm-sub').textContent = it.sub || (it.type === 'movie' ? 'فيلم' : '');
    attachPlayer($('#modal-slot'));
    play(it);
  }
  function closeModalPlayer() {
    stopPlayer();
    state.current = null;
    $('#player-modal').classList.add('hidden');
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    if (state.view === 'live' && state.live.current) playLive(state.live.current);
    else pwrap.classList.remove('attached');
    if (state.view === 'favs') selectGridCat(state.grid.cat);
  }

  // ---------- النوافذ والكيبورد ----------
  function closeModal(m) {
    if (m.id === 'player-modal') closeModalPlayer();
    else m.classList.add('hidden');
  }
  $$('.modal').forEach((m) => {
    m.addEventListener('click', (e) => { if (e.target === m || e.target.closest('[data-close]')) closeModal(m); });
  });

  // زرار الرجوع في الأندرويد بينادي الدالة دي
  window.__back = () => {
    if (document.fullscreenElement || document.webkitFullscreenElement) { (document.exitFullscreen || document.webkitExitFullscreen).call(document); return true; }
    const open = $$('.modal:not(.hidden)');
    if (open.length) { closeModal(open[open.length - 1]); return true; }
    if (state.source && state.view && state.view !== 'live') { go('live'); return true; }
    return false;
  };

  document.addEventListener('keydown', (e) => {
    const typing = /INPUT|TEXTAREA/.test(document.activeElement?.tagName);
    if (e.key === 'Escape') { if (!document.fullscreenElement) window.__back(); return; }
    if (typing || !state.source) return;
    const modalOpen = $$('.modal:not(.hidden)').length > 0;
    if (state.view === 'live' && !modalOpen) {
      if (e.key === 'ArrowUp' || e.key === 'PageUp') { e.preventDefault(); zap(-1); }
      if (e.key === 'ArrowDown' || e.key === 'PageDown') { e.preventDefault(); zap(1); }
    }
    if (e.key.toLowerCase() === 'f' && pwrap.classList.contains('attached')) toggleFullscreen();
    if (e.key === ' ' && pwrap.classList.contains('attached')) { e.preventDefault(); video.paused ? video.play() : video.pause(); }
    if (e.key === '/' && !modalOpen) { e.preventDefault(); (state.view === 'live' ? $('#ch-search') : $('#grid-search')).focus(); }
  });

  // ---------- الساعة والتحديث الدوري ----------
  function tick() {
    $('#clock').textContent = hhmm(Date.now());
    if (state.view === 'live' && state.source) {
      updateNow();
      for (const row of chList.rows.values()) {
        const it = chList.items.find((x) => x.key === row.dataset.key);
        if (it) fillRowEpg(row, it);
      }
      renderEpgGrid();
    }
  }
  setInterval(tick, 30000);

  // =====================================================================
  //                               الحساب
  // =====================================================================
  function renderAccount() {
    const u = state.userInfo;
    const body = $('#account-body');
    if (state.source.type === 'm3u') {
      const total = [...state.m3u.groups.values()].reduce((a, g) => a + g.length, 0);
      body.innerHTML = `<div class="acc-row"><span class="muted">النوع</span><b>قائمة M3U</b></div><div class="acc-row"><span class="muted">عدد القنوات</span><b>${total}</b></div>`;
      return;
    }
    let exp = 'غير محدود';
    if (u.exp_date && Number(u.exp_date) > 0) {
      const ms = Number(u.exp_date) * 1000;
      const days = Math.max(0, Math.ceil((ms - Date.now()) / 86400000));
      exp = `${new Date(ms).toLocaleDateString('ar-EG')} (باقي ${days} يوم)`;
    }
    body.innerHTML = `
      <div class="acc-row"><span class="muted">المستخدم</span><b>${esc(u.username)}</b></div>
      <div class="acc-row"><span class="muted">الحالة</span><b class="ok">${u.status === 'Active' ? 'نشط' : esc(u.status || '')}</b></div>
      <div class="acc-row"><span class="muted">ينتهي</span><b>${esc(exp)}</b></div>
      <div class="acc-row"><span class="muted">الأجهزة</span><b>${esc(u.active_cons ?? 0)} من ${esc(u.max_connections ?? '?')}</b></div>
      <div class="acc-row"><span class="muted">السيرفر</span><b dir="ltr">${esc(state.source.server.replace(/^https?:\/\//, ''))}</b></div>`;
  }
  $('#account-btn').onclick = () => { renderAccount(); $('#account-modal').classList.remove('hidden'); };

  // =====================================================================
  //                                الدخول
  // =====================================================================
  $$('.seg-btn').forEach((t) => {
    t.onclick = () => {
      $$('.seg-btn').forEach((x) => x.classList.toggle('active', x === t));
      $('#form-xtream').classList.toggle('hidden', t.dataset.tab !== 'xtream');
      $('#form-m3u').classList.toggle('hidden', t.dataset.tab !== 'm3u');
      $('#login-error').textContent = '';
    };
  });

  async function login(source) {
    const err = $('#login-error');
    err.textContent = '';
    const btns = $$('.login .btn.lime');
    btns.forEach((b) => { b.disabled = true; b.classList.add('loading'); });
    state.source = source;
    state.cache = new Map();
    try {
      if (source.type === 'xtream') {
        const data = await xtream();
        const u = data.user_info;
        if (!u || Number(u.auth) === 0) throw new Error('اسم المستخدم أو كلمة السر غلط');
        if (u.status && u.status !== 'Active') throw new Error('الاشتراك حالته: ' + u.status);
        state.userInfo = u;
      } else {
        const r = await fetch('/api/m3u?url=' + encodeURIComponent(source.url));
        const text = await r.text();
        if (!r.ok) { let m = 'خطأ ' + r.status; try { m = JSON.parse(text).error || m; } catch {} throw new Error(m); }
        state.m3u = parseM3u(text);
        if (!state.m3u.groups.size) throw new Error('القائمة فاضية أو مش بصيغة M3U');
      }
      $('#remember').checked ? store.set('iptv.source', source) : store.del('iptv.source');
      $('#login').classList.add('hidden');
      $('#app').classList.remove('hidden');
      state.view = null;
      state.live = { cats: [], cat: null, items: [], filtered: [], current: null };
      renderNav();
      tick();
      go('live');
    } catch (e) {
      err.textContent = e.message === 'Failed to fetch' ? 'تعذر الاتصال — اتأكد من النت وعنوان السيرفر' : e.message;
      state.source = null;
    } finally {
      btns.forEach((b) => { b.disabled = false; b.classList.remove('loading'); });
    }
  }

  $('#form-xtream').addEventListener('submit', (e) => {
    e.preventDefault();
    const f = new FormData(e.target);
    let server = String(f.get('server')).trim().replace(/\/+$/, '');
    if (!/^https?:\/\//i.test(server)) server = 'http://' + server;
    login({ type: 'xtream', server, username: String(f.get('username')).trim(), password: String(f.get('password')) });
  });
  $('#form-m3u').addEventListener('submit', (e) => {
    e.preventDefault();
    const url = String(new FormData(e.target).get('url')).trim();
    try {
      const u = new URL(url);
      const user = u.searchParams.get('username');
      const pass = u.searchParams.get('password');
      if (u.pathname.endsWith('/get.php') && user && pass) return login({ type: 'xtream', server: u.origin, username: user, password: pass });
    } catch {}
    login({ type: 'm3u', url });
  });

  $('#logout').onclick = () => {
    stopPlayer();
    pwrap.classList.remove('attached');
    $$('.modal').forEach((m) => m.classList.add('hidden'));
    store.del('iptv.source');
    state.source = null;
    state.current = null;
    state.view = null;
    epg.cache.clear();
    chList.setItems([]);
    $('#app').classList.add('hidden');
    $('#login').classList.remove('hidden');
  };

  const saved = store.get('iptv.source', null);
  if (saved) {
    if (saved.type === 'xtream') {
      const f = $('#form-xtream');
      f.server.value = saved.server; f.username.value = saved.username; f.password.value = saved.password;
    } else {
      $('.seg-btn[data-tab="m3u"]').click();
      $('#form-m3u').url.value = saved.url;
    }
    login(saved);
  }
})();
