/* Stream — مشغل IPTV */
(() => {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const PAGE = 60;

  const ICONS = {
    live: '<svg viewBox="0 0 24 24"><rect x="3" y="6" width="18" height="13" rx="2.5"/><path d="m8 2 4 4 4-4"/></svg>',
    movie: '<svg viewBox="0 0 24 24"><rect x="3" y="3" width="18" height="18" rx="2.5"/><path d="M7 3v18M17 3v18M3 8h4M3 16h4M17 8h4M17 16h4M3 12h18"/></svg>',
    series: '<svg viewBox="0 0 24 24"><rect x="2.5" y="7" width="19" height="13" rx="2.5"/><path d="M7 3h10M9.5 11v5l4.5-2.5z"/></svg>',
    m3u: '<svg viewBox="0 0 24 24"><path d="M4 6h16M4 12h16M4 18h10"/><circle cx="19" cy="18" r="2"/></svg>',
    favs: '<svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/></svg>',
    play: '<svg viewBox="0 0 24 24" class="play"><path d="M7 4.5v15l12.5-7.5z"/></svg>',
    star: '<svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/></svg>',
    search: '<svg viewBox="0 0 24 24"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/></svg>',
    alert: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7.5v5M12 16.5h.01"/></svg>',
  };

  const state = {
    source: null,
    userInfo: null,
    section: null,
    categories: [],
    category: null,
    items: [],
    filtered: [],
    shown: 0,
    current: null,
    playlist: [], // قايمة القنوات اللي في المشغل (للتنقل)
    m3u: null,
    cache: new Map(),
  };

  // ---------- تخزين ----------
  const store = {
    get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
    del(k) { try { localStorage.removeItem(k); } catch {} },
  };
  const acctKey = () => state.source.type === 'xtream'
    ? state.source.server + '|' + state.source.username : state.source.url;
  const getFavs = () => store.get('iptv.favs.' + acctKey(), []);
  const getRecent = () => store.get('iptv.recent.' + acctKey(), []);
  const isFav = (it) => !!it && getFavs().some((f) => f.key === it.key);
  const slim = (it) => { const { _el, ...rest } = it; return rest; };
  function toggleFav(it) {
    const on = isFav(it);
    store.set('iptv.favs.' + acctKey(), on ? getFavs().filter((f) => f.key !== it.key) : [slim(it), ...getFavs()]);
    toast(on ? 'اتشال من المفضلة' : '⭐ اتضاف للمفضلة');
    return !on;
  }
  function addRecent(it) {
    const list = [slim(it), ...getRecent().filter((r) => r.key !== it.key)].slice(0, 40);
    store.set('iptv.recent.' + acctKey(), list);
  }

  // ---------- أدوات ----------
  function toast(text) {
    const el = document.createElement('div');
    el.className = 'toast';
    el.textContent = text;
    $('#toasts').appendChild(el);
    setTimeout(() => { el.classList.add('out'); setTimeout(() => el.remove(), 300); }, 2200);
  }
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  function hue(str) { let h = 0; for (const c of String(str)) h = (h * 31 + c.charCodeAt(0)) % 360; return h; }
  function fallbackArt(name) {
    const h = hue(name);
    const d = document.createElement('div');
    d.className = 'fallback-art';
    d.style.background = `linear-gradient(145deg, hsl(${h} 70% 38%), hsl(${(h + 60) % 360} 70% 22%))`;
    d.textContent = (String(name).trim()[0] || '?').toUpperCase();
    return d;
  }
  function img(src, cls, onFail) {
    const i = new Image();
    i.loading = 'lazy';
    i.decoding = 'async';
    i.referrerPolicy = 'no-referrer';
    if (cls) i.className = cls;
    i.onerror = () => { i.remove(); onFail && onFail(); };
    i.src = src;
    return i;
  }
  const fmtRating = (r) => { const n = parseFloat(r); return n > 0 ? (n > 10 ? n / 10 : n).toFixed(1) : ''; };
  const year = (s) => (String(s || '').match(/(19|20)\d{2}/) || [''])[0];

  // ---------- API ----------
  async function xtream(params = {}) {
    const s = state.source;
    const q = new URLSearchParams({ server: s.server, username: s.username, password: s.password, ...params });
    const r = await fetch('/api/xtream?' + q);
    const text = await r.text();
    let data;
    try { data = JSON.parse(text); } catch { throw new Error('رد غير متوقع من السيرفر'); }
    if (!r.ok) throw new Error(data.error || 'خطأ ' + r.status);
    return data;
  }
  async function cached(key, fn) {
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
    for (const raw of text.split(/\r?\n/)) {
      const line = raw.trim();
      if (!line) continue;
      if (line.startsWith('#EXTINF')) {
        const attr = (n) => (line.match(new RegExp(n + '="([^"]*)"', 'i')) || [])[1] || '';
        const comma = line.lastIndexOf(',');
        meta = {
          name: (comma >= 0 ? line.slice(comma + 1) : '').trim() || attr('tvg-name') || 'بدون اسم',
          logo: attr('tvg-logo'),
          group: attr('group-title') || 'أخرى',
        };
      } else if (!line.startsWith('#')) {
        const m = meta || { name: line, logo: '', group: 'أخرى' };
        if (!groups.has(m.group)) groups.set(m.group, []);
        groups.get(m.group).push({ key: 'm3u:' + line, type: 'stream', name: m.name, logo: m.logo, url: line, group: m.group });
        meta = null;
      }
    }
    return { groups };
  }

  // ---------- الروابط ----------
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
  const isLiveType = (t) => t === 'live' || t === 'stream';

  // =====================================================================
  //                               المشغل
  // =====================================================================
  const video = $('#video');
  let hls = null;
  let mts = null;

  function stopPlayer() {
    if (hls) { hls.destroy(); hls = null; }
    if (mts) { try { mts.destroy(); } catch {} mts = null; }
    video.removeAttribute('src');
    video.load();
  }
  function pmsg(text, error) {
    const el = $('#player-msg');
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

  function openPlayer(it, playlist) {
    state.playlist = playlist || (isLiveType(it.type) ? state.filtered.filter((x) => isLiveType(x.type)) : []);
    $('#player').classList.remove('hidden');
    const live = isLiveType(it.type);
    $('#zap').classList.toggle('hidden', !live || state.playlist.length < 2);
    $('#prev-btn').classList.toggle('hidden', !live || state.playlist.length < 2);
    $('#next-btn').classList.toggle('hidden', !live || state.playlist.length < 2);
    $('#zap-title').textContent = state.category ? state.category.name : 'القنوات';
    $('#zap-search').value = '';
    play(it);
    renderZap();
  }

  function play(it, opts = {}) {
    const url = opts.url || streamUrl(it);
    if (!url) return;
    state.current = it;
    stopPlayer();
    pmsg('جاري التحميل...');
    addRecent(it);

    $('#np-title').textContent = it.name;
    const sub = $('#np-sub');
    sub.innerHTML = isLiveType(it.type)
      ? `<span class="badge-live">مباشر</span><span>${esc(it.group || state.category?.name || '')}</span>`
      : esc(it.sub || (it.type === 'movie' ? 'فيلم' : 'حلقة'));
    const logo = $('#np-logo');
    logo.innerHTML = '';
    if (it.logo) logo.appendChild(img(it.logo, isLiveType(it.type) ? '' : 'cover', () => (logo.innerHTML = '')));
    updateFavBtn();
    $$('#zap-list li').forEach((li) => li.classList.toggle('active', li.dataset.key === it.key));
    $('#zap-list li.active')?.scrollIntoView({ block: 'nearest' });

    const src = '/proxy?url=' + encodeURIComponent(url);
    const kind = kindOf(url);
    const fail = (why) => pmsg('الفيديو ده مش راضي يشتغل في المتصفح' + (why ? `\n(${why})` : '') +
      '\nجرّب قناة تانية، أو انسخ الرابط وشغّله على VLC.', true);

    if (kind === 'hls' && window.Hls && Hls.isSupported()) {
      hls = new Hls({ enableWorker: true });
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
        { type: 'mpegts', isLive: isLiveType(it.type), url: new URL(src, location.href).href },
        { enableStashBuffer: true, liveBufferLatencyChasing: false }
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

  function closePlayer() {
    stopPlayer();
    $('#player').classList.add('hidden');
    if (document.fullscreenElement) document.exitFullscreen().catch(() => {});
    renderGridFavMarks();
  }

  video.addEventListener('playing', () => pmsg(''));
  video.addEventListener('waiting', () => { if (!$('#player').classList.contains('hidden')) pmsg('جاري التحميل...'); });
  video.addEventListener('error', () => {
    if (!hls && !mts && state.current) pmsg('صيغة الفيديو مش مدعومة في المتصفح.\nانسخ الرابط وشغّله على VLC.', true);
  });

  function zapBy(step) {
    const list = state.playlist;
    if (list.length < 2) return;
    const i = list.findIndex((x) => x.key === state.current?.key);
    play(list[(i + step + list.length) % list.length]);
  }
  $('#prev-btn').onclick = () => zapBy(-1);
  $('#next-btn').onclick = () => zapBy(1);

  function updateFavBtn() {
    const on = isFav(state.current);
    $('#fav-btn').classList.toggle('on', on);
  }
  $('#fav-btn').onclick = () => { toggleFav(state.current); updateFavBtn(); };
  $('#copy-btn').onclick = async () => {
    const url = streamUrl(state.current);
    try { await navigator.clipboard.writeText(url); toast('✓ الرابط اتنسخ — افتحه في VLC'); }
    catch { prompt('انسخ الرابط:', url); }
  };
  $('#fs-btn').onclick = () => {
    const el = $('.video-wrap');
    document.fullscreenElement ? document.exitFullscreen() : (el.requestFullscreen || el.webkitRequestFullscreen)?.call(el);
  };

  function renderZap() {
    const q = $('#zap-search').value.trim().toLowerCase();
    const ul = $('#zap-list');
    ul.innerHTML = '';
    const frag = document.createDocumentFragment();
    let n = 0;
    state.playlist.forEach((it, idx) => {
      if (q && !it.name.toLowerCase().includes(q)) return;
      if (++n > 400) return;
      const li = document.createElement('li');
      li.dataset.key = it.key;
      li.innerHTML = `<span class="i">${idx + 1}</span><span class="lg"></span><span class="nm"></span><span class="eq"><i></i><i></i><i></i></span>`;
      $('.nm', li).textContent = it.name;
      if (it.logo) $('.lg', li).appendChild(img(it.logo));
      if (state.current?.key === it.key) li.classList.add('active');
      li.onclick = () => play(it);
      frag.appendChild(li);
    });
    ul.appendChild(frag);
    $('#zap-list li.active')?.scrollIntoView({ block: 'center' });
  }
  $('#zap-search').addEventListener('input', renderZap);

  // =====================================================================
  //                           الأقسام والتصنيفات
  // =====================================================================
  const SECTION_NAMES = { live: 'قنوات مباشرة', movie: 'أفلام', series: 'مسلسلات', m3u: 'القنوات', favs: 'مكتبتي' };
  function sectionsFor() {
    return state.source.type === 'xtream'
      ? [['live', 'قنوات'], ['movie', 'أفلام'], ['series', 'مسلسلات'], ['favs', 'مكتبتي']]
      : [['m3u', 'القنوات'], ['favs', 'مكتبتي']];
  }
  function renderSections() {
    const nav = $('#sections');
    nav.innerHTML = '';
    for (const [id, label] of sectionsFor()) {
      const b = document.createElement('button');
      b.className = 'nav-btn';
      b.dataset.id = id;
      b.innerHTML = ICONS[id] + `<span>${label}</span>`;
      b.onclick = () => selectSection(id);
      nav.appendChild(b);
    }
  }

  async function selectSection(id) {
    state.section = id;
    $$('#sections .nav-btn').forEach((b) => b.classList.toggle('active', b.dataset.id === id));
    $('#section-title').textContent = SECTION_NAMES[id];
    $('#item-search').value = '';
    $('#content').scrollTop = 0;
    setCategories([]);
    showSkeleton(id);
    try {
      let cats;
      if (id === 'favs') {
        cats = [
          { id: 'favs', name: '⭐ المفضلة', count: getFavs().length },
          { id: 'recent', name: '🕘 شوهد مؤخرًا', count: getRecent().length },
        ];
      } else if (id === 'm3u') {
        let total = 0;
        cats = [...state.m3u.groups].map(([name, list]) => { total += list.length; return { id: name, name, count: list.length }; });
        cats.unshift({ id: '__all', name: 'الكل', count: total });
      } else {
        const action = { live: 'get_live_categories', movie: 'get_vod_categories', series: 'get_series_categories' }[id];
        const data = await cached(action, () => xtream({ action }));
        cats = [{ id: '__all', name: 'الكل' }, ...(Array.isArray(data) ? data : []).map((c) => ({ id: c.category_id, name: c.category_name }))];
      }
      if (state.section !== id) return;
      setCategories(cats);
      selectCategory(id === 'favs' || cats.length < 2 ? cats[0] : cats[1]);
    } catch (e) {
      showEmpty('حصل خطأ', e.message, true);
    }
  }

  function setCategories(cats) {
    state.categories = cats;
    renderChips();
  }
  function renderChips() {
    const box = $('#chips');
    box.innerHTML = '';
    for (const c of state.categories.slice(0, 40)) {
      const b = document.createElement('button');
      b.className = 'chip' + (state.category?.id === c.id ? ' active' : '');
      b.innerHTML = `<span></span>${c.count != null ? `<span class="n">${c.count}</span>` : ''}`;
      b.firstChild.textContent = c.name;
      b.onclick = () => selectCategory(c);
      box.appendChild(b);
    }
    $('#all-cats').classList.toggle('hidden', state.categories.length < 8);
    $('#chips .chip.active')?.scrollIntoView({ inline: 'nearest', block: 'nearest' });
  }

  function openCats() {
    $('#cats-modal').classList.remove('hidden');
    $('#cat-search').value = '';
    renderCatGrid();
    setTimeout(() => $('#cat-search').focus(), 50);
  }
  function renderCatGrid() {
    const q = $('#cat-search').value.trim().toLowerCase();
    const g = $('#cat-grid');
    g.innerHTML = '';
    for (const c of state.categories) {
      if (q && !c.name.toLowerCase().includes(q)) continue;
      const b = document.createElement('button');
      b.className = 'cat-item' + (state.category?.id === c.id ? ' active' : '');
      b.innerHTML = `<span></span>${c.count != null ? `<span class="n">${c.count}</span>` : ''}`;
      b.firstChild.textContent = c.name;
      b.title = c.name;
      b.onclick = () => {
        $('#cats-modal').classList.add('hidden');
        if (!state.categories.slice(0, 40).includes(c)) {
          state.categories = [c, ...state.categories.filter((x) => x !== c)];
        }
        selectCategory(c);
      };
      g.appendChild(b);
    }
  }
  $('#all-cats').onclick = openCats;
  $('#cat-search').addEventListener('input', renderCatGrid);

  async function selectCategory(cat) {
    state.category = cat;
    renderChips();
    $('#item-search').value = '';
    const sec = state.section;
    showSkeleton(sec);
    try {
      let items;
      if (sec === 'favs') items = cat.id === 'recent' ? getRecent() : getFavs();
      else if (sec === 'm3u') items = cat.id === '__all' ? [...state.m3u.groups.values()].flat() : state.m3u.groups.get(cat.id) || [];
      else {
        const action = { live: 'get_live_streams', movie: 'get_vod_streams', series: 'get_series' }[sec];
        const params = { action };
        if (cat.id !== '__all') params.category_id = cat.id;
        const data = await cached(action + ':' + cat.id, () => xtream(params));
        items = (Array.isArray(data) ? data : []).map((x) => normalize(sec, x));
      }
      if (state.section !== sec || state.category !== cat) return;
      state.items = items;
      applyFilter();
    } catch (e) {
      showEmpty('حصل خطأ', e.message, true);
    }
  }

  function normalize(sec, x) {
    if (sec === 'live') return { key: 'live:' + x.stream_id, type: 'live', id: x.stream_id, name: x.name || '', logo: x.stream_icon };
    if (sec === 'movie') {
      return {
        key: 'movie:' + x.stream_id, type: 'movie', id: x.stream_id, name: x.name || '', logo: x.stream_icon,
        ext: x.container_extension, rating: fmtRating(x.rating_5based ? x.rating_5based * 2 : x.rating),
        year: year(x.year || x.releaseDate || x.name),
      };
    }
    return {
      key: 'series:' + x.series_id, type: 'series', id: x.series_id, name: x.name || '', logo: x.cover,
      rating: fmtRating(x.rating_5based ? x.rating_5based * 2 : x.rating), year: year(x.releaseDate || x.year),
      plot: x.plot, genre: x.genre,
    };
  }

  // =====================================================================
  //                               الشبكة
  // =====================================================================
  const grid = $('#grid');
  function gridMode(items) {
    if (state.section === 'live' || state.section === 'm3u') return 'live';
    if (state.section === 'favs') return items.length && items.every((i) => isLiveType(i.type)) ? 'live' : 'poster';
    return 'poster';
  }
  function showSkeleton(sec) {
    const mode = sec === 'live' || sec === 'm3u' ? 'live' : 'poster';
    grid.className = 'grid ' + mode;
    grid.innerHTML = '<div class="skeleton"></div>'.repeat(18);
    $('#count').textContent = '';
  }
  function showEmpty(title, sub, error) {
    grid.className = 'grid';
    grid.innerHTML = `<div class="empty"><div class="big-ico">${error ? ICONS.alert : ICONS.search}</div><h4>${esc(title)}</h4><div>${esc(sub || '')}</div></div>`;
  }

  function applyFilter() {
    const q = $('#item-search').value.trim().toLowerCase();
    state.filtered = q ? state.items.filter((i) => (i.name || '').toLowerCase().includes(q)) : state.items;
    $('#count').textContent = state.items.length ? (q ? `${state.filtered.length} من ${state.items.length}` : `${state.items.length}`) : '';
    state.shown = 0;
    grid.innerHTML = '';
    grid.className = 'grid ' + gridMode(state.filtered);
    if (!state.filtered.length) {
      if (q) return showEmpty('مفيش نتايج', `مالقيناش حاجة باسم "${q}"`);
      if (state.section === 'favs') {
        return showEmpty(state.category?.id === 'recent' ? 'لسه ماتفرجتش على حاجة' : 'المفضلة فاضية',
          'دوس على ⭐ في المشغل أو صفحة الفيلم عشان تضيف هنا');
      }
      return showEmpty('مفيش حاجة هنا', 'جرّب قسم تاني');
    }
    renderMore();
  }
  let searchT;
  $('#item-search').addEventListener('input', () => { clearTimeout(searchT); searchT = setTimeout(applyFilter, 150); });

  function renderMore() {
    const slice = state.filtered.slice(state.shown, state.shown + PAGE);
    const frag = document.createDocumentFragment();
    const favKeys = new Set(getFavs().map((f) => f.key));
    slice.forEach((it, i) => {
      const el = isLiveType(it.type) ? liveCard(it) : posterCard(it);
      el.style.animationDelay = Math.min(i * 18, 400) + 'ms';
      if (favKeys.has(it.key)) el.appendChild(favDot());
      frag.appendChild(el);
    });
    grid.appendChild(frag);
    state.shown += slice.length;
  }
  function favDot() {
    const d = document.createElement('span');
    d.className = 'fav-dot';
    d.innerHTML = ICONS.star;
    return d;
  }
  function renderGridFavMarks() {
    const favKeys = new Set(getFavs().map((f) => f.key));
    $$('#grid .card').forEach((c) => {
      const has = favKeys.has(c.dataset.key);
      const dot = $('.fav-dot', c);
      if (has && !dot) c.appendChild(favDot());
      if (!has && dot) dot.remove();
    });
    if (state.section === 'favs') selectCategory(state.category);
  }

  function liveCard(it) {
    const el = document.createElement('div');
    el.className = 'card live';
    el.tabIndex = 0;
    el.dataset.key = it.key;
    el.innerHTML = `<div class="logo"></div><div class="name"></div><span class="badge-live">LIVE</span>
      <div class="play-hover"><div class="play-circle">${ICONS.play}</div></div>`;
    $('.name', el).textContent = it.name;
    el.title = it.name;
    const logo = $('.logo', el);
    const fb = () => { const f = fallbackArt(it.name); f.style.position = 'static'; f.style.width = '100%'; f.style.height = '100%'; f.style.fontSize = '34px'; logo.appendChild(f); };
    it.logo ? logo.appendChild(img(it.logo, '', fb)) : fb();
    el.onclick = () => openPlayer(it);
    el.onkeydown = (e) => { if (e.key === 'Enter') el.click(); };
    return el;
  }

  function posterCard(it) {
    const el = document.createElement('div');
    el.className = 'card poster';
    el.tabIndex = 0;
    el.dataset.key = it.key;
    el.innerHTML = `<div class="art"><div class="shade"><div class="play-circle">${ICONS.play}</div></div></div><div class="t"></div><div class="s"></div>`;
    const art = $('.art', el);
    const fb = () => art.prepend(fallbackArt(it.name));
    it.logo ? art.prepend(img(it.logo, '', fb)) : fb();
    if (it.rating) art.insertAdjacentHTML('beforeend', `<span class="rate">★ ${esc(it.rating)}</span>`);
    $('.t', el).textContent = it.name;
    $('.s', el).textContent = [it.type === 'series' ? 'مسلسل' : it.type === 'episode' ? 'حلقة' : 'فيلم', it.year].filter(Boolean).join(' • ');
    el.title = it.name;
    el.onclick = () => (it.type === 'episode' ? openPlayer(it, []) : openDetails(it));
    el.onkeydown = (e) => { if (e.key === 'Enter') el.click(); };
    return el;
  }

  new IntersectionObserver((entries) => {
    if (entries[0].isIntersecting && state.shown && state.shown < state.filtered.length) renderMore();
  }, { root: $('#content'), rootMargin: '800px' }).observe($('#sentinel'));

  // =====================================================================
  //                         صفحة التفاصيل
  // =====================================================================
  async function openDetails(it) {
    const modal = $('#details');
    const body = $('#details-body');
    modal.classList.remove('hidden');
    $('.details-sheet', modal).scrollTop = 0;
    renderHero(body, it, {});
    try {
      if (it.type === 'movie') {
        const data = await cached('vod_info:' + it.id, () => xtream({ action: 'get_vod_info', vod_id: it.id }));
        if (modal.dataset.key !== it.key) return;
        renderHero(body, it, data.info || {});
      } else {
        const data = await cached('series_info:' + it.id, () => xtream({ action: 'get_series_info', series_id: it.id }));
        if (modal.dataset.key !== it.key) return;
        renderHero(body, it, data.info || {});
        renderEpisodes(body, it, data);
      }
    } catch (e) {
      $('.details-extra', body).innerHTML = `<p class="meta-line">${esc('تعذر تحميل التفاصيل: ' + e.message)}</p>`;
    }
  }

  function renderHero(body, it, info) {
    $('#details').dataset.key = it.key;
    const poster = info.movie_image || info.cover || it.logo || '';
    const backdrop = (Array.isArray(info.backdrop_path) ? info.backdrop_path[0] : info.backdrop_path) || '';
    const rating = fmtRating(info.rating) || it.rating;
    const yr = year(info.releasedate || info.releaseDate || info.release_date) || it.year;
    const plot = info.plot || info.description || it.plot || '';
    const genre = info.genre || it.genre || '';
    const dur = info.duration || (info.episode_run_time ? info.episode_run_time + ' دقيقة' : '');

    body.innerHTML = `
      <div class="hero">
        <div class="hero-bg ${backdrop ? '' : 'blur'}"></div>
        <div class="hero-inner">
          ${poster ? '<img class="hero-poster" referrerpolicy="no-referrer" />' : ''}
          <div class="hero-info">
            <h2></h2>
            <div class="badges">
              <span class="badge">${it.type === 'series' ? 'مسلسل' : 'فيلم'}</span>
              ${rating ? `<span class="badge gold">★ ${esc(rating)}</span>` : ''}
              ${yr ? `<span class="badge">${esc(yr)}</span>` : ''}
              ${dur ? `<span class="badge">${esc(dur)}</span>` : ''}
            </div>
            ${plot ? '<p class="plot"></p>' : ''}
            <div class="hero-actions">
              ${it.type === 'movie' ? `<button class="btn white" data-act="play">${ICONS.play}<span>تشغيل</span></button>` : ''}
              <button class="btn" data-act="fav">${ICONS.star}<span></span></button>
            </div>
          </div>
        </div>
      </div>
      <div class="details-extra">
        ${genre ? `<p class="meta-line">النوع: <b>${esc(genre)}</b></p>` : ''}
        ${info.cast ? `<p class="meta-line">التمثيل: <b>${esc(info.cast)}</b></p>` : ''}
        ${info.director ? `<p class="meta-line">إخراج: <b>${esc(info.director)}</b></p>` : ''}
        <div class="eps-box"></div>
      </div>`;
    $('h2', body).textContent = it.name;
    if (plot) $('.plot', body).textContent = plot;
    const bg = $('.hero-bg', body);
    const bgUrl = backdrop || poster;
    if (bgUrl) bg.style.backgroundImage = `url("${encodeURI(bgUrl).replace(/"/g, '%22')}")`;
    const pi = $('.hero-poster', body);
    if (pi) { pi.onerror = () => pi.remove(); pi.src = poster; }

    const favBtn = $('[data-act=fav]', body);
    const syncFav = () => ($('span', favBtn).textContent = isFav(it) ? 'في المفضلة' : 'أضف للمفضلة');
    syncFav();
    favBtn.onclick = () => { toggleFav(it); syncFav(); };
    const playBtn = $('[data-act=play]', body);
    if (playBtn) playBtn.onclick = () => openPlayer({ ...it, sub: [yr, genre].filter(Boolean).join(' • ') || 'فيلم' }, []);
  }

  function renderEpisodes(body, series, data) {
    const box = $('.eps-box', body);
    const eps = data.episodes || {};
    const seasons = Object.keys(eps).sort((a, b) => Number(a) - Number(b));
    if (!seasons.length) { box.innerHTML = '<p class="meta-line">مفيش حلقات متاحة</p>'; return; }
    box.innerHTML = '<div class="seasons"></div><div class="ep-list"></div>';
    const tabs = $('.seasons', box);
    const list = $('.ep-list', box);
    const show = (s) => {
      $$('.chip', tabs).forEach((b) => b.classList.toggle('active', b.dataset.s === s));
      list.innerHTML = '';
      const all = eps[s].map((ep) => ({
        key: 'ep:' + ep.id, type: 'episode', id: ep.id, ext: ep.container_extension,
        name: `${series.name} — م${s} ح${ep.episode_num}`, logo: ep.info?.movie_image || series.logo,
        sub: `الموسم ${s} • الحلقة ${ep.episode_num}${ep.title ? ' — ' + ep.title : ''}`,
      }));
      eps[s].forEach((ep, i) => {
        const b = document.createElement('button');
        b.className = 'ep';
        b.style.animation = `fadeUp .4s var(--ease) ${Math.min(i * 30, 400)}ms both`;
        b.innerHTML = `<div class="ep-thumb"><span class="num">${esc(ep.episode_num)}</span><div class="play-circle">${ICONS.play}</div></div>
          <div class="ep-body"><div class="ep-title"></div><div class="ep-plot"></div><div class="ep-dur"></div></div>`;
        const thumb = ep.info?.movie_image;
        if (thumb) $('.ep-thumb', b).prepend(img(thumb));
        $('.ep-title', b).textContent = `${ep.episode_num}. ${ep.title || 'الحلقة ' + ep.episode_num}`;
        $('.ep-plot', b).textContent = ep.info?.plot || '';
        $('.ep-dur', b).textContent = ep.info?.duration || '';
        b.onclick = () => openPlayer(all[i], []);
        list.appendChild(b);
      });
    };
    for (const s of seasons) {
      const b = document.createElement('button');
      b.className = 'chip';
      b.dataset.s = s;
      b.textContent = `الموسم ${s}`;
      b.onclick = () => show(s);
      tabs.appendChild(b);
    }
    show(seasons[0]);
  }

  // ---------- قفل النوافذ ----------
  $$('.modal').forEach((m) => {
    m.addEventListener('click', (e) => {
      if (e.target === m || e.target.closest('[data-close]')) {
        m.id === 'player' ? closePlayer() : m.classList.add('hidden');
      }
    });
  });

  document.addEventListener('keydown', (e) => {
    const playerOpen = !$('#player').classList.contains('hidden');
    const typing = /INPUT|TEXTAREA/.test(document.activeElement?.tagName);
    if (e.key === 'Escape') {
      if (document.fullscreenElement) return;
      if (playerOpen) return closePlayer();
      const open = $$('.modal:not(.hidden)').pop();
      if (open) open.classList.add('hidden');
      return;
    }
    if (typing) return;
    if (playerOpen) {
      if (e.key === 'ArrowUp') { e.preventDefault(); zapBy(-1); }
      if (e.key === 'ArrowDown') { e.preventDefault(); zapBy(1); }
      if (e.key.toLowerCase() === 'f') $('#fs-btn').click();
      if (e.key === ' ') { e.preventDefault(); video.paused ? video.play() : video.pause(); }
    } else if (e.key === '/' && !$('#app').classList.contains('hidden')) {
      e.preventDefault();
      $('#item-search').focus();
    }
  });

  // =====================================================================
  //                               الدخول
  // =====================================================================
  $$('.seg-btn').forEach((t) => {
    t.onclick = () => {
      $$('.seg-btn').forEach((x) => x.classList.toggle('active', x === t));
      $('.seg').dataset.active = t.dataset.tab;
      $('#form-xtream').classList.toggle('hidden', t.dataset.tab !== 'xtream');
      $('#form-m3u').classList.toggle('hidden', t.dataset.tab !== 'm3u');
      $('#login-error').textContent = '';
    };
  });

  function renderAccount(u) {
    const acc = $('#account');
    if (!u) { acc.innerHTML = ''; return; }
    if (u.m3uTotal != null) { acc.innerHTML = `<b><span class="dot"></span>قائمة M3U</b>${u.m3uTotal} عنصر`; return; }
    let expTxt = 'غير محدود';
    let pct = 100;
    if (u.exp_date && Number(u.exp_date) > 0) {
      const exp = Number(u.exp_date) * 1000;
      const days = Math.max(0, Math.ceil((exp - Date.now()) / 86400000));
      expTxt = `${new Date(exp).toLocaleDateString('ar-EG')} (باقي ${days} يوم)`;
      pct = Math.max(4, Math.min(100, (days / 365) * 100));
    }
    acc.innerHTML = `<b><span class="dot"></span>${esc(u.username)}</b>
      ينتهي: ${esc(expTxt)}<br>الأجهزة: ${esc(u.active_cons ?? 0)} / ${esc(u.max_connections ?? '?')}
      <div class="bar"><i style="width:${pct}%"></i></div>`;
  }

  async function login(source) {
    const err = $('#login-error');
    err.textContent = '';
    const btns = $$('.login .btn.primary');
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
        renderAccount(u);
      } else {
        const r = await fetch('/api/m3u?url=' + encodeURIComponent(source.url));
        const text = await r.text();
        if (!r.ok) { let m = 'خطأ ' + r.status; try { m = JSON.parse(text).error || m; } catch {} throw new Error(m); }
        state.m3u = parseM3u(text);
        if (!state.m3u.groups.size) throw new Error('القائمة فاضية أو مش بصيغة M3U');
        renderAccount({ m3uTotal: [...state.m3u.groups.values()].reduce((a, g) => a + g.length, 0) });
      }
      $('#remember').checked ? store.set('iptv.source', source) : store.del('iptv.source');
      $('#login').classList.add('hidden');
      $('#app').classList.remove('hidden');
      renderSections();
      selectSection(sectionsFor()[0][0]);
    } catch (e) {
      err.textContent = e.message;
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
    closePlayer();
    $$('.modal').forEach((m) => m.classList.add('hidden'));
    store.del('iptv.source');
    state.source = null;
    state.current = null;
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
