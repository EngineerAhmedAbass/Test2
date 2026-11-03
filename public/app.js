/* مشغل IPTV - الواجهة */
(() => {
  'use strict';

  const $ = (s) => document.querySelector(s);
  const PAGE = 200; // عدد العناصر اللي بتظهر في المرة

  const state = {
    source: null, // {type:'xtream', server, username, password} | {type:'m3u', url}
    userInfo: null,
    section: null,
    categories: [],
    category: null,
    items: [],
    shown: PAGE,
    current: null, // العنصر اللي شغال
    m3u: null, // {groups: Map(name -> items[])}
    cache: new Map(),
  };

  // ---------- تخزين ----------
  const store = {
    get(k, d) {
      try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; }
    },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch {} },
    del(k) { try { localStorage.removeItem(k); } catch {} },
  };
  const favKey = () => 'iptv.favs.' + (state.source.type === 'xtream'
    ? state.source.server + '|' + state.source.username
    : state.source.url);
  const getFavs = () => store.get(favKey(), []);
  const isFav = (it) => getFavs().some((f) => f.key === it.key);
  function toggleFav(it) {
    let favs = getFavs();
    favs = isFav(it) ? favs.filter((f) => f.key !== it.key) : [...favs, it];
    store.set(favKey(), favs);
  }

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

  function parseM3u(text) {
    const groups = new Map();
    const lines = text.split(/\r?\n/);
    let meta = null;
    let n = 0;
    for (const raw of lines) {
      const line = raw.trim();
      if (!line) continue;
      if (line.startsWith('#EXTINF')) {
        const attr = (name) => (line.match(new RegExp(name + '="([^"]*)"', 'i')) || [])[1] || '';
        const comma = line.lastIndexOf(',');
        meta = {
          name: (comma >= 0 ? line.slice(comma + 1) : '').trim() || attr('tvg-name') || 'بدون اسم',
          logo: attr('tvg-logo'),
          group: attr('group-title') || 'أخرى',
        };
      } else if (!line.startsWith('#')) {
        const m = meta || { name: line, logo: '', group: 'أخرى' };
        const item = { key: 'm3u:' + line, type: 'stream', name: m.name, logo: m.logo, url: line, idx: n++ };
        if (!groups.has(m.group)) groups.set(m.group, []);
        groups.get(m.group).push(item);
        meta = null;
      }
    }
    return { groups };
  }

  // ---------- روابط التشغيل ----------
  function liveExt() {
    const f = state.userInfo?.allowed_output_formats || [];
    return f.length && !f.includes('m3u8') ? 'ts' : 'm3u8';
  }
  function streamUrl(it) {
    const s = state.source;
    if (it.url) return it.url;
    const base = `${s.server}`;
    const cred = `${encodeURIComponent(s.username)}/${encodeURIComponent(s.password)}`;
    if (it.type === 'live') return `${base}/live/${cred}/${it.id}.${liveExt()}`;
    if (it.type === 'movie') return `${base}/movie/${cred}/${it.id}.${it.ext || 'mp4'}`;
    if (it.type === 'episode') return `${base}/series/${cred}/${it.id}.${it.ext || 'mp4'}`;
    return null;
  }

  // ---------- المشغل ----------
  const video = $('#video');
  let hls = null;
  let mts = null;

  function stopPlayer() {
    if (hls) { hls.destroy(); hls = null; }
    if (mts) { try { mts.destroy(); } catch {} mts = null; }
    video.removeAttribute('src');
    video.load();
  }

  function msg(text) {
    const el = $('#player-msg');
    el.textContent = text || '';
    el.classList.toggle('hidden', !text);
  }

  function kindOf(url) {
    const p = url.split('?')[0].toLowerCase();
    if (p.endsWith('.m3u8') || p.endsWith('.m3u')) return 'hls';
    if (p.endsWith('.ts')) return 'ts';
    const ext = p.match(/\.([a-z0-9]{2,4})$/);
    if (ext) return 'file';
    return 'ts'; // روابط بدون امتداد غالبًا بث مباشر TS
  }

  function play(it) {
    const url = streamUrl(it);
    if (!url) return;
    state.current = it;
    stopPlayer();
    msg('جاري التحميل...');
    $('#now-title').textContent = it.name;
    $('#copy-btn').classList.remove('hidden');
    $('#fav-btn').classList.toggle('hidden', it.type === 'episode');
    updateFavBtn();

    const src = '/proxy?url=' + encodeURIComponent(url);
    const kind = kindOf(url);
    const fail = (why) => {
      msg('الفيديو ده مش راضي يشتغل في المتصفح' + (why ? ` (${why})` : '') +
        '.\nجرب قناة تانية، أو انسخ الرابط وشغله على VLC.');
    };

    if (kind === 'hls' && window.Hls && Hls.isSupported()) {
      hls = new Hls({ enableWorker: true, lowLatencyMode: false });
      hls.loadSource(src);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => video.play().catch(() => {}));
      hls.on(Hls.Events.ERROR, (_, d) => {
        if (!d.fatal) return;
        if (d.type === Hls.ErrorTypes.MEDIA_ERROR) return hls.recoverMediaError();
        // لو فشل HLS في بث مباشر نجرب TS
        if (it.type === 'live' && !it._triedTs) {
          it._triedTs = true;
          return play({ ...it, url: url.replace(/\.m3u8$/, '.ts') });
        }
        fail(d.details);
      });
    } else if (kind === 'ts' && window.mpegts && mpegts.isSupported()) {
      mts = mpegts.createPlayer(
        { type: 'mpegts', isLive: it.type !== 'movie' && it.type !== 'episode', url: new URL(src, location.href).href },
        { enableStashBuffer: true, liveBufferLatencyChasing: false, lazyLoad: false }
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

  video.addEventListener('playing', () => msg(''));
  video.addEventListener('waiting', () => { if (state.current) msg('جاري التحميل...'); });
  video.addEventListener('error', () => {
    if (!hls && !mts && state.current) msg('صيغة الفيديو مش مدعومة في المتصفح. انسخ الرابط وشغله على VLC.');
  });

  $('#copy-btn').addEventListener('click', async () => {
    const url = streamUrl(state.current);
    try { await navigator.clipboard.writeText(url); $('#copy-btn').textContent = '✓ اتنسخ'; }
    catch { prompt('انسخ الرابط:', url); }
    setTimeout(() => ($('#copy-btn').textContent = 'نسخ الرابط (لـ VLC)'), 1500);
  });

  function updateFavBtn() {
    if (!state.current) return;
    $('#fav-btn').textContent = isFav(state.current) ? '★ في المفضلة' : '☆ مفضلة';
  }
  $('#fav-btn').addEventListener('click', () => {
    toggleFav(state.current);
    updateFavBtn();
    if (state.section === 'favs') selectSection('favs');
  });

  // ---------- الأقسام ----------
  function sectionsFor() {
    if (state.source.type === 'xtream') {
      return [
        ['live', '📺 قنوات'],
        ['movie', '🎬 أفلام'],
        ['series', '🎞️ مسلسلات'],
        ['favs', '⭐ المفضلة'],
      ];
    }
    return [['m3u', '📺 القائمة'], ['favs', '⭐ المفضلة']];
  }

  function renderSections() {
    const nav = $('#sections');
    nav.innerHTML = '';
    for (const [id, label] of sectionsFor()) {
      const b = document.createElement('button');
      b.textContent = label;
      b.dataset.id = id;
      b.onclick = () => selectSection(id);
      nav.appendChild(b);
    }
  }

  async function selectSection(id) {
    state.section = id;
    document.querySelectorAll('#sections button').forEach((b) => b.classList.toggle('active', b.dataset.id === id));
    $('#cat-search').value = '';
    $('#episodes').innerHTML = '';
    setCategories([]);
    setItems([], 'جاري التحميل...');

    try {
      if (id === 'favs') {
        setCategories([{ id: 'favs', name: 'المفضلة', count: getFavs().length }]);
        return selectCategory(state.categories[0]);
      }
      if (id === 'm3u') {
        const cats = [{ id: '__all', name: 'الكل', count: 0 }];
        let total = 0;
        for (const [name, list] of state.m3u.groups) {
          cats.push({ id: name, name, count: list.length });
          total += list.length;
        }
        cats[0].count = total;
        setCategories(cats);
        return selectCategory(cats[1] || cats[0]);
      }
      const action = { live: 'get_live_categories', movie: 'get_vod_categories', series: 'get_series_categories' }[id];
      const data = await cached(action, () => xtream({ action }));
      const cats = [{ id: '__all', name: 'الكل' }, ...(Array.isArray(data) ? data : []).map((c) => ({
        id: c.category_id, name: c.category_name,
      }))];
      if (state.section !== id) return;
      setCategories(cats);
      selectCategory(cats[1] || cats[0]);
    } catch (e) {
      setItems([], 'حصل خطأ: ' + e.message);
    }
  }

  async function cached(key, fn) {
    if (!state.cache.has(key)) {
      const p = fn();
      state.cache.set(key, p);
      p.catch(() => state.cache.delete(key));
    }
    return state.cache.get(key);
  }

  function setCategories(cats) {
    state.categories = cats;
    renderCategories();
  }

  function renderCategories() {
    const q = $('#cat-search').value.trim().toLowerCase();
    const ul = $('#cat-list');
    ul.innerHTML = '';
    for (const c of state.categories) {
      if (q && !c.name.toLowerCase().includes(q)) continue;
      const li = document.createElement('li');
      li.innerHTML = `<span class="name"></span>${c.count != null ? `<span class="count">${c.count}</span>` : ''}`;
      li.querySelector('.name').textContent = c.name;
      li.title = c.name;
      if (state.category && state.category.id === c.id) li.classList.add('active');
      li.onclick = () => selectCategory(c);
      ul.appendChild(li);
    }
  }
  $('#cat-search').addEventListener('input', renderCategories);

  async function selectCategory(cat) {
    state.category = cat;
    renderCategories();
    $('#item-search').value = '';
    setItems([], 'جاري التحميل...');
    const sec = state.section;
    try {
      let items;
      if (sec === 'favs') items = getFavs();
      else if (sec === 'm3u') {
        items = cat.id === '__all' ? [...state.m3u.groups.values()].flat() : state.m3u.groups.get(cat.id) || [];
      } else {
        const action = { live: 'get_live_streams', movie: 'get_vod_streams', series: 'get_series' }[sec];
        const params = { action };
        if (cat.id !== '__all') params.category_id = cat.id;
        const data = await cached(action + ':' + cat.id, () => xtream(params));
        items = (Array.isArray(data) ? data : []).map((x) => normalize(sec, x));
      }
      if (state.section !== sec || state.category !== cat) return;
      setItems(items, items.length ? '' : 'مفيش حاجة هنا');
    } catch (e) {
      setItems([], 'حصل خطأ: ' + e.message);
    }
  }

  function normalize(sec, x) {
    if (sec === 'live') {
      return { key: 'live:' + x.stream_id, type: 'live', id: x.stream_id, name: x.name, logo: x.stream_icon };
    }
    if (sec === 'movie') {
      return {
        key: 'movie:' + x.stream_id, type: 'movie', id: x.stream_id, name: x.name,
        logo: x.stream_icon, ext: x.container_extension,
      };
    }
    return { key: 'series:' + x.series_id, type: 'series', id: x.series_id, name: x.name, logo: x.cover };
  }

  // ---------- قائمة العناصر ----------
  let emptyMsg = '';
  function setItems(items, empty) {
    state.items = items;
    state.shown = PAGE;
    emptyMsg = empty || '';
    renderItems();
  }

  function renderItems() {
    const q = $('#item-search').value.trim().toLowerCase();
    const list = q ? state.items.filter((i) => (i.name || '').toLowerCase().includes(q)) : state.items;
    const ul = $('#item-list');
    ul.innerHTML = '';
    if (!list.length) {
      const li = document.createElement('li');
      li.className = 'more';
      li.textContent = q ? 'مفيش نتايج' : emptyMsg;
      ul.appendChild(li);
      return;
    }
    const frag = document.createDocumentFragment();
    for (const it of list.slice(0, state.shown)) {
      const li = document.createElement('li');
      if (it.logo) {
        const img = document.createElement('img');
        img.loading = 'lazy';
        img.referrerPolicy = 'no-referrer';
        img.src = it.logo;
        img.onerror = () => (img.style.visibility = 'hidden');
        li.appendChild(img);
      }
      const name = document.createElement('span');
      name.className = 'name';
      name.textContent = it.name;
      li.title = it.name;
      li.appendChild(name);
      if (state.current && state.current.key === it.key) li.classList.add('active');
      li.onclick = () => {
        ul.querySelectorAll('li.active').forEach((x) => x.classList.remove('active'));
        li.classList.add('active');
        it.type === 'series' ? openSeries(it) : play(it);
      };
      frag.appendChild(li);
    }
    if (list.length > state.shown) {
      const more = document.createElement('li');
      more.className = 'more';
      more.textContent = `عرض المزيد (${list.length - state.shown} باقي)`;
      more.onclick = () => { state.shown += PAGE; renderItems(); };
      frag.appendChild(more);
    }
    ul.appendChild(frag);
  }
  $('#item-search').addEventListener('input', () => { state.shown = PAGE; renderItems(); });

  // ---------- المسلسلات ----------
  async function openSeries(series) {
    const box = $('#episodes');
    box.innerHTML = '<h3>جاري تحميل الحلقات...</h3>';
    state.current = series;
    $('#now-title').textContent = series.name;
    $('#fav-btn').classList.remove('hidden');
    $('#copy-btn').classList.add('hidden');
    updateFavBtn();
    try {
      const info = await cached('series_info:' + series.id, () =>
        xtream({ action: 'get_series_info', series_id: series.id }));
      const eps = info.episodes || {};
      const seasons = Object.keys(eps).sort((a, b) => Number(a) - Number(b));
      if (!seasons.length) { box.innerHTML = '<h3>مفيش حلقات</h3>'; return; }
      box.innerHTML = '';
      const tabs = document.createElement('div');
      tabs.className = 'season-tabs';
      const ul = document.createElement('ul');
      const showSeason = (s) => {
        tabs.querySelectorAll('button').forEach((b) => b.classList.toggle('active', b.dataset.s === s));
        ul.innerHTML = '';
        for (const ep of eps[s]) {
          const li = document.createElement('li');
          const title = `${series.name} - م${s} ح${ep.episode_num}`;
          li.innerHTML = '<span class="name"></span>';
          li.querySelector('.name').textContent = `حلقة ${ep.episode_num}: ${ep.title || ''}`;
          li.onclick = () => {
            ul.querySelectorAll('li.active').forEach((x) => x.classList.remove('active'));
            li.classList.add('active');
            play({ key: 'ep:' + ep.id, type: 'episode', id: ep.id, name: title, ext: ep.container_extension });
          };
          ul.appendChild(li);
        }
      };
      for (const s of seasons) {
        const b = document.createElement('button');
        b.textContent = 'الموسم ' + s;
        b.dataset.s = s;
        b.onclick = () => showSeason(s);
        tabs.appendChild(b);
      }
      box.append(tabs, ul);
      showSeason(seasons[0]);
    } catch (e) {
      box.innerHTML = '';
      const h = document.createElement('h3');
      h.textContent = 'حصل خطأ: ' + e.message;
      box.appendChild(h);
    }
  }

  // ---------- الدخول ----------
  document.querySelectorAll('.tab').forEach((t) => {
    t.onclick = () => {
      document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('active', x === t));
      $('#form-xtream').classList.toggle('hidden', t.dataset.tab !== 'xtream');
      $('#form-m3u').classList.toggle('hidden', t.dataset.tab !== 'm3u');
    };
  });

  async function login(source) {
    const err = $('#login-error');
    err.textContent = '';
    const btns = document.querySelectorAll('.login button.primary');
    btns.forEach((b) => (b.disabled = true));
    state.source = source;
    state.cache = new Map();
    try {
      if (source.type === 'xtream') {
        const data = await xtream();
        const u = data.user_info;
        if (!u || Number(u.auth) === 0) throw new Error('اسم المستخدم أو كلمة السر غلط');
        if (u.status && u.status !== 'Active') throw new Error('الاشتراك حالته: ' + u.status);
        state.userInfo = u;
        const exp = u.exp_date ? new Date(Number(u.exp_date) * 1000).toLocaleDateString('ar-EG') : 'غير محدود';
        $('#account').textContent = `👤 ${u.username} • ينتهي: ${exp} • أجهزة: ${u.active_cons ?? 0}/${u.max_connections ?? '?'}`;
      } else {
        const r = await fetch('/api/m3u?url=' + encodeURIComponent(source.url));
        const text = await r.text();
        if (!r.ok) {
          let m = 'خطأ ' + r.status;
          try { m = JSON.parse(text).error || m; } catch {}
          throw new Error(m);
        }
        state.m3u = parseM3u(text);
        if (!state.m3u.groups.size) throw new Error('القائمة فاضية أو مش بصيغة M3U');
        const total = [...state.m3u.groups.values()].reduce((a, g) => a + g.length, 0);
        $('#account').textContent = `📃 ${total} عنصر`;
      }
      if ($('#remember').checked) store.set('iptv.source', source);
      else store.del('iptv.source');
      $('#login').classList.add('hidden');
      $('#app').classList.remove('hidden');
      renderSections();
      selectSection(sectionsFor()[0][0]);
    } catch (e) {
      err.textContent = e.message;
      state.source = null;
    } finally {
      btns.forEach((b) => (b.disabled = false));
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
    // لو الرابط بتاع Xtream (get.php) نحوله تلقائيًا لدخول Xtream عشان نجيب الأفلام والمسلسلات
    try {
      const u = new URL(url);
      const user = u.searchParams.get('username');
      const pass = u.searchParams.get('password');
      if (u.pathname.endsWith('/get.php') && user && pass) {
        return login({ type: 'xtream', server: u.origin, username: user, password: pass });
      }
    } catch {}
    login({ type: 'm3u', url });
  });

  $('#logout').addEventListener('click', () => {
    stopPlayer();
    store.del('iptv.source');
    state.source = null;
    state.current = null;
    $('#app').classList.add('hidden');
    $('#login').classList.remove('hidden');
    msg('اختار قناة أو فيلم عشان يشتغل');
    $('#now-title').textContent = '';
    $('#episodes').innerHTML = '';
  });

  // دخول تلقائي لو البيانات محفوظة
  const saved = store.get('iptv.source', null);
  if (saved) {
    if (saved.type === 'xtream') {
      $('#form-xtream').server.value = saved.server;
      $('#form-xtream').username.value = saved.username;
      $('#form-xtream').password.value = saved.password;
    } else {
      document.querySelector('.tab[data-tab="m3u"]').click();
      $('#form-m3u').url.value = saved.url;
    }
    login(saved);
  }
})();
