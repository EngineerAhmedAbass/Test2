/* GOAL — نتايج الماتشات
 * مصدرين للبيانات:
 *  - ESPN (مجاني، من غير مفتاح، أي يوم): كل الدوريات ما عدا الدوري المصري.
 *  - API-Football (مفتاح مجاني، 100 طلب/يوم، امبارح/النهارده/بكرة بس): الدوري المصري.
 * الاتنين بيتحولوا لنفس الشكل (compact) والواجهة مش فارق معاها المصدر.
 */
(() => {
  'use strict';

  const $ = (s, r = document) => r.querySelector(s);
  const $$ = (s, r = document) => [...r.querySelectorAll(s)];
  const TZ = Intl.DateTimeFormat().resolvedOptions().timeZone || 'Africa/Cairo';
  const ESPN = 'https://site.api.espn.com/apis';

  // دوريات ESPN المتاحة (اتأكدت إنها شغالة)
  const ESPN_CATALOG = [
    ['caf.champions', 'دوري أبطال أفريقيا'], ['caf.confed', 'الكونفدرالية الأفريقية'], ['uefa.champions', 'دوري أبطال أوروبا'],
    ['eng.1', 'الدوري الإنجليزي'], ['esp.1', 'الدوري الإسباني'], ['ita.1', 'الدوري الإيطالي'], ['ger.1', 'الدوري الألماني'],
    ['fra.1', 'الدوري الفرنسي'], ['ksa.1', 'الدوري السعودي'], ['uefa.europa', 'الدوري الأوروبي'], ['uefa.europa.conf', 'دوري المؤتمر الأوروبي'],
    ['afc.champions', 'دوري أبطال آسيا'], ['caf.nations', 'كأس أمم أفريقيا'], ['fifa.world', 'كأس العالم'], ['fifa.worldq.caf', 'تصفيات كأس العالم — أفريقيا'],
    ['fifa.cwc', 'كأس العالم للأندية'], ['fifa.friendly', 'مباريات ودية دولية'], ['eng.fa', 'كأس الاتحاد الإنجليزي'], ['eng.league_cup', 'كأس الرابطة الإنجليزية'],
    ['esp.copa_del_rey', 'كأس ملك إسبانيا'], ['ita.coppa_italia', 'كأس إيطاليا'], ['eng.2', 'التشامبيونشيب'], ['ned.1', 'الدوري الهولندي'],
    ['por.1', 'الدوري البرتغالي'], ['tur.1', 'الدوري التركي'], ['sco.1', 'الدوري الاسكتلندي'], ['bel.1', 'الدوري البلجيكي'],
    ['usa.1', 'الدوري الأمريكي'], ['bra.1', 'الدوري البرازيلي'], ['arg.1', 'الدوري الأرجنتيني'], ['mex.1', 'الدوري المكسيكي'], ['rsa.1', 'دوري جنوب أفريقيا'],
  ];
  const ESPN_AR = Object.fromEntries(ESPN_CATALOG);
  const AF_AR = { 233: 'الدوري المصري', 714: 'كأس مصر', 539: 'كأس الرابطة المصرية', 12: 'دوري أبطال أفريقيا', 20: 'الكونفدرالية الأفريقية' };
  const DEFAULT_LEAGUES = [
    { src: 'af', id: 233, name: 'Premier League', country: 'Egypt', logo: '' },
    ...['caf.champions', 'caf.confed', 'uefa.champions', 'eng.1', 'esp.1', 'ita.1', 'ger.1', 'fra.1', 'ksa.1'].map((slug) => ({ src: 'es', slug })),
  ];

  const LIVE = new Set(['1H', 'HT', '2H', 'ET', 'BT', 'P', 'SUSP', 'INT', 'LIVE']);
  const DONE = new Set(['FT', 'AET', 'PEN', 'AWD', 'WO']);
  const ST_TEXT = { HT: 'استراحة', BT: 'استراحة', P: 'ركلات ترجيح', FT: 'انتهت', AET: 'بعد الإضافي', PEN: 'بالترجيح', PST: 'اتأجلت', CANC: 'اتلغت', ABD: 'اتوقفت', SUSP: 'معلّقة', INT: 'متوقفة', TBD: 'لم يتحدد', AWD: 'قرار إداري', WO: 'انسحاب' };

  // =====================================================================
  //                         الإعدادات والتخزين
  // =====================================================================
  const ls = {
    get(k, d) { try { const v = localStorage.getItem(k); return v ? JSON.parse(v) : d; } catch { return d; } },
    set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch { return false; } },
  };
  const cfg = Object.assign({ v: 2, key: '', leagues: DEFAULT_LEAGUES, teams: [], interval: 60, tableLeague: null }, ls.get('goal.cfg', {}));
  if (cfg.v !== 2) { // ترحيل من النسخة الأولى (API-Football بس)
    cfg.v = 2;
    cfg.leagues = DEFAULT_LEAGUES;
    cfg.teams = (cfg.teams || []).map((t) => ({ ...t, key: t.key || 'af:' + t.id }));
    cfg.interval = 60;
    cfg.tableLeague = null;
  }
  const saveCfg = () => ls.set('goal.cfg', cfg);
  saveCfg();
  const lkey = (l) => (l.src === 'es' ? 'es:' + l.slug : 'af:' + l.id);
  const myLeagueKeys = () => new Set(cfg.leagues.map(lkey));
  const teamKeys = () => new Set(cfg.teams.map((t) => t.key));

  const state = {
    view: 'matches',
    date: dayKey(new Date()),
    filter: ls.get('goal.filter', 'mine'),
    days: new Map(), // date -> {t, list, notes}
    quota: ls.get('goal.quota', null),
    timers: {},
    lastEs: 0,
    lastAf: 0,
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
  const imgTag = (src) => (src ? `<img src="${esc(src)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.style.visibility='hidden'">` : '');
  const fmtDay = (k) => { const [y, m, d] = k.split('-').map(Number); return new Date(y, m - 1, d).toLocaleDateString('ar-EG', { weekday: 'long', day: 'numeric', month: 'numeric' }); };
  function leagueName(l) {
    if (l.src === 'es' || l.slug) return ESPN_AR[l.slug] || l.name || l.slug;
    return AF_AR[l.id] || l.name;
  }
  function toast(text, cls = '') {
    const el = document.createElement('div');
    el.className = 'toast ' + cls;
    el.textContent = text;
    $('#toasts').appendChild(el);
    setTimeout(() => el.remove(), cls === 'goal' ? 5000 : 2800);
  }
  async function pool(items, n, fn) { // تشغيل طلبات كتير بالتوازي بحد أقصى n
    const out = new Array(items.length);
    let i = 0;
    await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => { while (i < items.length) { const k = i++; out[k] = await fn(items[k]); } }));
    return out;
  }

  class ApiErr extends Error { constructor(msg, kind) { super(msg); this.kind = kind || 'api'; } }

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
      if (hit && e.kind !== 'key' && e.kind !== 'nokey') e.stale = hit.d;
      throw e;
    }
  }
  try { // تنضيف الكاش الأقدم من 4 أيام
    const old = Date.now() - 4 * 86400000;
    for (let i = localStorage.length - 1; i >= 0; i--) {
      const k = localStorage.key(i);
      if (k && k.startsWith('goal.c.')) { const v = ls.get(k, null); if (!v || v.t < old) localStorage.removeItem(k); }
    }
  } catch {}

  // =====================================================================
  //                         مصدر 1: ESPN
  // =====================================================================
  async function espn(path) {
    let r;
    try { r = await fetch(`${ESPN}/${path}`); } catch { throw new ApiErr('مفيش اتصال بالنت.', 'net'); }
    if (!r.ok) throw new ApiErr(`ESPN رد بخطأ ${r.status}.`, 'net');
    return r.json();
  }
  // ESPN بيقسّم الأيام بتوقيت أمريكا الشرقي، فيوم عندك ممكن يكون موزّع على يومين عندهم
  const etDate = (ms) => new Intl.DateTimeFormat('en-CA', { timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit' }).format(ms).replace(/-/g, '');
  function espnDatesFor(date) {
    const [y, m, d] = date.split('-').map(Number);
    const start = new Date(y, m - 1, d).getTime();
    const end = new Date(y, m - 1, d + 1).getTime() - 1;
    return [...new Set([etDate(start), etDate(end)])];
  }
  function esStatus(st) {
    const t = st?.type || {};
    const n = t.name || '';
    if (t.state === 'pre') return /POSTPONED/.test(n) ? 'PST' : /CANCEL/.test(n) ? 'CANC' : 'NS';
    if (t.state === 'in') return /HALFTIME/.test(n) ? 'HT' : /SHOOTOUT|PENALT/.test(n) ? 'P' : 'LIVE';
    if (/PEN/.test(n)) return 'PEN';
    if (/AET|EXTRA/.test(n)) return 'AET';
    if (/ABANDON/.test(n)) return 'ABD';
    if (/POSTPONED/.test(n)) return 'PST';
    if (/CANCEL/.test(n)) return 'CANC';
    return 'FT';
  }
  function esCompact(ev, slug, leagueInfo) {
    const c = ev.competitions?.[0] || ev;
    const st = ev.status || c.status;
    const code = esStatus(st);
    const side = (ha) => (c.competitors || []).find((x) => x.homeAway === ha) || {};
    const team = (x) => ({
      key: 'es:' + x.team?.id, name: x.team?.shortDisplayName || x.team?.displayName || '',
      logo: x.team?.logo || x.team?.logos?.[0]?.href || '', w: x.winner === true ? true : x.winner === false && code !== 'NS' ? false : null,
    });
    const h = side('home');
    const a = side('away');
    const started = code !== 'NS' && code !== 'PST' && code !== 'CANC';
    const venue = c.venue || ev.venue || {};
    return {
      id: `es:${slug}:${ev.id}`, src: 'es', eid: ev.id, slug, ts: Date.parse(ev.date || c.date), st: code,
      clock: code === 'LIVE' ? (st?.displayClock || '').replace(/\s/g, '') : '',
      ven: venue.fullName || '', city: venue.address?.city || '',
      lg: { key: 'es:' + slug, slug, name: leagueInfo?.name || slug, logo: leagueInfo?.logos?.[0]?.href || '', country: '', round: c.notes?.[0]?.headline || '' },
      h: team(h), a: team(a),
      g: started ? [Number(h.score ?? 0), Number(a.score ?? 0)] : [null, null],
      pen: h.shootoutScore != null ? [Number(h.shootoutScore), Number(a.shootoutScore)] : [null, null],
    };
  }
  async function espnDay(slug, date, force) {
    const lists = await Promise.all(espnDatesFor(date).map((d) =>
      cached(`es.${slug}.${d}`, ttlFor(date), async () => {
        const j = await espn(`site/v2/sports/soccer/${slug}/scoreboard?dates=${d}`);
        const L = j.leagues?.[0];
        return (j.events || []).map((ev) => esCompact(ev, slug, L));
      }, { force })));
    return lists.flat().filter((f) => dayKey(new Date(f.ts)) === date);
  }

  // =====================================================================
  //                      مصدر 2: API-Football
  // =====================================================================
  function afError(j) {
    const e = j && j.errors;
    if (!e || (Array.isArray(e) && !e.length) || (typeof e === 'object' && !Object.keys(e).length)) return null;
    const [k, v] = Array.isArray(e) ? ['', typeof e[0] === 'string' ? e[0] : JSON.stringify(e[0])] : Object.entries(e)[0];
    const raw = String(v);
    if (k === 'token' || /application key|api key|not subscribed|account/i.test(raw)) return new ApiErr('مفتاح API-Football غلط أو الحساب مش متفعّل — راجعه في الإعدادات.\n(' + raw + ')', 'key');
    if (k === 'requests' || /request limit|limit for the day/i.test(raw)) { setQuota(0); return new ApiErr('خلصت طلبات API-Football النهارده. هتتجدد بكرة.', 'quota'); }
    if (k === 'rateLimit' || /too many requests/i.test(raw)) return new ApiErr('طلبات كتير ورا بعض — استنى دقيقة.', 'rate');
    if (k === 'plan' || /plan/i.test(raw)) {
      const days = raw.match(/from (\d{4}-\d{2}-\d{2}) to (\d{4}-\d{2}-\d{2})/);
      if (days) { setRange(days[1], days[2]); return new ApiErr(`الخطة المجانية بتعرض ${fmtDay(days[1])} لحد ${fmtDay(days[2])} بس.`, 'range'); }
      const seasons = raw.match(/from (\d{4}) to (\d{4})/);
      const er = new ApiErr('الخطة المجانية مش بتغطي الطلب ده:\n' + raw, 'plan');
      if (seasons) er.seasons = [Number(seasons[1]), Number(seasons[2])];
      return er;
    }
    return new ApiErr(raw, 'api');
  }
  function setRange(from, to) { ls.set('goal.range', { from, to, t: Date.now() }); }
  function afLocked(date) {
    const r = ls.get('goal.range', null);
    if (!r || Date.now() - r.t > 8 * 3600000) return false; // المدى بيتحرك كل يوم، فبنصدّقه كام ساعة بس
    return date < r.from || date > r.to;
  }
  function setQuota(rem, lim) {
    state.quota = { rem: Number(rem), lim: Number(lim || state.quota?.lim || 100), day: today() };
    ls.set('goal.quota', state.quota);
    renderQuota();
  }
  function renderQuota() {
    const q = state.quota;
    const el = $('#quota');
    if (!cfg.key || !q || q.day !== today()) { el.classList.add('hidden'); return; }
    el.classList.remove('hidden');
    el.textContent = `${q.rem} / ${q.lim} طلب`;
    el.title = 'طلبات API-Football الباقية النهارده (للدوري المصري)';
    el.classList.toggle('low', q.rem > 0 && q.rem < 25);
    el.classList.toggle('out', q.rem <= 0);
  }

  const inflight = new Map();
  function af(ep, params = {}) {
    const qs = new URLSearchParams(params).toString();
    const ck = ep + '?' + qs;
    if (inflight.has(ck)) return inflight.get(ck);
    const p = (async () => {
      if (!cfg.key) throw new ApiErr('محتاج مفتاح API-Football من الإعدادات.', 'nokey');
      let r;
      try { r = await fetch(`/api/football/${ep}?${qs}${qs ? '&' : ''}_k=${encodeURIComponent(cfg.key)}`); }
      catch { throw new ApiErr('مفيش اتصال بالنت.', 'net'); }
      const rem = r.headers.get('x-ratelimit-requests-remaining');
      if (rem != null && rem !== '') setQuota(rem, r.headers.get('x-ratelimit-requests-limit'));
      let j;
      try { j = await r.json(); } catch { throw new ApiErr(`رد غير متوقع من API-Football (${r.status}).`, 'net'); }
      const err = afError(j);
      if (err) throw err;
      if (!r.ok) throw new ApiErr(`API-Football رد بخطأ ${r.status}.`, 'net');
      return j.response;
    })();
    inflight.set(ck, p);
    p.then(() => inflight.delete(ck), () => inflight.delete(ck));
    return p;
  }
  const afCompact = (f) => ({
    id: 'af:' + f.fixture.id, src: 'af', fid: f.fixture.id, ts: f.fixture.timestamp * 1000, st: f.fixture.status.short,
    el: f.fixture.status.elapsed, ex: f.fixture.status.extra ?? null, clock: '',
    ven: f.fixture.venue?.name || '', city: f.fixture.venue?.city || '', ref: f.fixture.referee || '',
    lg: { key: 'af:' + f.league.id, id: f.league.id, name: f.league.name, country: f.league.country, logo: f.league.logo, round: f.league.round || '' },
    h: { key: 'af:' + f.teams.home.id, name: f.teams.home.name, logo: f.teams.home.logo, w: f.teams.home.winner },
    a: { key: 'af:' + f.teams.away.id, name: f.teams.away.name, logo: f.teams.away.logo, w: f.teams.away.winner },
    g: [f.goals.home, f.goals.away],
    pen: [f.score?.penalty?.home ?? null, f.score?.penalty?.away ?? null],
  });
  function afDay(date, force) {
    return cached('af.day.' + date, ttlFor(date), async () => (await af('fixtures', { date, timezone: TZ })).map(afCompact), { force });
  }

  function ttlFor(date) {
    const t = today();
    if (date < t) return 3 * 86400000;
    if (date > t) return 60 * 60000;
    return 5 * 60000;
  }

  // =====================================================================
  //                             المباريات
  // =====================================================================
  const espnLeaguesNeeded = () => (state.filter === 'mine'
    ? cfg.leagues.filter((l) => l.src === 'es').map((l) => l.slug)
    : ESPN_CATALOG.map(([s]) => s));
  const afNeeded = () => !!cfg.key && (state.filter !== 'mine' || cfg.leagues.some((l) => l.src === 'af') || cfg.teams.some((t) => t.key.startsWith('af:')));

  async function loadDay(date, force = false) {
    const box = $('#matches');
    if (!state.days.has(date)) box.innerHTML = '<div class="skeleton"></div>'.repeat(6);
    $('#refresh').classList.add('spin');
    const notes = [];
    const errors = [];
    const slugs = espnLeaguesNeeded();
    const esPart = pool(slugs, 6, (slug) => espnDay(slug, date, force).catch((e) => { errors.push(e); return e.stale ? e.stale.filter((f) => dayKey(new Date(f.ts)) === date) : []; }));
    let afPart = Promise.resolve([]);
    if (afNeeded()) {
      if (afLocked(date)) notes.push({ kind: 'range' });
      else afPart = afDay(date, force).catch((e) => {
        notes.push(e.kind === 'range' ? { kind: 'range' } : { kind: 'af', msg: e.message });
        return e.stale || [];
      });
    }
    const [esLists, afList] = await Promise.all([esPart, afPart]);
    const list = [...esLists.flat(), ...afList];
    state.days.set(date, { t: Date.now(), list, notes });
    if (date === today()) { state.lastEs = Date.now(); state.lastAf = Date.now(); }
    $('#refresh').classList.remove('spin');
    if (state.date !== date) return;
    if (!list.length && slugs.length && errors.length === slugs.length) { showError(box, errors[0]); return; }
    if (errors.length) toast(`${errors.length} دوري ماتحمّلش — ${errors[0].message}`);
    renderMatches();
    scheduleLive();
  }

  function showError(box, e) {
    box.innerHTML = `<div class="empty"><h4>حصلت مشكلة</h4><div style="white-space:pre-line">${esc(e.message)}</div><button class="btn" data-retry type="button">جرّب تاني</button></div>`;
    $('[data-retry]', box).onclick = () => loadDay(state.date, true);
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
      const prev = state.filter;
      state.filter = c.dataset.filter;
      ls.set('goal.filter', state.filter);
      $$('.filters .chip').forEach((x) => x.classList.toggle('active', x === c));
      if (prev === 'mine' && state.filter !== 'mine') loadDay(state.date); // محتاجين دوريات أكتر
      else { renderMatches(); scheduleLive(); }
    };
  });

  function visibleFixtures() {
    const day = state.days.get(state.date);
    if (!day) return [];
    const L = myLeagueKeys();
    const T = teamKeys();
    if (state.filter === 'live') return day.list.filter((f) => LIVE.has(f.st));
    if (state.filter === 'mine') return day.list.filter((f) => L.has(f.lg.key) || T.has(f.h.key) || T.has(f.a.key));
    return day.list;
  }

  function renderMatches() {
    const box = $('#matches');
    const day = state.days.get(state.date);
    if (!day) return;
    const list = visibleFixtures();
    $('#updated').textContent = state.date === today() ? `اتحدّث ${hhmm(Math.max(day.t, state.lastEs))}` : '';
    const order = new Map(cfg.leagues.map((l, i) => [lkey(l), i]));
    const groups = new Map();
    for (const f of list) {
      if (!groups.has(f.lg.key)) groups.set(f.lg.key, { lg: f.lg, items: [] });
      groups.get(f.lg.key).items.push(f);
    }
    // ملاحظة الدوري المصري لو الخطة المجانية مش بتغطي اليوم ده
    const afLeague = cfg.leagues.find((l) => l.src === 'af');
    const notesHtml = day.notes.length && afLeague && state.filter !== 'live'
      ? day.notes.map((n) => `<section class="league"><div class="league-head">${imgTag(afLeague.logo)}<div><div class="nm">${esc(leagueName(afLeague))}</div></div></div>
          <div class="league-note">${n.kind === 'range' ? 'الخطة المجانية بتاعة API-Football بتعرض الدوري المصري لامبارح والنهارده وبكرة بس.' : esc(n.msg)}</div></section>`).join('')
      : '';
    if (!list.length) {
      const msg = state.filter === 'live' ? 'مفيش ماتشات شغالة دلوقتي' : state.filter === 'mine' ? 'مفيش ماتشات لدورياتك في اليوم ده' : 'مفيش ماتشات في اليوم ده';
      box.innerHTML = notesHtml + `<div class="empty"><h4>${msg}</h4>${state.filter === 'mine' ? '<button class="btn" data-all type="button">اعرض كل الدوريات</button>' : ''}</div>`;
      $('[data-all]', box)?.addEventListener('click', () => $('.filters .chip[data-filter=all]').click());
      return;
    }
    const sorted = [...groups.values()].sort((a, b) => {
      const oa = order.has(a.lg.key) ? order.get(a.lg.key) : 1e6;
      const ob = order.has(b.lg.key) ? order.get(b.lg.key) : 1e6;
      return oa - ob || leagueName(a.lg).localeCompare(leagueName(b.lg));
    });
    const T = teamKeys();
    let html = notesHtml;
    for (const g of sorted) {
      g.items.sort((a, b) => a.ts - b.ts || String(a.id).localeCompare(String(b.id)));
      const rounds = [...new Set(g.items.map((f) => f.lg.round))];
      html += `<section class="league"><div class="league-head">${imgTag(g.lg.logo)}<div><div class="nm">${esc(leagueName(g.lg))}</div>${g.lg.country ? `<div class="ct">${esc(g.lg.country)}</div>` : ''}</div>
        <span class="round">${rounds.length === 1 ? esc(roundName(rounds[0])) : ''}</span></div>`;
      for (const f of g.items) html += matchRow(f, T.has(f.h.key) || T.has(f.a.key));
      html += '</section>';
    }
    box.innerHTML = html;
    $$('.match', box).forEach((b) => (b.onclick = () => openMatch(b.dataset.id)));
  }

  function roundName(r) {
    return String(r || '').replace(/Regular Season - (\d+)/, 'الجولة $1').replace(/Group Stage - (\d+)/, 'دور المجموعات — الجولة $1')
      .replace('Round of 16', 'دور الـ16').replace('Quarter-finals', 'ربع النهائي').replace('Semi-finals', 'نص النهائي').replace(/^Final$/, 'النهائي');
  }
  function statusText(f) {
    if (f.st === 'LIVE' && f.clock) return `<span dir="ltr">${esc(f.clock)}</span>`;
    if (LIVE.has(f.st) && !ST_TEXT[f.st]) return f.el != null ? `<span dir="ltr">${f.el}${f.ex ? '+' + f.ex : ''}'</span>` : 'مباشر';
    return ST_TEXT[f.st] || '';
  }
  const hasScore = (f) => f.g[0] != null && f.st !== 'NS' && f.st !== 'PST' && f.st !== 'CANC';
  function matchRow(f, fav) {
    const live = LIVE.has(f.st);
    const mid = hasScore(f)
      ? `<div class="score">${f.g[0]} - ${f.g[1]}</div>${f.pen[0] != null ? `<div class="pen">(${f.pen[0]} - ${f.pen[1]})</div>` : ''}<div class="st">${statusText(f)}</div>`
      : `<div class="time">${hhmm(f.ts)}</div><div class="st">${statusText(f)}</div>`;
    const lose = (t) => (DONE.has(f.st) && t.w === false ? ' lose' : '');
    return `<button type="button" class="match${live ? ' live' : ''}${fav ? ' fav' : ''}" data-id="${esc(f.id)}">
      <div class="team${lose(f.h)}">${imgTag(f.h.logo)}<span>${esc(f.h.name)}</span></div>
      <div class="mid">${mid}</div>
      <div class="team away${lose(f.a)}">${imgTag(f.a.logo)}<span>${esc(f.a.name)}</span></div></button>`;
  }

  // ---------- التحديث المباشر ----------
  function afInterval() {
    if (!cfg.interval) return 0;
    const base = Math.max(cfg.interval, 120);
    const q = state.quota;
    if (q && q.day === today()) {
      if (q.rem <= 3) return 0;
      if (q.rem < 15) return Math.max(base, 600);
      if (q.rem < 35) return Math.max(base, 300);
    }
    return base;
  }
  const liveish = (f, now) => LIVE.has(f.st) || (f.st === 'NS' && f.ts <= now && f.ts > now - 3 * 3600000);

  function scheduleLive() {
    clearTimeout(state.timers.es);
    clearTimeout(state.timers.af);
    if (state.view !== 'matches' || state.date !== today() || !cfg.interval) return;
    if (!state.days.get(today())) return;
    const now = Date.now();
    const rel = visibleFixtures();
    for (const src of ['es', 'af']) {
      const iv = src === 'es' ? cfg.interval : afInterval();
      if (!iv) continue;
      const mine = rel.filter((f) => f.src === src);
      let wait;
      if (mine.some((f) => liveish(f, now))) wait = Math.max(3000, (src === 'es' ? state.lastEs : state.lastAf) + iv * 1000 - now);
      else {
        const next = mine.filter((f) => f.st === 'NS' && f.ts > now).sort((a, b) => a.ts - b.ts)[0];
        if (!next || next.ts - now > 6 * 3600000) continue;
        wait = next.ts - now + 60000;
      }
      state.timers[src] = setTimeout(() => refreshLive(src), wait);
    }
  }

  async function refreshLive(src) {
    if (document.hidden) return;
    const day = state.days.get(today());
    if (!day) return;
    const now = Date.now();
    const before = new Map(day.list.map((f) => [f.id, f]));
    $('#refresh').classList.add('spin');
    try {
      if (src === 'es') {
        const slugs = [...new Set(visibleFixtures().filter((f) => f.src === 'es' && (liveish(f, now) || (f.st === 'NS' && f.ts < now + 120000))).map((f) => f.slug))];
        const fresh = (await pool(slugs, 6, (s) => espnDay(s, today(), true).catch(() => null))).filter(Boolean).flat();
        const map = new Map(fresh.map((f) => [f.id, f]));
        day.list = day.list.map((f) => map.get(f.id) || f);
        state.lastEs = Date.now();
      } else {
        const ids = [...new Set(visibleFixtures().filter((f) => f.src === 'af').map((f) => f.lg.id))].slice(0, 20);
        if (!ids.length) return;
        const resp = await af('fixtures', { live: ids.join('-'), timezone: TZ });
        const map = new Map(resp.map((f) => [`af:${f.fixture.id}`, afCompact(f)]));
        let ended = false;
        day.list = day.list.map((f) => {
          if (f.src !== 'af') return f;
          if (map.has(f.id)) return map.get(f.id);
          if (LIVE.has(f.st) && ids.includes(f.lg.id)) ended = true;
          return f;
        });
        state.lastAf = Date.now();
        if (ended) { // ماتش خلص: نجيب النتيجة النهائية
          const list = await afDay(today(), true).catch(() => null);
          if (list) day.list = [...day.list.filter((f) => f.src !== 'af'), ...list];
        }
      }
      renderMatches();
      announceGoals(before, day.list);
    } catch (e) {
      toast(e.message);
    } finally {
      $('#refresh').classList.remove('spin');
      scheduleLive();
    }
  }

  function announceGoals(before, list) {
    const L = myLeagueKeys();
    const T = teamKeys();
    for (const f of list) {
      const o = before.get(f.id);
      if (!o || o.g[0] == null || f.g[0] == null) continue;
      if (!(f.g[0] > o.g[0] || f.g[1] > o.g[1])) continue;
      if (!L.has(f.lg.key) && !T.has(f.h.key) && !T.has(f.a.key)) continue;
      toast(`جوووول! ${f.h.name} ${f.g[0]} - ${f.g[1]} ${f.a.name}`, 'goal');
      $(`.match[data-id="${CSS.escape(f.id)}"]`)?.classList.add('flash');
      if (navigator.vibrate) navigator.vibrate([120, 60, 120]);
    }
  }

  document.addEventListener('visibilitychange', () => {
    if (document.hidden || state.view !== 'matches' || state.date !== today()) return;
    if (cfg.interval && Date.now() - state.lastEs > cfg.interval * 1000) refreshLive('es'); else scheduleLive();
  });
  $('#refresh').onclick = () => {
    if (state.view === 'matches') loadDay(state.date, true);
    else if (state.view === 'table') loadTable(true);
  };

  // =====================================================================
  //                 تفاصيل الماتش (شكل موحّد للمصدرين)
  // =====================================================================
  const findFixture = (id) => { for (const d of state.days.values()) { const f = d.list.find((x) => x.id === id); if (f) return f; } return null; };

  async function openMatch(id, silent) {
    state.openId = id;
    $('#match').classList.remove('hidden');
    const body = $('#match-body');
    let base = findFixture(id);
    if (!silent) {
      body.innerHTML = (base ? header(base) : '') + '<div class="note">جاري تحميل التفاصيل...</div>';
      bindStars(body);
    }
    const live = base && LIVE.has(base.st);
    const ttl = base && DONE.has(base.st) ? 86400000 : live ? 30000 : 10 * 60000;
    try {
      let detail;
      if (id.startsWith('es:')) {
        const [, slug, eid] = id.split(':');
        const j = await cached(`es.sum.${eid}`, ttl, () => espn(`site/v2/sports/soccer/${slug}/summary?event=${eid}`), { persist: false, force: !!silent });
        detail = espnDetail(j, slug, base);
      } else {
        const fid = id.slice(3);
        const resp = await cached('af.fx.' + fid, ttl, () => af('fixtures', { id: fid }), { persist: !live, force: !!silent });
        if (!resp[0]) throw new ApiErr('الماتش ده مش موجود.');
        detail = afDetail(resp[0]);
      }
      if (state.openId !== id) return;
      renderMatch(detail);
      base = detail.fx;
    } catch (e) {
      if (state.openId !== id) return;
      if (!silent) body.insertAdjacentHTML('beforeend', `<div class="note" style="white-space:pre-line">${esc(e.message)}</div>`);
    }
    clearTimeout(state.matchTimer);
    const iv = id.startsWith('es:') ? cfg.interval : afInterval();
    if (base && LIVE.has(base.st) && iv) state.matchTimer = setTimeout(() => { if (state.openId === id && !document.hidden) openMatch(id, true); }, Math.max(iv, 30) * 1000);
  }

  // ---- ESPN → شكل موحّد ----
  const pctFmt = (v) => { const n = parseFloat(v) || 0; return `${Math.round(n <= 1 ? n * 100 : n)}%`; };
  const ES_STAT = [
    ['possessionPct', 'الاستحواذ', pctFmt], ['totalShots', 'التسديدات'], ['shotsOnTarget', 'على المرمى'],
    ['wonCorners', 'الركنيات'], ['foulsCommitted', 'الأخطاء'], ['offsides', 'التسلل'], ['yellowCards', 'إنذارات'], ['redCards', 'طرد'],
    ['saves', 'تصديات الحارس'], ['totalPasses', 'التمريرات'], ['passPct', 'دقة التمرير', pctFmt],
    ['totalTackles', 'الالتحامات'], ['interceptions', 'قطع الكورة'],
  ];
  function espnDetail(j, slug, base) {
    const comp = j.header?.competitions?.[0];
    const fx = comp
      ? esCompact({ id: comp.id, date: comp.date, competitions: [comp], status: comp.status, venue: j.gameInfo?.venue }, slug, j.header?.league)
      : base;
    if (base) fx.lg = { ...base.lg };
    const homeId = comp?.competitors?.find((c) => c.homeAway === 'home')?.team?.id;
    const events = [];
    for (const k of j.keyEvents || []) {
      const t = k.type?.type || '';
      const side = k.team?.id === homeId ? 'h' : 'a';
      const names = (k.participants || []).map((p) => p.athlete?.displayName).filter(Boolean);
      const min = k.clock?.displayValue || '';
      if (t === 'halftime') { events.push({ sep: 'الاستراحة' }); continue; }
      if (/missed|saved/.test(t)) events.push({ min, side, kind: 'miss', title: names[0] || '', sub: 'ضيّع ضربة جزاء' });
      else if (/goal|penalty---scored/.test(t)) {
        const own = /own/.test(t);
        const pen = /penalty/.test(t);
        events.push({ min, side, kind: 'goal', title: names[0] || '', sub: own ? 'في مرماه' : pen ? 'ضربة جزاء' : names[1] ? 'صناعة: ' + names[1] : '' });
      } else if (t === 'yellow-card') events.push({ min, side, kind: 'yc', title: names[0] || '', sub: 'إنذار' });
      else if (/red-card/.test(t)) events.push({ min, side, kind: 'rc', title: names[0] || '', sub: 'طرد' });
      else if (t === 'substitution') events.push({ min, side, kind: 'sub', title: 'دخل: ' + (names[0] || ''), sub: 'خرج: ' + (names[1] || '') });
    }
    const teams = j.boxscore?.teams || [];
    const ht = teams.find((t) => t.team?.id === homeId) || teams[0];
    const at = teams.find((t) => t !== ht);
    const stats = [];
    if (ht && at) {
      const hm = new Map((ht.statistics || []).map((s) => [s.name, s.displayValue]));
      const am = new Map((at.statistics || []).map((s) => [s.name, s.displayValue]));
      for (const [name, label, fmt] of ES_STAT) {
        if (!hm.has(name) && !am.has(name)) continue;
        const hv = parseFloat(hm.get(name)) || 0;
        const av = parseFloat(am.get(name)) || 0;
        stats.push({ label, h: fmt ? fmt(hm.get(name)) : hm.get(name) ?? 0, a: fmt ? fmt(am.get(name)) : am.get(name) ?? 0, hv, av });
      }
    }
    const lineups = [...(j.rosters || [])].sort((a, b) => (a.homeAway === 'home' ? -1 : b.homeAway === 'home' ? 1 : 0)).map((r) => ({
      name: r.team?.displayName || '', formation: r.formation || '', coach: '',
      xi: (r.roster || []).filter((p) => p.starter).map((p) => ({ no: p.jersey, name: p.athlete?.displayName })),
      subs: (r.roster || []).filter((p) => !p.starter).map((p) => ({ no: p.jersey, name: p.athlete?.displayName })),
    })).filter((r) => r.xi.length);
    return { fx, events, stats, lineups, notStarted: fx?.st === 'NS' };
  }

  // ---- API-Football → شكل موحّد ----
  const AF_STAT = {
    'Ball Possession': 'الاستحواذ', 'Total Shots': 'التسديدات', 'Shots on Goal': 'على المرمى', 'Corner Kicks': 'الركنيات', Fouls: 'الأخطاء',
    Offsides: 'التسلل', 'Yellow Cards': 'إنذارات', 'Red Cards': 'طرد', 'Goalkeeper Saves': 'تصديات الحارس', 'Total passes': 'التمريرات',
    'Passes %': 'دقة التمرير', expected_goals: 'الأهداف المتوقعة (xG)', 'Shots off Goal': 'خارج المرمى', 'Blocked Shots': 'تسديدات اتصدت',
  };
  function afDetail(f) {
    const fx = afCompact(f);
    const homeId = f.teams.home.id;
    const events = [];
    let ht = false;
    for (const e of f.events || []) {
      if (!ht && e.time.elapsed > 45 && !e.time.extra) { events.push({ sep: 'الشوط التاني' }); ht = true; }
      const side = e.team?.id === homeId ? 'h' : 'a';
      const min = `${e.time.elapsed}${e.time.extra ? '+' + e.time.extra : ''}'`;
      const name = e.player?.name || '';
      if (e.type === 'Goal') {
        if (e.detail === 'Missed Penalty') events.push({ min, side, kind: 'miss', title: name, sub: 'ضيّع ضربة جزاء' });
        else events.push({ min, side, kind: 'goal', title: name, sub: e.detail === 'Own Goal' ? 'في مرماه' : e.detail === 'Penalty' ? 'ضربة جزاء' : e.assist?.name ? 'صناعة: ' + e.assist.name : '' });
      } else if (e.type === 'Card') events.push({ min, side, kind: /red|second/i.test(e.detail) ? 'rc' : 'yc', title: name, sub: /second/i.test(e.detail) ? 'إنذار تاني' : /red/i.test(e.detail) ? 'طرد' : 'إنذار' });
      else if (e.type === 'subst') events.push({ min, side, kind: 'sub', title: name, sub: 'تبديل مع ' + (e.assist?.name || '') });
      else if (e.type === 'Var') events.push({ min, side, kind: 'var', title: name, sub: e.detail || 'فار' });
    }
    const s = f.statistics || [];
    const stats = [];
    if (s.length === 2) {
      const [h, a] = s[0].team?.id === homeId ? s : [s[1], s[0]];
      const am = new Map(a.statistics.map((x) => [x.type, x.value]));
      for (const { type, value } of h.statistics) {
        const av = am.get(type);
        if (value == null && av == null) continue;
        const n = (v) => parseFloat(String(v ?? 0).replace('%', '')) || 0;
        stats.push({ label: AF_STAT[type] || type, h: value ?? 0, a: av ?? 0, hv: n(value), av: n(av) });
      }
    }
    const L = f.lineups || [];
    const lineups = (L[0]?.team?.id === homeId ? L : [...L].reverse()).map((t) => ({
      name: t.team.name, formation: t.formation || '', coach: t.coach?.name || '',
      xi: (t.startXI || []).map((p) => ({ no: p.player.number, name: p.player.name })),
      subs: (t.substitutes || []).map((p) => ({ no: p.player.number, name: p.player.name })),
    }));
    return { fx, events, stats, lineups, notStarted: fx.st === 'NS' };
  }

  // ---- العرض ----
  function header(c) {
    const T = teamKeys();
    const live = LIVE.has(c.st);
    const star = (t) => `<button type="button" class="star${T.has(t.key) ? ' on' : ''}" data-team="${esc(JSON.stringify({ key: t.key, name: t.name, logo: t.logo }))}" aria-label="فريق مفضل"><svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/></svg></button>`;
    return `<div class="mh">
      <div class="lg">${imgTag(c.lg.logo)}<span>${esc(leagueName(c.lg))}${c.lg.round ? ' • ' + esc(roundName(c.lg.round)) : ''}</span></div>
      <div class="mh-row">
        <div class="mh-team">${imgTag(c.h.logo)}<span>${esc(c.h.name)}</span>${star(c.h)}</div>
        <div>${hasScore(c) ? `<div class="mh-score">${c.g[0]} - ${c.g[1]}</div>` : `<div class="mh-time">${hhmm(c.ts)}</div>`}
          ${c.pen[0] != null ? `<div class="pen">ترجيح (${c.pen[0]} - ${c.pen[1]})</div>` : ''}
          <div class="mh-st${live ? ' live' : ''}">${statusText(c) || new Date(c.ts).toLocaleDateString('ar-EG', { weekday: 'long', day: 'numeric', month: 'long' })}</div></div>
        <div class="mh-team">${imgTag(c.a.logo)}<span>${esc(c.a.name)}</span>${star(c.a)}</div>
      </div>
      <div class="mh-meta">${[c.ven, c.city].filter(Boolean).map(esc).join('، ')}${c.ref ? ' • الحكم: ' + esc(c.ref) : ''}</div>
    </div>`;
  }
  function bindStars(root) {
    $$('.star[data-team]', root).forEach((b) => {
      b.onclick = () => {
        const t = JSON.parse(b.dataset.team);
        const has = cfg.teams.some((x) => x.key === t.key);
        cfg.teams = has ? cfg.teams.filter((x) => x.key !== t.key) : [...cfg.teams, t];
        saveCfg();
        b.classList.toggle('on', !has);
        toast(has ? `${t.name} اتشال من فرقك` : `${t.name} اتضاف لفرقك`);
        renderMatches();
        renderTeams();
      };
    });
  }

  let matchTab = 'events';
  function renderMatch(d) {
    const body = $('#match-body');
    const tabs = [['events', 'الأحداث'], ['stats', 'الإحصائيات'], ['lineups', 'التشكيل']];
    body.innerHTML = header(d.fx) + `<div class="tabs">${tabs.map(([k, l]) => `<button type="button" class="tab${k === matchTab ? ' active' : ''}" data-tab="${k}">${l}</button>`).join('')}</div><div class="pane"></div>`;
    bindStars(body);
    const pane = $('.pane', body);
    const draw = () => {
      $$('.tab', body).forEach((t) => t.classList.toggle('active', t.dataset.tab === matchTab));
      pane.innerHTML = matchTab === 'events' ? eventsHtml(d) : matchTab === 'stats' ? statsHtml(d) : lineupsHtml(d);
    };
    $$('.tab', body).forEach((t) => (t.onclick = () => { matchTab = t.dataset.tab; draw(); }));
    draw();
  }
  const IC = {
    goal: '<span class="ic goal"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="m12 7 4 3-1.5 5h-5L8 10z"/></svg></span>',
    sub: '<span class="ic sub"><svg viewBox="0 0 24 24"><path d="M7 4v14M3 14l4 4 4-4M17 20V6M13 10l4-4 4 4"/></svg></span>',
    yc: '<span class="ic cardi"><i></i></span>', rc: '<span class="ic cardi red"><i></i></span>',
    miss: '<span class="ic var">X</span>', var: '<span class="ic var">VAR</span>',
  };
  function eventsHtml(d) {
    if (!d.events.filter((e) => !e.sep).length) return `<div class="note">${d.notStarted ? 'الماتش لسه مابدأش.' : 'مفيش أحداث متسجلة.'}</div>`;
    return d.events.map((e) => {
      if (e.sep) return `<div class="ht-sep">— ${esc(e.sep)} —</div>`;
      const side = `<div class="side${e.side === 'h' ? '' : ' away'}">${IC[e.kind] || ''}<div class="who"><b>${esc(e.title)}</b><small>${esc(e.sub)}</small></div></div>`;
      return `<div class="ev">${e.side === 'h' ? side : '<div></div>'}<div class="min">${esc(e.min)}</div>${e.side === 'h' ? '<div></div>' : side}</div>`;
    }).join('');
  }
  function statsHtml(d) {
    if (!d.stats.length) return `<div class="note">${d.notStarted ? 'الإحصائيات بتظهر لما الماتش يبدأ.' : 'مفيش إحصائيات للماتش ده.'}</div>`;
    return d.stats.map((s) => {
      const tot = s.hv + s.av || 1;
      return `<div class="stat"><div class="stat-top"><span>${esc(s.h)}</span><span>${esc(s.label)}</span><span>${esc(s.a)}</span></div>
        <div class="bars"><div class="h"><i style="width:${(s.hv / tot) * 100}%"></i></div><div class="a"><i style="width:${(s.av / tot) * 100}%"></i></div></div></div>`;
    }).join('');
  }
  function lineupsHtml(d) {
    if (!d.lineups.length) return '<div class="note">التشكيل بيتعلن عادةً قبل الماتش بساعة تقريبًا.</div>';
    const pl = (p) => `<div class="pl"><span class="no">${esc(p.no ?? '')}</span><span>${esc(p.name)}</span></div>`;
    return `<div class="lineups">${d.lineups.map((t) => `<div class="lu"><h4>${esc(t.name)}</h4>
      <div class="meta">${t.formation ? 'خطة ' + esc(t.formation) : ''}${t.coach ? ' • المدرب: ' + esc(t.coach) : ''}</div>
      ${t.xi.map(pl).join('')}${t.subs.length ? '<div class="sub-title">الاحتياطي</div>' + t.subs.map(pl).join('') : ''}</div>`).join('')}</div>`;
  }
  function closeMatch() { $('#match').classList.add('hidden'); state.openId = null; clearTimeout(state.matchTimer); }
  $('#match').addEventListener('click', (e) => { if (e.target.id === 'match' || e.target.closest('[data-close]')) closeMatch(); });

  // =====================================================================
  //                               الترتيب
  // =====================================================================
  function renderTableChips() {
    const box = $('#table-leagues');
    box.innerHTML = '';
    const usable = cfg.leagues.filter((l) => l.src === 'es' || cfg.key);
    if (!usable.some((l) => lkey(l) === cfg.tableLeague)) cfg.tableLeague = usable[0] ? lkey(usable[0]) : cfg.leagues[0] ? lkey(cfg.leagues[0]) : null;
    for (const l of cfg.leagues) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'chip' + (lkey(l) === cfg.tableLeague ? ' active' : '');
      b.innerHTML = imgTag(l.logo) + `<span>${esc(leagueName(l))}</span>`;
      b.onclick = () => { cfg.tableLeague = lkey(l); saveCfg(); renderTableChips(); loadTable(); };
      box.appendChild(b);
    }
    $('#table-leagues .chip.active')?.scrollIntoView({ inline: 'center', block: 'nearest' });
  }

  async function loadTable(force = false, seasonOverride = null) {
    const key = cfg.tableLeague;
    const box = $('#table');
    if (!key) { box.innerHTML = '<div class="empty"><h4>مفيش دوريات</h4><div>ضيف دوري من الإعدادات.</div></div>'; return; }
    const l = cfg.leagues.find((x) => lkey(x) === key);
    box.innerHTML = '<div class="skeleton" style="margin:12px"></div>'.repeat(5);
    $('#refresh').classList.add('spin');
    try {
      let table;
      if (l.src === 'es') {
        const j = await cached(`es.table.${l.slug}`, 3600000, () => espn(`v2/sports/soccer/${l.slug}/standings`), { force });
        table = espnTable(j);
      } else {
        if (!cfg.key) throw new ApiErr('ترتيب الدوري ده محتاج مفتاح API-Football من الإعدادات.', 'nokey');
        const season = seasonOverride || await cached('af.season.' + l.id, 7 * 86400000, async () => {
          const r = await af('leagues', { id: l.id, current: 'true' });
          const s = r[0]?.seasons?.find((x) => x.current) || r[0]?.seasons?.slice(-1)[0];
          return s ? s.year : null;
        });
        if (!season) throw new ApiErr('مالقيتش موسم حالي للبطولة دي.');
        const data = await cached(`af.table.${l.id}.${season}`, 3 * 3600000, () => af('standings', { league: l.id, season }), { force });
        table = afTable(data, season);
      }
      if (cfg.tableLeague === key) renderTable(table);
    } catch (e) {
      if (cfg.tableLeague !== key) return;
      if (e.seasons) {
        const last = e.seasons[1];
        box.innerHTML = `<div class="empty"><h4>الموسم الحالي مش متاح في الخطة المجانية</h4><div>API-Football المجاني بيعرض مواسم ${e.seasons[0]} لحد ${last} بس.</div>
          <button class="btn" data-season type="button">اعرض ترتيب موسم ${last}/${last + 1}</button></div>`;
        $('[data-season]', box).onclick = () => loadTable(false, last);
      } else box.innerHTML = `<div class="empty"><h4>حصلت مشكلة</h4><div style="white-space:pre-line">${esc(e.message)}</div></div>`;
    } finally {
      $('#refresh').classList.remove('spin');
    }
  }

  function espnTable(j) {
    const kids = j.children?.length ? j.children : j.standings ? [j] : [];
    const groups = kids.map((c) => ({
      title: kids.length > 1 ? String(c.name || '').replace(/^.* - /, '').replace('Group', 'مجموعة') : '',
      rows: (c.standings?.entries || []).map((e) => {
        const st = Object.fromEntries((e.stats || []).map((s) => [s.name, s.value ?? s.displayValue]));
        return {
          rank: Number(st.rank) || 0, team: { key: 'es:' + e.team.id, name: e.team.shortDisplayName || e.team.displayName, logo: e.team.logos?.[0]?.href },
          p: st.gamesPlayed, w: st.wins, d: st.ties, l: st.losses, gd: Number(st.pointDifferential) || 0, pts: st.points,
          zone: e.note ? { desc: zoneAr(e.note.description), color: e.note.color } : null, form: '',
        };
      }).sort((a, b) => a.rank - b.rank),
    })).filter((g) => g.rows.length);
    return { groups, season: j.children?.[0]?.standings?.seasonDisplayName || j.season?.displayName || '' };
  }
  const ZONE_AR = [[/champions league qualif/i, 'تصفيات دوري الأبطال'], [/champions league/i, 'دوري الأبطال'], [/europa league qualif/i, 'تصفيات الدوري الأوروبي'], [/europa league/i, 'الدوري الأوروبي'],
    [/conference league qualif/i, 'تصفيات دوري المؤتمر'], [/conference league/i, 'دوري المؤتمر'], [/relegation play/i, 'ملحق الهبوط'], [/relegation/i, 'هبوط'],
    [/promotion play/i, 'ملحق الصعود'], [/promotion/i, 'صعود'], [/knockout|next round|qualif/i, 'التأهل للدور الجاي']];
  const zoneAr = (d) => { for (const [re, ar] of ZONE_AR) if (re.test(d || '')) return ar; return d; };
  function afZone(desc) {
    if (!desc) return null;
    if (/relegation/i.test(desc)) return { desc: zoneAr(desc), color: '#ff7f84' };
    if (/champions league|promotion|next round|round of 16/i.test(desc)) return { desc: zoneAr(desc), color: '#81d6ac' };
    return { desc: zoneAr(desc), color: '#4ea8ff' };
  }
  function afTable(data, season) {
    const groups = (data?.[0]?.league?.standings || []).map((g) => ({
      title: String(g[0]?.group || '').replace('Group', 'مجموعة'),
      rows: g.map((r) => ({
        rank: r.rank, team: { key: 'af:' + r.team.id, name: r.team.name, logo: r.team.logo },
        p: r.all.played, w: r.all.win, d: r.all.draw, l: r.all.lose, gd: r.goalsDiff, pts: r.points, zone: afZone(r.description), form: r.form || '',
      })),
    }));
    if (groups.length === 1) groups[0].title = '';
    return { groups, season: `موسم ${season}/${season + 1}` };
  }
  function renderTable(t) {
    const box = $('#table');
    if (!t.groups.length) { box.innerHTML = '<div class="empty"><h4>مفيش جدول ترتيب</h4><div>البطولة دي ممكن تكون بنظام خروج المغلوب، أو الموسم لسه مابدأش.</div></div>'; return; }
    const T = teamKeys();
    const legend = new Map();
    const hasForm = t.groups.some((g) => g.rows.some((r) => r.form));
    let html = '';
    for (const g of t.groups) {
      if (g.title) html += `<div class="group-title">${esc(g.title)}</div>`;
      html += `<table><thead><tr><th>#</th><th class="tm">الفريق</th><th>لعب</th><th>ف</th><th>ت</th><th>خ</th><th>+/-</th><th>نقاط</th>${hasForm ? '<th class="f">آخر 5</th>' : ''}</tr></thead><tbody>`;
      for (const r of g.rows) {
        if (r.zone?.desc) legend.set(r.zone.desc, r.zone.color);
        const form = String(r.form).slice(-5).split('').filter(Boolean).map((x) => `<i class="${x}">${x === 'W' ? 'ف' : x === 'D' ? 'ت' : 'خ'}</i>`).join('');
        html += `<tr class="${T.has(r.team.key) ? 'fav' : ''}"><td class="rk" style="--q:${esc(r.zone?.color || 'transparent')}">${esc(r.rank)}</td>
          <td class="tm"><div>${imgTag(r.team.logo)}<span>${esc(r.team.name)}</span></div></td>
          <td>${esc(r.p)}</td><td>${esc(r.w)}</td><td>${esc(r.d)}</td><td>${esc(r.l)}</td>
          <td dir="ltr">${r.gd > 0 ? '+' : ''}${esc(r.gd)}</td><td class="pts">${esc(r.pts)}</td>${hasForm ? `<td class="f"><span class="form" dir="ltr">${form}</span></td>` : ''}</tr>`;
      }
      html += '</tbody></table>';
    }
    if (legend.size) html += `<div class="legend">${[...legend].map(([d, c]) => `<span style="--c:${esc(c)}">${esc(d)}</span>`).join('')}</div>`;
    if (t.season) html += `<div class="legend">${esc(t.season)}</div>`;
    box.innerHTML = html;
  }

  // =====================================================================
  //                              الإعدادات
  // =====================================================================
  function renderSettings() {
    $('#welcome').classList.toggle('hidden', !!cfg.key || ls.get('goal.seenWelcome', false));
    ls.set('goal.seenWelcome', true);
    $('#key').value = cfg.key;
    $$('.af-only').forEach((el) => el.classList.toggle('off', !cfg.key));
    $$('#interval button').forEach((b) => b.classList.toggle('active', Number(b.dataset.v) === cfg.interval));
    renderLeagues();
    renderCatalog();
    renderTeams();
  }

  $('#key-show').onclick = () => { const i = $('#key'); i.type = i.type === 'password' ? 'text' : 'password'; };
  $('#key-save').onclick = async () => {
    const box = $('#key-status');
    const btn = $('#key-save');
    cfg.key = $('#key').value.trim();
    saveCfg();
    box.className = 'status-box';
    if (!cfg.key) { box.textContent = 'اتشال المفتاح. الدوري المصري مش هيظهر.'; renderQuota(); return; }
    box.textContent = 'بختبر المفتاح...';
    btn.disabled = true;
    try {
      const s = await af('status');
      const sub = s.subscription || {};
      const req = s.requests || {};
      if (req.limit_day != null) setQuota(req.limit_day - (req.current || 0), req.limit_day);
      box.className = 'status-box ok';
      box.innerHTML = `المفتاح شغال ✓<br>الخطة: <b>${esc(sub.plan || '—')}</b>${sub.end ? ` • لحد ${esc(String(sub.end).slice(0, 10))}` : ''}<br>استخدمت النهارده <b>${esc(req.current ?? 0)}</b> من <b>${esc(req.limit_day ?? '?')}</b> طلب`;
      if (!cfg.leagues.some((l) => l.src === 'af')) { cfg.leagues.unshift({ ...DEFAULT_LEAGUES[0] }); saveCfg(); }
      state.days.clear();
      $$('.af-only').forEach((el) => el.classList.remove('off'));
      renderLeagues();
      renderCatalog();
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
      const needsKey = l.src === 'af' && !cfg.key;
      row.innerHTML = `${imgTag(l.logo)}<div class="tx"><b>${esc(leagueName(l))}<span class="src">${l.src === 'es' ? 'ESPN' : 'API-Football'}</span></b>
        <small>${needsKey ? 'محتاج مفتاح API-Football (تحت)' : l.src === 'af' ? 'امبارح والنهارده وبكرة بس' : 'أي يوم'}</small></div>
        ${i > 0 ? '<button type="button" class="act" data-up aria-label="لفوق">↑</button>' : ''}<button type="button" class="act" data-rm>شيل</button>`;
      $('[data-rm]', row).onclick = () => { cfg.leagues = cfg.leagues.filter((x) => x !== l); saveCfg(); state.days.clear(); renderLeagues(); renderCatalog(); };
      const up = $('[data-up]', row);
      if (up) up.onclick = () => { const a = cfg.leagues; [a[i - 1], a[i]] = [a[i], a[i - 1]]; saveCfg(); renderLeagues(); };
      box.appendChild(row);
    });
  }
  function renderCatalog() {
    const box = $('#catalog');
    const mine = myLeagueKeys();
    box.innerHTML = '';
    if (!mine.has('af:233')) {
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'cat';
      b.textContent = 'الدوري المصري (API-Football)';
      b.onclick = () => { cfg.leagues.unshift({ ...DEFAULT_LEAGUES[0] }); saveCfg(); state.days.clear(); renderLeagues(); renderCatalog(); };
      box.appendChild(b);
    }
    for (const [slug, name] of ESPN_CATALOG) {
      if (mine.has('es:' + slug)) continue;
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'cat';
      b.textContent = name;
      b.onclick = () => { cfg.leagues.push({ src: 'es', slug }); saveCfg(); state.days.clear(); renderLeagues(); renderCatalog(); toast('اتضاف ' + name); };
      box.appendChild(b);
    }
  }

  $('#league-search').addEventListener('input', debounce(async (e) => {
    const q = e.target.value.trim();
    const box = $('#league-results');
    if (q.length < 3) { box.innerHTML = ''; return; }
    box.innerHTML = '<div class="hint">بدوّر...</div>';
    try {
      const res = await cached('af.ls.' + q.toLowerCase(), 7 * 86400000, () => af('leagues', { search: q }));
      if (e.target.value.trim() !== q) return;
      const mine = myLeagueKeys();
      box.innerHTML = res.length ? '' : '<div class="hint">مالقيتش حاجة. جرّب اسم الدولة بالإنجليزي.</div>';
      for (const r of res.slice(0, 25)) {
        const l = { src: 'af', id: r.league.id, name: r.league.name, country: r.country?.name, logo: r.league.logo };
        const has = mine.has(lkey(l));
        const row = document.createElement('div');
        row.className = 'row';
        row.innerHTML = `${imgTag(l.logo)}<div class="tx"><b>${esc(leagueName(l))}</b><small>${esc(l.country || '')} • ${r.league.type === 'Cup' ? 'كأس' : 'دوري'}</small></div>
          <button type="button" class="act${has ? '' : ' add'}">${has ? 'موجود' : 'ضيف'}</button>`;
        const b = $('.act', row);
        if (!has) b.onclick = () => { cfg.leagues.push(l); saveCfg(); state.days.clear(); renderLeagues(); b.textContent = 'موجود'; b.classList.remove('add'); b.onclick = null; };
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
      $('.act', row).onclick = () => { cfg.teams = cfg.teams.filter((x) => x.key !== t.key); saveCfg(); renderTeams(); };
      box.appendChild(row);
    }
  }
  $$('#interval button').forEach((b) => { b.onclick = () => { cfg.interval = Number(b.dataset.v); saveCfg(); $$('#interval button').forEach((x) => x.classList.toggle('active', x === b)); }; });

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
    clearTimeout(state.timers.es);
    clearTimeout(state.timers.af);
    if (view === 'matches') { renderDates(); loadDay(state.date); }
    if (view === 'table') { renderTableChips(); loadTable(); }
    if (view === 'settings') renderSettings();
  }
  $$('#nav .nav-btn').forEach((b) => (b.onclick = () => go(b.dataset.view)));

  window.__back = () => { // زرار الرجوع في الأندرويد
    if (!$('#match').classList.contains('hidden')) { closeMatch(); return true; }
    if (state.view !== 'matches') { go('matches'); return true; }
    return false;
  };
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') window.__back(); });

  setInterval(() => { // يوم جديد؟ نرجّع لـ "النهارده"
    if (state.view === 'matches' && state.date < today() && $('#dates .date.active b')?.textContent === 'النهارده') { state.date = today(); renderDates(); loadDay(state.date); }
  }, 60000);

  renderQuota();
  go(ls.get('goal.seenWelcome', false) || cfg.key ? 'matches' : 'settings');
})();
