/* GOAL — نتايج الماتشات (API-Football v3) */
(() => {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const TZ = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Africa/Cairo';

  // الدوريات الافتراضية (أرقامها في API-Football)
  const DEFAULT_LEAGUES = [
    { id: 233, name: 'Premier League', country: 'Egypt' },
    { id: 12, name: 'CAF Champions League', country: 'World' },
    { id: 2, name: 'UEFA Champions League', country: 'World' },
    { id: 39, name: 'Premier League', country: 'England' },
    { id: 140, name: 'La Liga', country: 'Spain' },
    { id: 135, name: 'Serie A', country: 'Italy' },
    { id: 78, name: 'Bundesliga', country: 'Germany' },
    { id: 61, name: 'Ligue 1', country: 'France' },
    { id: 307, name: 'Pro League', country: 'Saudi-Arabia' },
  ];
  const AR_LEAGUE = {
    233: 'الدوري المصري', 12: 'دوري أبطال أفريقيا', 20: 'الكونفدرالية الأفريقية', 2: 'دوري أبطال أوروبا', 3: 'الدوري الأوروبي',
    39: 'الدوري الإنجليزي', 140: 'الدوري الإسباني', 135: 'الدوري الإيطالي', 78: 'الدوري الألماني', 61: 'الدوري الفرنسي',
    307: 'الدوري السعودي', 1: 'كأس العالم', 6: 'كأس أمم أفريقيا', 714: 'كأس مصر', 45: 'كأس الاتحاد الإنجليزي', 143: 'كأس ملك إسبانيا',
  };
  const AR_COUNTRY = { Egypt: 'مصر', England: 'إنجلترا', Spain: 'إسبانيا', Italy: 'إيطاليا', Germany: 'ألمانيا', France: 'فرنسا', 'Saudi-Arabia': 'السعودية', World: 'دولي', Morocco: 'المغرب', Tunisia: 'تونس', Algeria: 'الجزائر' };
  const LIVE = new Set(['1H', 'HT', '2H', 'ET', 'BT', 'P', 'SUSP', 'INT', 'LIVE']);
  const DONE = new Set(['FT', 'AET', 'PEN', 'AWD', 'WO']);
  const ST_TEXT = { HT: 'استراحة', BT: 'استراحة', P: 'ركلات ترجيح', FT: 'انتهت', AET: 'بعد الإضافي', PEN: 'بالترجيح', PST: 'اتأجلت', CANC: 'اتلغت', ABD: 'اتوقفت', SUSP: 'معلّقة', INT: 'متوقفة', TBD: 'لم يتحدد', AWD: 'قرار إداري', WO: 'انسحاب' };
  const AR_STAT = {
    'Ball Possession': 'الاستحواذ', 'Total Shots': 'التسديدات', 'Shots on Goal': 'على المرمى', 'Shots off Goal': 'خارج المرمى',
    'Blocked Shots': 'تسديدات اتصدت', 'Shots insidebox': 'من جوه الصندوق', 'Shots outsidebox': 'من برة الصندوق', 'Corner Kicks': 'الركنيات',
    Fouls: 'الأخطاء', Offsides: 'التسلل', 'Yellow Cards': 'إنذارات', 'Red Cards': 'طرد', 'Goalkeeper Saves': 'تصديات الحارس',
    'Total passes': 'التمريرات', 'Passes accurate': 'تمريرات صحيحة', 'Passes %': 'دقة التمرير', expected_goals: 'الأهداف المتوقعة (xG)', goals_prevented: 'أهداف اتمنعت',
  };

  // =====================================================================
  //                         الإعدادات والتخزين
  // =====================================================================
  const ls = {
    get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch { return false; } },
  };
  const cfg = Object.assign({ key: '', leagues: DEFAULT_LEAGUES, teams: [], interval: 120, tableLeague: 233 }, ls.get('goal.cfg', {}));
  const saveCfg = () => ls.set('goal.cfg', cfg);
  const leagueIds = () => new Set(cfg.leagues.map((l) => l.id));
  const teamIds = () => new Set(cfg.teams.map((t) => t.id));

  const state = {
    view: 'matches',
    date: dayKey(new Date()),
    filter: ls.get('goal.filter', 'mine'),
    days: new Map(), // date -> {t, list}
    quota: ls.get('goal.quota', null),
    liveTimer: 0,
    lastLive: 0,
    openId: null,
    matchTimer: 0,
  };

  // =====================================================================
  //                                أدوات
  // =====================================================================
  function dayKey(d) { return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`; }
  const today = () => dayKey(new Date());
  const hhmm = (ms) => new Date(ms).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' });
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };
  const leagueName = (l) => AR_LEAGUE[l.id] || l.name;
  const countryName = (c) => AR_COUNTRY[c] || c || '';
  const imgTag = (src, cls = '') => (src ? `<img class="${cls}" src="${esc(src)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.style.visibility='hidden'">` : '');

  function toast(text, cls = '') {
    const el = document.createElement('div');
    el.className = 'toast ' + cls;
    el.textContent = text;
    $('#toasts').appendChild(el);
    setTimeout(() => el.remove(), cls === 'goal' ? 5000 : 2600);
  }

  // =====================================================================
  //                                API
  // =====================================================================
  class ApiErr extends Error { constructor(msg, kind) { super(msg); this.kind = kind || 'api'; } }

  function apiError(j) {
    const e = j && j.errors;
    if (!e || (Array.isArray(e) && !e.length) || (typeof e === 'object' && !Object.keys(e).length)) return null;
    const entries = Array.isArray(e) ? e.map((v) => ['', typeof v === 'string' ? v : JSON.stringify(v)]) : Object.entries(e);
    const [k, v] = entries[0];
    const raw = String(v);
    if (k === 'token' || /application key|api key|not subscribed|account/i.test(raw)) return new ApiErr('المفتاح غلط أو الحساب مش متفعّل — راجع المفتاح في الإعدادات.\n(' + raw + ')', 'key');
    if (k === 'requests' || /request limit|limit for the day/i.test(raw)) { setQuota(0); return new ApiErr('خلصت طلبات النهارده (حد الخطة المجانية). هتتجدد بكرة.', 'quota'); }
    if (k === 'rateLimit' || /too many requests/i.test(raw)) return new ApiErr('طلبات كتير ورا بعض — استنى دقيقة وجرّب تاني.', 'rate');
    if (k === 'plan' || /free plan|plan/i.test(raw)) return new ApiErr('الخطة المجانية مش بتغطي الطلب ده:\n' + raw, 'plan');
    return new ApiErr(raw, 'api');
  }

  function setQuota(rem, lim) {
    state.quota = { rem: Number(rem), lim: Number(lim || state.quota?.lim || 100), day: today() };
    ls.set('goal.quota', state.quota);
    renderQuota();
  }
  function renderQuota() {
    const q = state.quota;
    const el = $('#quota');
    if (!q || q.day !== today()) { el.classList.add('hidden'); return; }
    el.classList.remove('hidden');
    el.textContent = `${q.rem} / ${q.lim} طلب`;
    el.classList.toggle('low', q.rem > 0 && q.rem < 25);
    el.classList.toggle('out', q.rem <= 0);
  }

  const inflight = new Map();
  function api(ep, params = {}) {
    const qs = new URLSearchParams(params).toString();
    const ck = ep + '?' + qs;
    if (inflight.has(ck)) return inflight.get(ck);
    const p = (async () => {
      if (!cfg.key) throw new ApiErr('محتاج تحط مفتاح API-Football الأول من الإعدادات.', 'nokey');
      let r;
      try { r = await fetch(`/api/football/${ep}?${qs}${qs ? '&' : ''}_k=${encodeURIComponent(cfg.key)}`); }
      catch { throw new ApiErr('مفيش اتصال بالنت.', 'net'); }
      const rem = r.headers.get('x-ratelimit-requests-remaining');
      if (rem != null && rem !== '') setQuota(rem, r.headers.get('x-ratelimit-requests-limit'));
      let j;
      try { j = await r.json(); } catch { throw new ApiErr(`رد غير متوقع من السيرفر (${r.status}).`, 'net'); }
      const err = apiError(j);
      if (err) throw err;
      if (!r.ok) throw new ApiErr(`السيرفر رد بخطأ ${r.status}.`, 'net');
      return j.response;
    })();
    inflight.set(ck, p);
    p.then(() => inflight.delete(ck), () => inflight.delete(ck));
    return p;
  }

  // كاش في الذاكرة + على الجهاز، ولو الطلب فشل نرجّع آخر نسخة محفوظة
  const mem = new Map();
  async function cached(key, ttl, fn, { persist = true, force = false } = {}) {
    const hit = mem.get(key) || (persist ? ls.get('goal.c.' + key, null) : null);
    if (hit && !force && Date.now() - hit.t < ttl) return hit.d;
    try {
      const d = await fn();
      const rec = { t: Date.now(), d };
      mem.set(key, rec);
      if (persist) ls.set('goal.c.' + key, rec);
      return d;
    } catch (e) {
      if (hit && e.kind !== 'key' && e.kind !== 'nokey') { e.stale = hit.d; }
      throw e;
    }
  }
  // تنضيف الكاش القديم مرة عند الفتح
  try {
    const old = Date.now() - 3 * 86400000;
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i);
      if (k && k.startsWith('goal.c.')) { const v = ls.get(k, null); if (!v || v.t < old) localStorage.removeItem(k); }
    }
  } catch {}

  // =====================================================================
  //                             المباريات
  // =====================================================================
  const compact = (f) => ({
    id: f.fixture.id, ts: f.fixture.timestamp * 1000, st: f.fixture.status.short, el: f.fixture.status.elapsed, ex: f.fixture.status.extra ?? null,
    ven: f.fixture.venue?.name || '', city: f.fixture.venue?.city || '', ref: f.fixture.referee || '',
    lg: { id: f.league.id, name: f.league.name, country: f.league.country, logo: f.league.logo, round: f.league.round || '' },
    h: { id: f.teams.home.id, name: f.teams.home.name, logo: f.teams.home.logo, w: f.teams.home.winner },
    a: { id: f.teams.away.id, name: f.teams.away.name, logo: f.teams.away.logo, w: f.teams.away.winner },
    g: [f.goals.home, f.goals.away],
    pen: [f.score?.penalty?.home ?? null, f.score?.penalty?.away ?? null],
  });

  function dayTtl(date) {
    const t = today();
    if (date < t) return 7 * 86400000; // الأيام اللي فاتت مش هتتغير
    if (date > t) return 60 * 60000;
    return 10 * 60000;
  }

  async function loadDay(date, force = false) {
    const box = $('#matches');
    if (!state.days.has(date)) box.innerHTML = '<div class="skeleton"></div>'.repeat(6);
    $('#refresh').classList.add('spin');
    try {
      const list = await cached('day.' + date, dayTtl(date), async () => (await api('fixtures', { date, timezone: TZ })).map(compact), { force });
      state.days.set(date, { t: Date.now(), list });
      if (date === today()) state.lastLive = Date.now();
    } catch (e) {
      if (e.stale) { state.days.set(date, { t: 0, list: e.stale }); toast(e.message + '\n(بعرض آخر نسخة محفوظة)'); }
      else if (state.date === date) { showError(box, e); return; }
    } finally {
      $('#refresh').classList.remove('spin');
    }
    if (state.date === date) { renderMatches(); scheduleLive(); }
  }

  function showError(box, e) {
    box.innerHTML = `<div class="empty"><h4>${e.kind === 'nokey' ? 'محتاج مفتاح' : 'حصلت مشكلة'}</h4><div style="white-space:pre-line">${esc(e.message)}</div>
      ${e.kind === 'nokey' || e.kind === 'key' ? '<button class="btn primary" data-go="settings" type="button">روح للإعدادات</button>' : '<button class="btn" data-retry type="button">جرّب تاني</button>'}</div>`;
    $('[data-go]', box)?.addEventListener('click', () => go('settings'));
    $('[data-retry]', box)?.addEventListener('click', () => loadDay(state.date, true));
  }

  function renderDates() {
    const box = $('#dates');
    box.innerHTML = '';
    const base = new Date();
    for (let i = -7; i <= 7; i++) {
      const d = new Date(base.getFullYear(), base.getMonth(), base.getDate() + i);
      const k = dayKey(d);
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'date' + (k === state.date ? ' active' : '');
      b.setAttribute('role', 'tab');
      const label = i === 0 ? 'النهارده' : i === -1 ? 'امبارح' : i === 1 ? 'بكرة' : d.toLocaleDateString('ar-EG', { weekday: 'short' });
      b.innerHTML = `<b>${label}</b><small>${d.getDate()}/${d.getMonth() + 1}</small>`;
      b.onclick = () => { state.date = k; renderDates(); loadDay(k); };
      box.appendChild(b);
    }
    $('#dates .date.active')?.scrollIntoView({ inline: 'center', block: 'nearest' });
  }

  $$('.filters .chip').forEach((c) => {
    c.classList.toggle('active', c.dataset.filter === state.filter);
    c.onclick = () => {
      state.filter = c.dataset.filter;
      ls.set('goal.filter', state.filter);
      $$('.filters .chip').forEach((x) => x.classList.toggle('active', x === c));
      renderMatches();
      scheduleLive();
    };
  });

  function visibleFixtures() {
    const day = state.days.get(state.date);
    if (!day) return [];
    const L = leagueIds();
    const T = teamIds();
    if (state.filter === 'live') return day.list.filter((f) => LIVE.has(f.st));
    if (state.filter === 'mine') return day.list.filter((f) => L.has(f.lg.id) || T.has(f.h.id) || T.has(f.a.id));
    return day.list;
  }

  function renderMatches() {
    const box = $('#matches');
    const day = state.days.get(state.date);
    if (!day) return;
    const list = visibleFixtures();
    const upd = state.date === today() && day.t ? `اتحدّث ${hhmm(Math.max(day.t, state.lastLive))}` : '';
    $('#updated').textContent = upd;
    if (!list.length) {
      const msg = state.filter === 'live' ? ['مفيش ماتشات شغالة دلوقتي', ''] :
        state.filter === 'mine' ? ['مفيش ماتشات لدورياتك في اليوم ده', `فيه ${day.list.length} ماتش في دوريات تانية.`] : ['مفيش ماتشات في اليوم ده', ''];
      box.innerHTML = `<div class="empty"><h4>${msg[0]}</h4><div>${msg[1]}</div>${state.filter !== 'all' && day.list.length ? '<button class="btn" data-all type="button">اعرض كل الماتشات</button>' : ''}</div>`;
      $('[data-all]', box)?.addEventListener('click', () => $('.filters .chip[data-filter=all]').click());
      return;
    }
    // تجميع حسب الدوري: دورياتك الأول بنفس ترتيبها، وبعدين الباقي
    const order = new Map(cfg.leagues.map((l, i) => [l.id, i]));
    const groups = new Map();
    for (const f of list) {
      if (!groups.has(f.lg.id)) groups.set(f.lg.id, { lg: f.lg, items: [] });
      groups.get(f.lg.id).items.push(f);
    }
    const sorted = [...groups.values()].sort((a, b) => {
      const oa = order.has(a.lg.id) ? order.get(a.lg.id) : 1e6;
      const ob = order.has(b.lg.id) ? order.get(b.lg.id) : 1e6;
      return oa - ob || String(a.lg.country).localeCompare(b.lg.country) || a.lg.name.localeCompare(b.lg.name);
    });
    const T = teamIds();
    let html = '';
    for (const g of sorted) {
      g.items.sort((a, b) => a.ts - b.ts || a.id - b.id);
      const rounds = [...new Set(g.items.map((f) => f.lg.round))];
      html += `<section class="league"><div class="league-head">${imgTag(g.lg.logo)}<div><div class="nm">${esc(leagueName(g.lg))}</div><div class="ct">${esc(countryName(g.lg.country))}</div></div>
        <span class="round">${rounds.length === 1 ? esc(roundName(rounds[0])) : ''}</span></div>`;
      for (const f of g.items) html += matchRow(f, T.has(f.h.id) || T.has(f.a.id));
      html += '</section>';
    }
    box.innerHTML = html;
    $$('.match', box).forEach((b) => (b.onclick = () => openMatch(Number(b.dataset.id))));
  }

  function roundName(r) {
    return String(r || '').replace(/Regular Season - (\d+)/, 'الجولة $1').replace(/Group Stage - (\d+)/, 'دور المجموعات — الجولة $1')
      .replace('Round of 16', 'دور الـ16').replace('Quarter-finals', 'ربع النهائي').replace('Semi-finals', 'نص النهائي').replace(/^Final$/, 'النهائي');
  }

  function statusText(f) {
    if (LIVE.has(f.st) && !ST_TEXT[f.st]) return f.el != null ? `<span dir="ltr">${f.el}${f.ex ? '+' + f.ex : ''}'</span>` : 'مباشر';
    return ST_TEXT[f.st] || '';
  }

  function matchRow(f, fav) {
    const live = LIVE.has(f.st);
    const started = live || DONE.has(f.st) || (f.g[0] != null && f.st !== 'NS');
    const mid = started
      ? `<div class="score">${f.g[0] ?? 0} - ${f.g[1] ?? 0}</div>${f.pen[0] != null ? `<div class="pen">(${f.pen[0]} - ${f.pen[1]})</div>` : ''}<div class="st">${statusText(f)}</div>`
      : `<div class="time">${hhmm(f.ts)}</div><div class="st">${statusText(f)}</div>`;
    const lose = (t) => (DONE.has(f.st) && t.w === false ? ' lose' : '');
    return `<button type="button" class="match${live ? ' live' : ''}${fav ? ' fav' : ''}" data-id="${f.id}">
      <div class="team${lose(f.h)}">${imgTag(f.h.logo)}<span>${esc(f.h.name)}</span></div>
      <div class="mid">${mid}</div>
      <div class="team away${lose(f.a)}">${imgTag(f.a.logo)}<span>${esc(f.a.name)}</span></div></button>`;
  }

  // ---------- التحديث المباشر (بحساب الطلبات) ----------
  function effectiveInterval() {
    if (!cfg.interval) return 0;
    const q = state.quota;
    if (q && q.day === today()) {
      if (q.rem <= 3) return 0; // نسيب آخر كام طلب للفتح اليدوي
      if (q.rem < 15) return Math.max(cfg.interval, 600);
      if (q.rem < 35) return Math.max(cfg.interval, 300);
    }
    return cfg.interval;
  }

  function scheduleLive() {
    clearTimeout(state.liveTimer);
    if (state.view !== 'matches' || state.date !== today()) return;
    const day = state.days.get(today());
    if (!day) return;
    const iv = effectiveInterval();
    if (!iv) return;
    const now = Date.now();
    const rel = visibleFixtures();
    const live = rel.some((f) => LIVE.has(f.st) || (f.st === 'NS' && f.ts <= now && f.ts > now - 3 * 3600000));
    let wait;
    if (live) wait = Math.max(5000, state.lastLive + iv * 1000 - now);
    else {
      const next = rel.filter((f) => f.st === 'NS' && f.ts > now).sort((a, b) => a.ts - b.ts)[0];
      if (!next || next.ts - now > 6 * 3600000) return;
      wait = next.ts - now + 60000; // دقيقة بعد ميعاد الماتش
    }
    state.liveTimer = setTimeout(refreshLive, wait);
  }

  async function refreshLive() {
    if (document.hidden) return; // هنكمّل لما التطبيق يرجع قدامك
    const day = state.days.get(today());
    if (!day) return;
    const ids = state.filter === 'mine'
      ? [...new Set(visibleFixtures().map((f) => f.lg.id))].slice(0, 20)
      : null;
    $('#refresh').classList.add('spin');
    try {
      const resp = await api('fixtures', ids && ids.length ? { live: ids.join('-'), timezone: TZ } : { live: 'all', timezone: TZ });
      const fresh = new Map(resp.map((f) => [f.fixture.id, compact(f)]));
      const scope = ids ? new Set(ids) : null;
      let ended = false;
      const goals = [];
      day.list = day.list.map((f) => {
        const n = fresh.get(f.id);
        if (n) {
          if (f.g[0] != null && (n.g[0] > f.g[0] || n.g[1] > f.g[1])) goals.push(n);
          return n;
        }
        if (LIVE.has(f.st) && (!scope || scope.has(f.lg.id))) ended = true; // كان شغال ومبقاش في اللايف = خلص
        return f;
      });
      for (const n of fresh.values()) if (!day.list.some((f) => f.id === n.id) && dayKey(new Date(n.ts)) === today()) day.list.push(n);
      state.lastLive = Date.now();
      mem.set('day.' + today(), { t: day.t, d: day.list });
      if (ended) await loadDay(today(), true);
      renderMatches();
      announceGoals(goals);
    } catch (e) {
      toast(e.message);
    } finally {
      $('#refresh').classList.remove('spin');
      scheduleLive();
    }
  }

  function announceGoals(list) {
    const L = leagueIds();
    const T = teamIds();
    for (const f of list) {
      if (!L.has(f.lg.id) && !T.has(f.h.id) && !T.has(f.a.id)) continue;
      toast(`جوووول! ${f.h.name} ${f.g[0]} - ${f.g[1]} ${f.a.name}`, 'goal');
      $(`.match[data-id="${f.id}"]`)?.classList.add('flash');
      if (navigator.vibrate) navigator.vibrate([120, 60, 120]);
    }
  }

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && state.view === 'matches' && state.date === today()) {
      const iv = effectiveInterval();
      if (iv && Date.now() - state.lastLive > iv * 1000) refreshLive(); else scheduleLive();
    }
  });

  $('#refresh').onclick = () => {
    if (state.view === 'matches') loadDay(state.date, true);
    else if (state.view === 'table') loadTable(true);
  };

  // =====================================================================
  //                           تفاصيل الماتش
  // =====================================================================
  async function openMatch(id, silent) {
    state.openId = id;
    const modal = $('#match');
    const body = $('#match-body');
    modal.classList.remove('hidden');
    const base = [...state.days.values()].flatMap((d) => d.list).find((f) => f.id === id);
    if (!silent) {
      body.innerHTML = base ? header(base) + '<div class="note">جاري تحميل التفاصيل...</div>' : '<div class="note">جاري التحميل...</div>';
      bindStars(body);
    }
    const live = base && LIVE.has(base.st);
    const ttl = base && DONE.has(base.st) ? 86400000 : live ? 45000 : 10 * 60000;
    try {
      const resp = await cached('fx.' + id, ttl, () => api('fixtures', { id }), { persist: !live, force: !!silent });
      if (state.openId !== id) return;
      const f = resp[0];
      if (!f) throw new ApiErr('الماتش ده مش موجود.');
      renderMatch(f);
    } catch (e) {
      if (state.openId !== id) return;
      if (e.stale && e.stale[0]) renderMatch(e.stale[0]);
      else if (!silent) body.insertAdjacentHTML('beforeend', `<div class="note" style="white-space:pre-line">${esc(e.message)}</div>`);
    }
    clearTimeout(state.matchTimer);
    const iv = effectiveInterval();
    if (live && iv) state.matchTimer = setTimeout(() => { if (state.openId === id && !document.hidden) openMatch(id, true); }, Math.max(iv, 60) * 1000);
  }

  function header(c) {
    const T = teamIds();
    const live = LIVE.has(c.st);
    const started = live || DONE.has(c.st) || (c.g[0] != null && c.st !== 'NS');
    const star = (t) => `<button type="button" class="star${T.has(t.id) ? ' on' : ''}" data-team='${esc(JSON.stringify({ id: t.id, name: t.name, logo: t.logo }))}' aria-label="فريق مفضل"><svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/></svg></button>`;
    const d = new Date(c.ts);
    return `<div class="mh">
      <div class="lg">${imgTag(c.lg.logo)}<span>${esc(leagueName(c.lg))} • ${esc(roundName(c.lg.round))}</span></div>
      <div class="mh-row">
        <div class="mh-team">${imgTag(c.h.logo)}<span>${esc(c.h.name)}</span>${star(c.h)}</div>
        <div>${started ? `<div class="mh-score">${c.g[0] ?? 0} - ${c.g[1] ?? 0}</div>` : `<div class="mh-time">${hhmm(c.ts)}</div>`}
          ${c.pen[0] != null ? `<div class="pen">ترجيح (${c.pen[0]} - ${c.pen[1]})</div>` : ''}
          <div class="mh-st${live ? ' live' : ''}">${statusText(c) || d.toLocaleDateString('ar-EG', { weekday: 'long', day: 'numeric', month: 'long' })}</div></div>
        <div class="mh-team">${imgTag(c.a.logo)}<span>${esc(c.a.name)}</span>${star(c.a)}</div>
      </div>
      <div class="mh-meta">${[c.ven, c.city].filter(Boolean).map(esc).join('، ')}${c.ref ? ' • الحكم: ' + esc(c.ref) : ''}</div>
    </div>`;
  }

  function bindStars(root) {
    $$('.star[data-team]', root).forEach((b) => {
      b.onclick = () => {
        const t = JSON.parse(b.dataset.team);
        const has = cfg.teams.some((x) => x.id === t.id);
        cfg.teams = has ? cfg.teams.filter((x) => x.id !== t.id) : [...cfg.teams, t];
        saveCfg();
        b.classList.toggle('on', !has);
        toast(has ? `${t.name} اتشال من فرقك` : `${t.name} اتضاف لفرقك`);
        renderMatches();
        renderTeams();
      };
    });
  }

  let matchTab = 'events';
  function renderMatch(f) {
    const c = compact(f);
    const body = $('#match-body');
    const tabs = [['events', 'الأحداث'], ['stats', 'الإحصائيات'], ['lineups', 'التشكيل']];
    body.innerHTML = header(c) + `<div class="tabs">${tabs.map(([k, l]) => `<button type="button" class="tab${k === matchTab ? ' active' : ''}" data-tab="${k}">${l}</button>`).join('')}</div><div class="pane"></div>`;
    bindStars(body);
    const pane = $('.pane', body);
    const draw = () => {
      $$('.tab', body).forEach((t) => t.classList.toggle('active', t.dataset.tab === matchTab));
      pane.innerHTML = matchTab === 'events' ? eventsHtml(f) : matchTab === 'stats' ? statsHtml(f) : lineupsHtml(f);
    };
    $$('.tab', body).forEach((t) => (t.onclick = () => { matchTab = t.dataset.tab; draw(); }));
    draw();
  }

  const ICONS = {
    goal: '<span class="ic goal"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="m12 7 4 3-1.5 5h-5L8 10z"/></svg></span>',
    sub: '<span class="ic sub"><svg viewBox="0 0 24 24"><path d="M7 4v14M3 14l4 4 4-4M17 20V6M13 10l4-4 4 4"/></svg></span>',
  };
  function eventsHtml(f) {
    const ev = f.events || [];
    if (!ev.length) return `<div class="note">${f.fixture.status.short === 'NS' ? 'الماتش لسه مابدأش.' : 'مفيش أحداث متسجلة.'}</div>`;
    let html = '';
    let shownHT = false;
    for (const e of ev) {
      if (!shownHT && e.time.elapsed > 45 && !e.time.extra) { html += '<div class="ht-sep">— الشوط التاني —</div>'; shownHT = true; }
      const home = e.team?.id === f.teams.home.id;
      let icon = '';
      let title = e.player?.name || '';
      let sub = '';
      if (e.type === 'Goal') {
        icon = e.detail === 'Missed Penalty' ? '<span class="ic var">X</span>' : ICONS.goal;
        sub = e.detail === 'Own Goal' ? 'في مرماه' : e.detail === 'Penalty' ? 'ضربة جزاء' : e.detail === 'Missed Penalty' ? 'ضيّع ضربة جزاء' : (e.assist?.name ? 'صناعة: ' + e.assist.name : '');
      } else if (e.type === 'Card') {
        icon = `<span class="ic cardi${/red|second/i.test(e.detail) ? ' red' : ''}"><i></i></span>`;
        sub = /second/i.test(e.detail) ? 'إنذار تاني' : /red/i.test(e.detail) ? 'طرد' : 'إنذار';
      } else if (e.type === 'subst') {
        icon = ICONS.sub;
        title = `${e.player?.name || ''} ⇄ ${e.assist?.name || ''}`;
        sub = 'تبديل';
      } else if (e.type === 'Var') {
        icon = '<span class="ic var">VAR</span>';
        sub = e.detail || 'فار';
      } else { sub = e.detail || e.type; }
      const side = `<div class="side${home ? '' : ' away'}">${icon}<div class="who"><b>${esc(title)}</b><small>${esc(sub)}</small></div></div>`;
      html += `<div class="ev">${home ? side : '<div></div>'}<div class="min">${e.time.elapsed}${e.time.extra ? '+' + e.time.extra : ''}'</div>${home ? '<div></div>' : side}</div>`;
    }
    return html;
  }

  function statsHtml(f) {
    const s = f.statistics || [];
    if (s.length < 2) return '<div class="note">مفيش إحصائيات للماتش ده لسه.</div>';
    const [h, a] = s[0].team?.id === f.teams.home.id ? s : [s[1], s[0]];
    const aMap = new Map(a.statistics.map((x) => [x.type, x.value]));
    let html = '';
    for (const { type, value } of h.statistics) {
      const av = aMap.get(type);
      if (value == null && av == null) continue;
      const n = (v) => parseFloat(String(v ?? 0).replace('%', '')) || 0;
      const hv = n(value);
      const awv = n(av);
      const tot = hv + awv || 1;
      html += `<div class="stat"><div class="stat-top"><span>${esc(value ?? 0)}</span><span>${esc(AR_STAT[type] || type)}</span><span>${esc(av ?? 0)}</span></div>
        <div class="bars"><div class="h"><i style="width:${(hv / tot) * 100}%"></i></div><div class="a"><i style="width:${(awv / tot) * 100}%"></i></div></div></div>`;
    }
    return html || '<div class="note">مفيش إحصائيات.</div>';
  }

  function lineupsHtml(f) {
    const L = f.lineups || [];
    if (!L.length) return '<div class="note">التشكيل بيتعلن عادةً قبل الماتش بساعة تقريبًا.</div>';
    const ordered = L[0].team?.id === f.teams.home.id ? L : [...L].reverse();
    const pl = (p) => `<div class="pl"><span class="no">${esc(p.player.number ?? '')}</span><span>${esc(p.player.name)}</span></div>`;
    return `<div class="lineups">${ordered.map((t) => `<div class="lu"><h4>${esc(t.team.name)}</h4>
      <div class="meta">${t.formation ? 'خطة ' + esc(t.formation) : ''}${t.coach?.name ? ' • المدرب: ' + esc(t.coach.name) : ''}</div>
      ${(t.startXI || []).map(pl).join('')}
      ${(t.substitutes || []).length ? '<div class="sub-title">الاحتياطي</div>' + t.substitutes.map(pl).join('') : ''}</div>`).join('')}</div>`;
  }

  function closeMatch() {
    $('#match').classList.add('hidden');
    state.openId = null;
    clearTimeout(state.matchTimer);
  }
  $('#match').addEventListener('click', (e) => { if (e.target.id === 'match' || e.target.closest('[data-close]')) closeMatch(); });

  // =====================================================================
  //                               الترتيب
  // =====================================================================
  function renderTableChips() {
    const box = $('#table-leagues');
    box.innerHTML = '';
    if (!cfg.leagues.some((l) => l.id === cfg.tableLeague)) cfg.tableLeague = cfg.leagues[0]?.id;
    for (const l of cfg.leagues) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'chip' + (l.id === cfg.tableLeague ? ' active' : '');
      b.innerHTML = imgTag(l.logo) + `<span>${esc(leagueName(l))}</span>`;
      b.onclick = () => { cfg.tableLeague = l.id; saveCfg(); renderTableChips(); loadTable(); };
      box.appendChild(b);
    }
    $('#table-leagues .chip.active')?.scrollIntoView({ inline: 'center', block: 'nearest' });
  }

  async function loadTable(force = false) {
    const id = cfg.tableLeague;
    const box = $('#table');
    if (!id) { box.innerHTML = '<div class="empty"><h4>مفيش دوريات</h4><div>ضيف دوري من الإعدادات.</div></div>'; return; }
    box.innerHTML = '<div class="skeleton" style="margin:12px"></div>'.repeat(5);
    $('#refresh').classList.add('spin');
    try {
      const season = await cached('season.' + id, 7 * 86400000, async () => {
        const r = await api('leagues', { id, current: 'true' });
        const s = r[0]?.seasons?.find((x) => x.current) || r[0]?.seasons?.slice(-1)[0];
        return s ? s.year : null;
      });
      if (!season) throw new ApiErr('مالقيتش موسم حالي للبطولة دي.');
      const data = await cached(`table.${id}.${season}`, 3 * 3600000, () => api('standings', { league: id, season }), { force });
      if (cfg.tableLeague !== id) return;
      renderTable(data, season);
    } catch (e) {
      if (cfg.tableLeague !== id) return;
      if (e.stale) { renderTable(e.stale); toast(e.message); }
      else box.innerHTML = `<div class="empty"><h4>حصلت مشكلة</h4><div style="white-space:pre-line">${esc(e.message)}</div></div>`;
    } finally {
      $('#refresh').classList.remove('spin');
    }
  }

  function zoneColor(desc) {
    if (!desc) return '';
    if (/relegation/i.test(desc)) return 'var(--live)';
    if (/champions league|promotion|libertadores|next round|1\/8|round of 16/i.test(desc)) return 'var(--green)';
    return 'var(--blue)';
  }

  function renderTable(data, season) {
    const box = $('#table');
    const groups = data?.[0]?.league?.standings || [];
    if (!groups.length) { box.innerHTML = '<div class="empty"><h4>مفيش جدول ترتيب</h4><div>البطولة دي ممكن تكون بنظام خروج المغلوب.</div></div>'; return; }
    const T = teamIds();
    const legend = new Map();
    let html = '';
    for (const g of groups) {
      if (groups.length > 1) html += `<div class="group-title">${esc(String(g[0]?.group || '').replace('Group', 'مجموعة'))}</div>`;
      html += `<table><thead><tr><th>#</th><th class="tm">الفريق</th><th>لعب</th><th>ف</th><th>ت</th><th>خ</th><th>+/-</th><th>نقاط</th><th class="f">آخر 5</th></tr></thead><tbody>`;
      for (const r of g) {
        const c = zoneColor(r.description);
        if (c) legend.set(r.description, c);
        const form = String(r.form || '').slice(-5).split('').map((x) => `<i class="${x}">${x === 'W' ? 'ف' : x === 'D' ? 'ت' : 'خ'}</i>`).join('');
        html += `<tr class="${T.has(r.team.id) ? 'fav' : ''}"><td class="rk" style="--q:${c || 'transparent'}">${r.rank}</td>
          <td class="tm"><div>${imgTag(r.team.logo)}<span>${esc(r.team.name)}</span></div></td>
          <td>${r.all.played}</td><td>${r.all.win}</td><td>${r.all.draw}</td><td>${r.all.lose}</td>
          <td dir="ltr">${r.goalsDiff > 0 ? '+' : ''}${r.goalsDiff}</td><td class="pts">${r.points}</td><td class="f"><span class="form" dir="ltr">${form}</span></td></tr>`;
      }
      html += '</tbody></table>';
    }
    if (legend.size) html += `<div class="legend">${[...legend].map(([d, c]) => `<span style="--c:${c}">${esc(d)}</span>`).join('')}</div>`;
    if (season) html += `<div class="legend">موسم ${season}/${season + 1}</div>`;
    box.innerHTML = html;
  }

  // =====================================================================
  //                              الإعدادات
  // =====================================================================
  function renderSettings() {
    $('#welcome').classList.toggle('hidden', !!cfg.key);
    $('#key').value = cfg.key;
    $$('#interval button').forEach((b) => b.classList.toggle('active', Number(b.dataset.v) === cfg.interval));
    renderLeagues();
    renderTeams();
  }

  $('#key-show').onclick = () => { const i = $('#key'); i.type = i.type === 'password' ? 'text' : 'password'; };
  $('#key-save').onclick = async () => {
    const box = $('#key-status');
    const btn = $('#key-save');
    cfg.key = $('#key').value.trim();
    saveCfg();
    box.className = 'status-box';
    box.textContent = 'بختبر المفتاح...';
    btn.disabled = true;
    try {
      const s = await api('status');
      const sub = s.subscription || {};
      const req = s.requests || {};
      if (req.limit_day != null) setQuota(req.limit_day - (req.current || 0), req.limit_day);
      box.className = 'status-box ok';
      box.innerHTML = `المفتاح شغال ✓<br>الخطة: <b>${esc(sub.plan || '—')}</b>${sub.end ? ` • لحد ${esc(String(sub.end).slice(0, 10))}` : ''}<br>استخدمت النهارده <b>${esc(req.current ?? 0)}</b> من <b>${esc(req.limit_day ?? '?')}</b> طلب`;
      $('#welcome').classList.add('hidden');
      state.days.clear();
      mem.clear();
    } catch (e) {
      box.className = 'status-box err';
      box.textContent = e.message;
    } finally {
      btn.disabled = false;
    }
  };

  function renderLeagues() {
    const box = $('#my-leagues');
    box.innerHTML = '';
    cfg.leagues.forEach((l, i) => {
      const row = document.createElement('div');
      row.className = 'row';
      row.innerHTML = `${imgTag(l.logo)}<div class="tx"><b>${esc(leagueName(l))}</b><small>${esc(countryName(l.country))}</small></div>
        ${i > 0 ? '<button type="button" class="act" data-up aria-label="لفوق">↑</button>' : ''}<button type="button" class="act" data-rm>شيل</button>`;
      $('[data-rm]', row).onclick = () => { cfg.leagues = cfg.leagues.filter((x) => x.id !== l.id); saveCfg(); renderLeagues(); };
      const up = $('[data-up]', row);
      if (up) up.onclick = () => { const a = cfg.leagues; [a[i - 1], a[i]] = [a[i], a[i - 1]]; saveCfg(); renderLeagues(); };
      box.appendChild(row);
    });
  }

  $('#league-search').addEventListener('input', debounce(async (e) => {
    const q = e.target.value.trim();
    const box = $('#league-results');
    if (q.length < 3) { box.innerHTML = ''; return; }
    box.innerHTML = '<div class="hint">بدوّر...</div>';
    try {
      const res = await cached('lsearch.' + q.toLowerCase(), 7 * 86400000, () => api('leagues', { search: q }));
      if (e.target.value.trim() !== q) return;
      const mine = leagueIds();
      box.innerHTML = '';
      if (!res.length) { box.innerHTML = '<div class="hint">مالقيتش حاجة. جرّب اسم الدولة أو الدوري بالإنجليزي.</div>'; return; }
      for (const r of res.slice(0, 25)) {
        const l = { id: r.league.id, name: r.league.name, country: r.country?.name, logo: r.league.logo };
        const row = document.createElement('div');
        row.className = 'row';
        row.innerHTML = `${imgTag(l.logo)}<div class="tx"><b>${esc(leagueName(l))}</b><small>${esc(countryName(l.country))} • ${r.league.type === 'Cup' ? 'كأس' : 'دوري'}</small></div>
          <button type="button" class="act${mine.has(l.id) ? '' : ' add'}">${mine.has(l.id) ? 'موجود' : 'ضيف'}</button>`;
        const b = $('.act', row);
        if (!mine.has(l.id)) b.onclick = () => { cfg.leagues.push(l); saveCfg(); renderLeagues(); b.textContent = 'موجود'; b.classList.remove('add'); b.onclick = null; toast('اتضاف ' + leagueName(l)); };
        box.appendChild(row);
      }
    } catch (err) {
      box.innerHTML = `<div class="hint" style="white-space:pre-line">${esc(err.message)}</div>`;
    }
  }, 600));

  function renderTeams() {
    const box = $('#my-teams');
    box.innerHTML = '';
    for (const t of cfg.teams) {
      const row = document.createElement('div');
      row.className = 'row';
      row.innerHTML = `${imgTag(t.logo)}<div class="tx"><b>${esc(t.name)}</b></div><button type="button" class="act">شيل</button>`;
      $('.act', row).onclick = () => { cfg.teams = cfg.teams.filter((x) => x.id !== t.id); saveCfg(); renderTeams(); };
      box.appendChild(row);
    }
  }

  $$('#interval button').forEach((b) => {
    b.onclick = () => { cfg.interval = Number(b.dataset.v); saveCfg(); renderSettings(); };
  });

  // =====================================================================
  //                               التنقل
  // =====================================================================
  function go(view) {
    state.view = view;
    $$('#nav .nav-btn').forEach((b) => b.classList.toggle('active', b.dataset.view === view));
    $('#view-matches').classList.toggle('hidden', view !== 'matches');
    $('#view-table').classList.toggle('hidden', view !== 'table');
    $('#view-settings').classList.toggle('hidden', view !== 'settings');
    $('#refresh').classList.toggle('hidden', view === 'settings');
    window.scrollTo(0, 0);
    clearTimeout(state.liveTimer);
    if (view === 'matches') { renderDates(); loadDay(state.date); }
    if (view === 'table') { renderTableChips(); loadTable(); }
    if (view === 'settings') renderSettings();
  }
  $$('#nav .nav-btn').forEach((b) => (b.onclick = () => go(b.dataset.view)));

  // زرار الرجوع في الأندرويد
  window.__back = () => {
    if (!$('#match').classList.contains('hidden')) { closeMatch(); return true; }
    if (state.view !== 'matches') { go('matches'); return true; }
    return false;
  };
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') window.__back(); });

  // يوم جديد؟ نرجّع لـ "النهارده"
  setInterval(() => { if (state.date < today() && state.view === 'matches' && $('#dates .date.active b')?.textContent === 'النهارده') { state.date = today(); renderDates(); loadDay(state.date); } }, 60000);

  renderQuota();
  go(cfg.key ? 'matches' : 'settings');
})();
