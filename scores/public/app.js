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
    ['caf.champions', tr("دوري أبطال أفريقيا")], ['caf.confed', tr("الكونفدرالية الأفريقية")], ['uefa.champions', tr("دوري أبطال أوروبا")],
    ['eng.1', tr("الدوري الإنجليزي")], ['esp.1', tr("الدوري الإسباني")], ['ita.1', tr("الدوري الإيطالي")], ['ger.1', tr("الدوري الألماني")],
    ['fra.1', tr("الدوري الفرنسي")], ['ksa.1', tr("الدوري السعودي")], ['uefa.europa', tr("الدوري الأوروبي")], ['uefa.europa.conf', tr("دوري المؤتمر الأوروبي")],
    ['afc.champions', tr("دوري أبطال آسيا")], ['caf.nations', tr("كأس أمم أفريقيا")], ['fifa.world', tr("كأس العالم")], ['fifa.worldq.caf', tr("تصفيات كأس العالم — أفريقيا")],
    ['fifa.cwc', tr("كأس العالم للأندية")], ['fifa.friendly', tr("مباريات ودية دولية")], ['eng.fa', tr("كأس الاتحاد الإنجليزي")], ['eng.league_cup', tr("كأس الرابطة الإنجليزية")],
    ['esp.copa_del_rey', tr("كأس ملك إسبانيا")], ['ita.coppa_italia', tr("كأس إيطاليا")], ['eng.2', tr("التشامبيونشيب")], ['ned.1', tr("الدوري الهولندي")],
    ['por.1', tr("الدوري البرتغالي")], ['tur.1', tr("الدوري التركي")], ['sco.1', tr("الدوري الاسكتلندي")], ['bel.1', tr("الدوري البلجيكي")],
    ['usa.1', tr("الدوري الأمريكي")], ['bra.1', tr("الدوري البرازيلي")], ['arg.1', tr("الدوري الأرجنتيني")], ['mex.1', tr("الدوري المكسيكي")], ['rsa.1', tr("دوري جنوب أفريقيا")],
  ];
  const ESPN_AR = Object.fromEntries(ESPN_CATALOG);
  const AF_AR = { 233: tr("الدوري المصري"), 714: tr("كأس مصر"), 539: tr("كأس الرابطة المصرية"), 12: tr("دوري أبطال أفريقيا"), 20: tr("الكونفدرالية الأفريقية") };
  // نفس البطولة في المصدرين: رقمها في API-Football ← اسمها في ESPN
  const AF_TO_ESPN = {
    39: 'eng.1', 140: 'esp.1', 135: 'ita.1', 78: 'ger.1', 61: 'fra.1', 2: 'uefa.champions', 3: 'uefa.europa', 848: 'uefa.europa.conf',
    12: 'caf.champions', 20: 'caf.confed', 307: 'ksa.1', 17: 'afc.champions', 6: 'caf.nations', 1: 'fifa.world', 15: 'fifa.cwc',
    45: 'eng.fa', 48: 'eng.league_cup', 143: 'esp.copa_del_rey', 137: 'ita.coppa_italia', 40: 'eng.2', 88: 'ned.1', 94: 'por.1',
    203: 'tur.1', 179: 'sco.1', 144: 'bel.1', 253: 'usa.1', 71: 'bra.1', 128: 'arg.1', 262: 'mex.1', 288: 'rsa.1', 10: 'fifa.friendly',
  };
  const DEFAULT_LEAGUES = [
    { src: 'af', id: 233, name: 'Premier League', country: 'Egypt', logo: '' },
    ...['caf.champions', 'caf.confed', 'uefa.champions', 'eng.1', 'esp.1', 'ita.1', 'ger.1', 'fra.1', 'ksa.1'].map((slug) => ({ src: 'es', slug })),
  ];

  const LIVE = new Set(['1H', 'HT', '2H', 'ET', 'BT', 'P', 'SUSP', 'INT', 'LIVE']);
  const DONE = new Set(['FT', 'AET', 'PEN', 'AWD', 'WO']);
  const ST_TEXT = { HT: tr("استراحة"), BT: tr("استراحة"), P: tr("ركلات ترجيح"), FT: tr("انتهت"), AET: tr("بعد الإضافي"), PEN: tr("بالترجيح"), PST: tr("اتأجلت"), CANC: tr("اتلغت"), ABD: tr("اتوقفت"), SUSP: tr("معلّقة"), INT: tr("متوقفة"), TBD: tr("لم يتحدد"), AWD: tr("قرار إداري"), WO: tr("انسحاب") };

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
  const validTeam = (t) => t && typeof t.key === 'string' && /^(es|af):./.test(t.key) && !/:(undefined|null)$/.test(t.key);
  cfg.teams = (Array.isArray(cfg.teams) ? cfg.teams : []).filter(validTeam);
  cfg.leagues = (Array.isArray(cfg.leagues) && cfg.leagues.length ? cfg.leagues : DEFAULT_LEAGUES).filter((l) => l && (l.src === 'es' ? l.slug : l.id));
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
  const fmtDay = (k) => { const [y, m, d] = k.split('-').map(Number); return new Date(y, m - 1, d).toLocaleDateString(LOC, { weekday: 'long', day: 'numeric', month: 'numeric' }); };
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

  function logErr(e, where) {
    try {
      const msg = (e && (e.stack || e.message)) || String(e);
      ls.set('goal.lastErr', { t: Date.now(), where: where || '', msg: String(msg).slice(0, 600) });
    } catch { /* مفيش */ }
  }
  window.addEventListener('error', (ev) => logErr(ev.error || ev.message, 'window'));
  window.addEventListener('unhandledrejection', (ev) => logErr(ev.reason, 'promise'));

  class ApiErr extends Error { constructor(msg, kind) { super(msg); this.kind = kind || 'api'; } }

  // كاش في الذاكرة + على الجهاز، ولو الطلب فشل نرجّع آخر نسخة محفوظة
  const mem = new Map();
  async function cached(key, ttl, fn, { persist = true, force = false, finalAt = 0 } = {}) {
    const hit = mem.get(key) || (persist ? ls.get('goal.c.' + key, null) : null);
    const notFinal = finalAt && hit && hit.t < finalAt && Date.now() > finalAt;
    if (hit && !force && !notFinal && Date.now() - hit.t < ttl) return hit.d;
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
  function fetchT(url, ms = 12000) {
    const ac = new AbortController();
    const timer = setTimeout(() => ac.abort(), ms);
    return fetch(url, { signal: ac.signal }).finally(() => clearTimeout(timer));
  }
  async function espn(path) {
    let r;
    try { r = await fetchT(`${ESPN}/${path}`); } catch { throw new ApiErr(tr("مفيش اتصال بالنت."), 'net'); }
    if (!r.ok) throw new ApiErr(`ESPN ${tr("رد بخطأ")} ${r.status}.`, 'net');
    return r.json();
  }
  // ESPN بيقسّم الأيام بتوقيت أمريكا الشرقي، فيوم عندك ممكن يكون موزّع على يومين عندهم
  const ET_FMT = new Intl.DateTimeFormat('en-US', { timeZone: 'America/New_York', year: 'numeric', month: '2-digit', day: '2-digit' });
  const etDate = (ms) => {
    const o = Object.fromEntries(ET_FMT.formatToParts(ms).map((x) => [x.type, x.value]));
    return `${o.year}${String(o.month).padStart(2, '0')}${String(o.day).padStart(2, '0')}`;
  };
  // آخر لحظة في يوم ESPN (بتوقيت أمريكا الشرقي) + 6 ساعات: بعدها نتايج اليوم ده بقت نهائية
  function etFinalAt(etd) {
    const y = +etd.slice(0, 4), m = +etd.slice(4, 6), d = +etd.slice(6, 8);
    return Date.UTC(y, m - 1, d + 1, 5) + 6 * 3600000; // نهاية اليوم في أمريكا الشرقية ≈ 05:00 UTC اليوم اللي بعده
  }
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
      g: started ? [scoreNum(h.score), scoreNum(a.score)] : [null, null],
      pen: h.shootoutScore != null ? [Number(h.shootoutScore), Number(a.shootoutScore)] : [null, null],
    };
  }
  function scoreNum(x) { const v = x && typeof x === 'object' ? x.value ?? x.displayValue : x; return Number(v ?? 0) || 0; }
  async function espnDay(slug, date, force) {
    const lists = await Promise.all(espnDatesFor(date).map((d) =>
      cached(`es.${slug}.${d}`, Math.min(ttlFor(date), Date.now() < etFinalAt(d) ? 5 * 60000 : ttlFor(date)), async () => {
        const j = await espn(`site/v2/sports/soccer/${slug}/scoreboard?dates=${d}`);
        const L = j.leagues?.[0];
        return (j.events || []).map((ev) => esCompact(ev, slug, L));
      }, { force, finalAt: etFinalAt(d) })));
    return lists.flat().filter((f) => dayKey(new Date(f.ts)) === date);
  }

  // scorepanel: طلب واحد بيرجّع معظم الدوريات مع بعض. بعض البطولات (زي أفريقيا) مش بتيجي فيه،
  // فبنفتكر الدوريات اللي شفناها فيه، والباقي بنطلبه لوحده.
  const PANEL_SEED = ['eng.1', 'esp.1', 'ita.1', 'ger.1', 'fra.1', 'ksa.1', 'uefa.champions', 'ned.1', 'por.1', 'tur.1', 'bra.1', 'arg.1', 'mex.1', 'sco.1', 'eng.fa'];
  const panelSlugs = new Set([...PANEL_SEED, ...ls.get('goal.panelSlugs', [])]);
  const savePanelSlugs = debounce(() => ls.set('goal.panelSlugs', [...panelSlugs]), 1000);
  function espnPanel(etd, ttl, force) {
    return cached(`es.panel.${etd}`, Math.min(ttl, Date.now() < etFinalAt(etd) ? 5 * 60000 : ttl), async () => {
      const j = await espn(`site/v2/sports/soccer/scorepanel?dates=${etd}`);
      const out = {};
      for (const sc of j.scores || []) {
        const L = sc.leagues?.[0];
        if (!L?.slug) continue;
        out[L.slug] = (sc.events || []).map((ev) => esCompact(ev, L.slug, L));
        if (!panelSlugs.has(L.slug)) { panelSlugs.add(L.slug); savePanelSlugs(); }
      }
      return out;
    }, { force, finalAt: etFinalAt(etd) });
  }
  // ماتشات يوم محلي لدوريات معيّنة من ESPN بأقل عدد طلبات
  async function espnFor(slugs, date, force) {
    const etds = espnDatesFor(date);
    const viaPanel = slugs.filter((x) => panelSlugs.has(x));
    let single = slugs.filter((x) => !panelSlugs.has(x));
    const errors = [];
    const one = (x) => espnDay(x, date, force).catch((e) => { errors.push(e); return e.stale || []; });
    // الطلب المجمّع والطلبات المنفصلة بيمشوا مع بعض في نفس الوقت
    const [fromPanel, singles] = await Promise.all([
      viaPanel.length
        ? Promise.all(etds.map((d) => espnPanel(d, ttlFor(date), force)))
          .then((panels) => panels.flatMap((pn) => viaPanel.flatMap((x) => pn[x] || [])))
          .catch(async () => (await pool(viaPanel, 6, one)).flat()) // الطلب المجمّع فشل: نرجع للمنفصلة
        : [],
      pool(single, 6, one).then((x) => x.flat()),
    ]);
    const fromSingle = singles;
    const seen = new Set();
    const list = [...fromPanel, ...fromSingle].filter((f) => dayKey(new Date(f.ts)) === date && !seen.has(f.id) && seen.add(f.id));
    return { list, errors, failedAll: errors.length && errors.length === single.length && !fromPanel.length };
  }
  // اللي متخزن على الجهاز من غير ما نبص على الصلاحية (للعرض الفوري)
  function peek(key) { const h = mem.get(key) || ls.get('goal.c.' + key, null); return h ? h.d : undefined; }
  function peekDay(date, slugs) {
    const etds = espnDatesFor(date);
    let any = false;
    const out = [];
    for (const d of etds) {
      const pn = peek(`es.panel.${d}`);
      if (pn) { any = true; for (const x of slugs) if (panelSlugs.has(x)) out.push(...(pn[x] || [])); }
    }
    for (const x of slugs) {
      if (panelSlugs.has(x)) continue;
      for (const d of etds) { const l = peek(`es.${x}.${d}`); if (l) { any = true; out.push(...l); } }
    }
    const afl = peek('af.day.' + date);
    if (afl) { any = true; out.push(...afl); }
    if (!any) return null;
    const seen = new Set();
    return out.filter((f) => dayKey(new Date(f.ts)) === date && !seen.has(f.id) && seen.add(f.id));
  }

  // =====================================================================
  //                      مصدر 2: API-Football
  // =====================================================================
  function afError(j) {
    const e = j && j.errors;
    if (!e || (Array.isArray(e) && !e.length) || (typeof e === 'object' && !Object.keys(e).length)) return null;
    const [k, v] = Array.isArray(e) ? ['', typeof e[0] === 'string' ? e[0] : JSON.stringify(e[0])] : Object.entries(e)[0];
    const raw = String(v);
    if (k === 'token' || /application key|api key|not subscribed|account/i.test(raw)) return new ApiErr(tr("مفتاح API-Football غلط أو الحساب مش متفعّل — راجعه في الإعدادات.\n(") + raw + ')', 'key');
    if (k === 'requests' || /request limit|limit for the day/i.test(raw)) { setQuota(0); return new ApiErr(tr("خلصت طلبات API-Football النهارده. هتتجدد بكرة."), 'quota'); }
    if (k === 'rateLimit' || /too many requests/i.test(raw)) return new ApiErr(tr("طلبات كتير ورا بعض — استنى دقيقة."), 'rate');
    if (k === 'plan' || /plan/i.test(raw)) {
      const days = raw.match(/from (\d{4}-\d{2}-\d{2}) to (\d{4}-\d{2}-\d{2})/);
      if (days) { setRange(days[1], days[2]); return new ApiErr(`${tr("الخطة المجانية بتعرض")} ${fmtDay(days[1])} ${tr("لحد")} ${fmtDay(days[2])} ${tr("بس")}.`, 'range'); }
      const seasons = raw.match(/from (\d{4}) to (\d{4})/);
      const er = new ApiErr(tr("الخطة المجانية مش بتغطي الطلب ده:\n") + raw, 'plan');
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
    el.textContent = `${q.rem} / ${q.lim} ${tr("طلب")}`;
    el.title = tr("طلبات API-Football الباقية النهارده (للدوري المصري)");
    el.classList.toggle('low', q.rem > 0 && q.rem < 25);
    el.classList.toggle('out', q.rem <= 0);
  }

  const inflight = new Map();
  function af(ep, params = {}) {
    const qs = new URLSearchParams(params).toString();
    const ck = ep + '?' + qs;
    if (inflight.has(ck)) return inflight.get(ck);
    const p = (async () => {
      if (!cfg.key) throw new ApiErr(tr("محتاج مفتاح API-Football من الإعدادات."), 'nokey');
      let r;
      try { r = await fetchT(`/api/football/${ep}?${qs}${qs ? '&' : ''}_k=${encodeURIComponent(cfg.key)}`, 15000); }
      catch { throw new ApiErr(tr("مفيش اتصال بالنت."), 'net'); }
      const rem = r.headers.get('x-ratelimit-requests-remaining');
      if (rem != null && rem !== '') setQuota(rem, r.headers.get('x-ratelimit-requests-limit'));
      let j;
      try { j = await r.json(); } catch { throw new ApiErr(`${tr("رد غير متوقع من")} API-Football (${r.status}).`, 'net'); }
      const err = afError(j);
      if (err) throw err;
      if (!r.ok) throw new ApiErr(`API-Football ${tr("رد بخطأ")} ${r.status}.`, 'net');
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
  const afNeeded = () => !!cfg.key && (state.filter !== 'mine' || cfg.leagues.some((l) => l.src === 'af') || cfg.teams.some((t) => String(t?.key || '').startsWith('af:')));

  const loadingDays = new Set();
  async function loadDay(date, force = false) {
    if (loadingDays.has(date) && !force) return;
    loadingDays.add(date);
    try { await loadDayInner(date, force); } catch (e) {
      logErr(e, 'loadDay');
      if (state.date === date && !state.days.get(date)?.list?.length) showError($('#matches'), e);
    } finally {
      loadingDays.delete(date);
      $('#refresh').classList.remove('spin');
    }
  }
  async function loadDayInner(date, force = false) {
    const box = $('#matches');
    const slugs = espnLeaguesNeeded();
    const wantAf = afNeeded();
    // العرض الفوري: آخر نسخة متخزنة حتى لو قديمة، والتحديث بيكمل في الخلفية
    if (!force) {
      const cachedList = peekDay(date, slugs);
      if (cachedList) {
        const prev = state.days.get(date);
        state.days.set(date, { t: prev?.t || 0, list: cachedList, notes: prev?.notes || [] });
        if (state.date === date) renderMatches();
      } else if (state.date === date) {
        box.dataset.date = date;
        box.innerHTML = '<div class="skeleton"></div>'.repeat(6);
        $('#updated').textContent = '';
      }
    }
    $('#refresh').classList.add('spin');
    const notes = [];
    let afList = [];
    let esList = [];
    const afPart = !wantAf ? Promise.resolve([]) : afLocked(date)
      ? (notes.push({ kind: 'range' }), Promise.resolve([]))
      : afDay(date, force).catch((e) => { notes.push(e.kind === 'range' ? { kind: 'range' } : { kind: 'af', msg: e.message }); return e.stale || []; });
    const myKeys = myLeagueKeys();
    const publish = () => {
      const list = [...esList, ...afList.filter((f) => !AF_TO_ESPN[f.lg.id] || myKeys.has(f.lg.key))];
      state.days.set(date, { t: Date.now(), list, notes: [...notes] });
      if (date === today()) { state.lastEs = Date.now(); state.lastAf = Date.now(); }
      if (state.date === date) { renderMatches(); scheduleLive(); }
    };
    // ESPN الأول (أسرع)، و API-Football بيتضاف لما يوصل
    const es = await espnFor(slugs, date, force);
    esList = es.list;
    if (state.date === date && es.failedAll && !state.days.get(date)?.list.length) { $('#refresh').classList.remove('spin'); showError(box, es.errors[0]); return; }
    if (es.errors.length && state.date === date) toast(`${es.errors.length} ${tr("دوري ماتحمّلش")} — ${es.errors[0].message}`);
    publish();
    afList = await afPart;
    if (wantAf) publish();
    $('#refresh').classList.remove('spin');
    if (date === state.date) prefetchAround(date);
  }

  // تحميل امبارح وبكرة في الخلفية عشان التنقل والسحب يبقوا فوريين (ESPN بس، من غير طلبات API-Football)
  const prefetched = new Set();
  function prefetchAround(date) {
    const go = () => {
      (async () => {
        for (const step of [1, -1]) {
          if (state.date !== date) return; // المستخدم راح ليوم تاني: نسيبه ياخد الشبكة
          const [y, m, d] = date.split('-').map(Number);
          const k = dayKey(new Date(y, m - 1, d + step));
          if (prefetched.has(k + state.filter)) continue;
          prefetched.add(k + state.filter);
          await espnFor(espnLeaguesNeeded(), k, false).catch(() => {});
        }
      })();
    };
    (window.requestIdleCallback || ((f) => setTimeout(f, 800)))(go);
  }

  function showError(box, e) {
    box.innerHTML = `<div class="empty"><h4>${tr("حصلت مشكلة")}</h4><div style="white-space:pre-line">${esc(e.message)}</div><button class="btn" data-retry type="button">${tr("جرّب تاني")}</button></div>`;
    $('[data-retry]', box).onclick = () => loadDay(state.date, true);
  }

  function renderDates() {
    const box = $('#dates');
    box.innerHTML = '';
    const base = new Date();
    const diff = dayDiff(state.date);
    for (let i = Math.min(-7, diff - 3); i <= Math.max(7, diff + 3); i++) {
      const d = new Date(base.getFullYear(), base.getMonth(), base.getDate() + i);
      const k = dayKey(d);
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'date' + (k === state.date ? ' active' : '');
      b.setAttribute('role', 'tab');
      const label = i === 0 ? tr("النهارده") : i === -1 ? tr("امبارح") : i === 1 ? tr("بكرة") : d.toLocaleDateString(LOC, { weekday: 'short' });
      b.innerHTML = `<b>${label}</b><small>${d.getDate()}/${d.getMonth() + 1}</small>`;
      b.onclick = () => selectDay(k);
      box.appendChild(b);
    }
    try { $('#dates .date.active')?.scrollIntoView({ inline: 'center', block: 'nearest' }); } catch (e) { logErr(e, 'scrollIntoView'); }
  }

  function selectDay(k) {
    state.date = k;
    loadDay(k); // الأول: نبدأ تحميل الماتشات
    try { renderDates(); } catch (e) { logErr(e, 'renderDates'); }
  }

  // كام يوم بين اليوم المختار والنهارده
  function dayDiff(k) {
    const [y, m, d] = k.split('-').map(Number);
    const n = new Date();
    return Math.round((new Date(y, m - 1, d) - new Date(n.getFullYear(), n.getMonth(), n.getDate())) / 86400000);
  }
  // يوم لقدام (+1) أو لورا (-1)، والماتشات بتدخل من ناحية السحب
  function shiftDay(step, fromLeft) {
    const [y, m, d] = state.date.split('-').map(Number);
    state.date = dayKey(new Date(y, m - 1, d + step));
    try { renderDates(); } catch (e) { logErr(e, 'renderDates'); }
    const box = $('#matches');
    box.classList.remove('in-l', 'in-r');
    void box.offsetWidth;
    box.classList.add(fromLeft ? 'in-l' : 'in-r');
    loadDay(state.date);
  }

  // السحب يمين/شمال على قايمة الماتشات بيغيّر اليوم
  // بالعربي (يمين لشمال) بكرة على الشمال: السحب لليمين = بكرة. بالإنجليزي العكس.
  (() => {
    const area = $('#view-matches');
    let sx = 0, sy = 0, st = 0, ok = false;
    area.addEventListener('touchstart', (e) => {
      ok = e.touches.length === 1 && !e.target.closest('.dates, .filters');
      if (!ok) return;
      sx = e.touches[0].clientX; sy = e.touches[0].clientY; st = Date.now();
    }, { passive: true });
    area.addEventListener('touchend', (e) => {
      if (!ok || state.view !== 'matches') return;
      ok = false;
      const t = e.changedTouches[0];
      const dx = t.clientX - sx;
      const dy = t.clientY - sy;
      if (Math.abs(dx) < 60 || Math.abs(dx) < Math.abs(dy) * 1.6 || Date.now() - st > 800) return;
      const forward = RTL ? dx > 0 : dx < 0;
      shiftDay(forward ? 1 : -1, dx > 0);
    }, { passive: true });
  })();

  $$('.filters .chip').forEach((c) => {
    c.classList.toggle('active', c.dataset.filter === state.filter);
    c.onclick = () => {
      const prev = state.filter;
      state.filter = c.dataset.filter;
      ls.set('goal.filter', state.filter);
      $$('.filters .chip').forEach((x) => x.classList.toggle('active', x === c));
      if ((prev === 'mine' && state.filter !== 'mine') || !state.days.has(state.date)) loadDay(state.date); // محتاجين دوريات أكتر
      else { renderMatches(); scheduleLive(); }
    };
  });

  function visibleFixtures() {
    const day = state.days.get(state.date);
    if (!day) return [];
    const L = myLeagueKeys();
    const T = teamKeys();
    const onDay = day.list.filter((f) => dayKey(new Date(f.ts)) === state.date);
    if (state.filter === 'live') return onDay.filter((f) => LIVE.has(f.st));
    if (state.filter === 'mine') return onDay.filter((f) => L.has(f.lg.key) || T.has(f.h.key) || T.has(f.a.key));
    return onDay;
  }

  function renderMatches() {
    try { renderMatchesInner(); } catch (e) {
      const box = $('#matches');
      box.dataset.date = state.date;
      box.innerHTML = `<div class="empty"><h4>${tr("حصلت مشكلة")}</h4><div>${esc(e.message)}</div></div>`;
    }
  }
  function renderMatchesInner() {
    const box = $('#matches');
    box.dataset.date = state.date;
    const day = state.days.get(state.date);
    if (!day) {
      box.innerHTML = '<div class="skeleton"></div>'.repeat(6);
      if (!loadingDays.has(state.date)) loadDay(state.date); // مفيش بيانات ومفيش تحميل شغال: نبدأه
      return;
    }
    const list = visibleFixtures();
    $('#updated').textContent = state.date === today() ? `${tr("اتحدّث")} ${hhmm(Math.max(day.t, state.lastEs))}` : '';
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
          <div class="league-note">${n.kind === 'range' ? tr("الخطة المجانية بتاعة API-Football بتعرض الدوري المصري لامبارح والنهارده وبكرة بس.") : esc(n.msg)}</div></section>`).join('')
      : '';
    if (!list.length) {
      const msg = state.filter === 'live' ? tr("مفيش ماتشات شغالة دلوقتي") : state.filter === 'mine' ? tr("مفيش ماتشات لدورياتك في اليوم ده") : tr("مفيش ماتشات في اليوم ده");
      box.innerHTML = notesHtml + `<div class="empty"><h4>${msg}</h4>${state.filter === 'mine' ? `<button class="btn" data-all type="button">${tr("اعرض كل الدوريات")}</button>` : ''}</div>`;
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
      html += `<section class="league"><button type="button" class="league-head" data-lg="${esc(g.lg.key)}" aria-label="${tr("ترتيب")} ${esc(leagueName(g.lg))}">${imgTag(g.lg.logo)}<div><div class="nm">${esc(leagueName(g.lg))}</div>${g.lg.country ? `<div class="ct">${esc(g.lg.country)}</div>` : ''}</div>
        <span class="round">${rounds.length === 1 ? esc(roundName(rounds[0])) : ''}</span>
        <span class="to-table"><svg viewBox="0 0 24 24"><path d="M8 4h8v5a4 4 0 0 1-8 0z"/><path d="M12 13v4M9 20h6"/></svg>${tr("الترتيب")}</span></button>`;
      for (const f of g.items) html += matchRow(f, T.has(f.h.key) || T.has(f.a.key));
      html += '</section>';
    }
    box.innerHTML = html;
    $$('.match', box).forEach((b) => (b.onclick = () => openMatch(b.dataset.id)));
    const lgs = new Map(sorted.map((g) => [g.lg.key, g.lg]));
    $$('.league-head[data-lg]', box).forEach((b) => (b.onclick = () => openLeagueTable(lgs.get(b.dataset.lg))));
  }

  function roundName(r) {
    return String(r || '').replace(/Regular Season - (\d+)/, tr("الجولة $1")).replace(/Group Stage - (\d+)/, tr("دور المجموعات — الجولة $1"))
      .replace('Round of 16', tr("دور الـ16")).replace('Quarter-finals', tr("ربع النهائي")).replace('Semi-finals', tr("نص النهائي")).replace(/^Final$/, tr("النهائي"));
  }
  function statusText(f) {
    if (f.st === 'LIVE' && f.clock) return `<span dir="ltr">${esc(f.clock)}</span>`;
    if (LIVE.has(f.st) && !ST_TEXT[f.st]) return f.el != null ? `<span dir="ltr">${f.el}${f.ex ? '+' + f.ex : ''}'</span>` : tr("مباشر");
    return ST_TEXT[f.st] || '';
  }
  // الصفحة يمين-لشمال: صاحب الأرض يمين والضيف شمال، فبنكتب "الضيف - صاحب الأرض" من الشمال لليمين
  // بالإنجليزي (شمال لليمين) صاحب الأرض على الشمال، فالترتيب بيتعكس
  const scoreTxt = (p) => (RTL ? `<span dir="ltr">${p[1]} - ${p[0]}</span>` : `<span dir="ltr">${p[0]} - ${p[1]}</span>`);
  const hasScore = (f) => f.g[0] != null && f.st !== 'NS' && f.st !== 'PST' && f.st !== 'CANC';
  function matchRow(f, fav) {
    const live = LIVE.has(f.st);
    const mid = hasScore(f)
      ? `<div class="score">${scoreTxt(f.g)}</div>${f.pen[0] != null ? `<div class="pen">(${scoreTxt(f.pen)})</div>` : ''}<div class="st">${statusText(f)}</div>`
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
        const fresh = slugs.length ? (await espnFor(slugs, today(), true)).list : [];
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
      toast(`${tr("جوووول")}! \u2066${f.h.name} ${f.g[0]} - ${f.g[1]} ${f.a.name}\u2069`, 'goal');
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
  const extraFx = new Map(); // ماتشات اتعرضت في صفحات الفرق
  const findFixture = (id) => { for (const d of state.days.values()) { const f = d.list.find((x) => x.id === id); if (f) return f; } return extraFx.get(id) || null; };

  async function openMatch(id, silent) {
    state.openId = id;
    showModal($('#match'));
    const body = $('#match-body');
    let base = findFixture(id);
    if (!silent) {
      body.innerHTML = (base ? header(base) : '') + `<div class="note">${tr("جاري تحميل التفاصيل")}...</div>`;
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
        if (!resp[0]) throw new ApiErr(tr("الماتش ده مش موجود."));
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
    ['possessionPct', tr("الاستحواذ"), pctFmt], ['totalShots', tr("التسديدات")], ['shotsOnTarget', tr("على المرمى")],
    ['wonCorners', tr("الركنيات")], ['foulsCommitted', tr("الأخطاء")], ['offsides', tr("التسلل")], ['yellowCards', tr("إنذارات")], ['redCards', tr("طرد")],
    ['saves', tr("تصديات الحارس")], ['totalPasses', tr("التمريرات")], ['passPct', tr("دقة التمرير"), pctFmt],
    ['totalTackles', tr("الالتحامات")], ['interceptions', tr("قطع الكورة")],
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
      if (t === 'halftime') { events.push({ sep: tr("الاستراحة") }); continue; }
      if (/missed|saved/.test(t)) events.push({ min, side, kind: 'miss', title: names[0] || '', sub: tr("ضيّع ضربة جزاء") });
      else if (/goal|penalty---scored/.test(t)) {
        const own = /own/.test(t);
        const pen = /penalty/.test(t);
        events.push({ min, side, kind: 'goal', title: names[0] || '', sub: own ? tr("في مرماه") : pen ? tr("ضربة جزاء") : names[1] ? tr("صناعة: ") + names[1] : '' });
      } else if (t === 'yellow-card') events.push({ min, side, kind: 'yc', title: names[0] || '', sub: tr("إنذار") });
      else if (/red-card/.test(t)) events.push({ min, side, kind: 'rc', title: names[0] || '', sub: tr("طرد") });
      else if (t === 'substitution') events.push({ min, side, kind: 'sub', title: tr("دخل: ") + (names[0] || ''), sub: tr("خرج: ") + (names[1] || '') });
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
      name: r.team?.shortDisplayName || r.team?.displayName || '', formation: r.formation || '', coach: '',
      color: r.uniform?.color || r.team?.color || '', alt: r.uniform?.alternateColor || r.team?.alternateColor || '',
      club: r.team?.color || '', clubAlt: r.team?.alternateColor || '',
      xi: (r.roster || []).filter((p) => p.starter).map((p) => ({
        no: p.jersey, name: p.athlete?.displayName, short: p.athlete?.lastName || p.athlete?.shortName, pos: p.position?.abbreviation || '', out: !!p.subbedOut, id: p.athlete?.id,
      })),
      subs: (r.roster || []).filter((p) => !p.starter).map((p) => ({ no: p.jersey, name: p.athlete?.displayName, in: !!p.subbedIn, id: p.athlete?.id })),
      tid: r.team?.id, slug,
    })).filter((r) => r.xi.length);
    return { fx, events, stats, lineups, notStarted: fx?.st === 'NS' };
  }

  // ---- API-Football → شكل موحّد ----
  const AF_STAT = {
    'Ball Possession': tr("الاستحواذ"), 'Total Shots': tr("التسديدات"), 'Shots on Goal': tr("على المرمى"), 'Corner Kicks': tr("الركنيات"), Fouls: tr("الأخطاء"),
    Offsides: tr("التسلل"), 'Yellow Cards': tr("إنذارات"), 'Red Cards': tr("طرد"), 'Goalkeeper Saves': tr("تصديات الحارس"), 'Total passes': tr("التمريرات"),
    'Passes %': tr("دقة التمرير"), expected_goals: tr("الأهداف المتوقعة (xG)"), 'Shots off Goal': tr("خارج المرمى"), 'Blocked Shots': tr("تسديدات اتصدت"),
  };
  function afDetail(f) {
    const fx = afCompact(f);
    const homeId = f.teams.home.id;
    const events = [];
    let ht = false;
    for (const e of f.events || []) {
      if (!ht && e.time.elapsed > 45 && !e.time.extra) { events.push({ sep: tr("الشوط التاني") }); ht = true; }
      const side = e.team?.id === homeId ? 'h' : 'a';
      const min = `${e.time.elapsed}${e.time.extra ? '+' + e.time.extra : ''}'`;
      const name = e.player?.name || '';
      if (e.type === 'Goal') {
        if (e.detail === 'Missed Penalty') events.push({ min, side, kind: 'miss', title: name, sub: tr("ضيّع ضربة جزاء") });
        else events.push({ min, side, kind: 'goal', title: name, sub: e.detail === 'Own Goal' ? tr("في مرماه") : e.detail === 'Penalty' ? tr("ضربة جزاء") : e.assist?.name ? tr("صناعة: ") + e.assist.name : '' });
      } else if (e.type === 'Card') events.push({ min, side, kind: /red|second/i.test(e.detail) ? 'rc' : 'yc', title: name, sub: /second/i.test(e.detail) ? tr("إنذار تاني") : /red/i.test(e.detail) ? tr("طرد") : tr("إنذار") });
      else if (e.type === 'subst') events.push({ min, side, kind: 'sub', title: name, sub: tr("تبديل مع ") + (e.assist?.name || '') });
      else if (e.type === 'Var') events.push({ min, side, kind: 'var', title: name, sub: e.detail || tr("فار") });
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
      color: t.team.colors?.player?.primary || '', alt: t.team.colors?.player?.border || t.team.colors?.player?.number || '',
      num: t.team.colors?.player?.number || '', gk: t.team.colors?.goalkeeper?.primary || '', gkNum: t.team.colors?.goalkeeper?.number || '',
      xi: (t.startXI || []).map((p) => ({ no: p.player.number, name: p.player.name, pos: p.player.pos || '', grid: p.player.grid || '' })),
      subs: (t.substitutes || []).map((p) => ({ no: p.player.number, name: p.player.name })),
    }));
    return { fx, events, stats, lineups, notStarted: fx.st === 'NS' };
  }

  // ---- العرض ----
  function header(c) {
    const T = teamKeys();
    const live = LIVE.has(c.st);
    const star = (t) => `<button type="button" class="star${T.has(t.key) ? ' on' : ''}" data-team="${esc(JSON.stringify({ key: t.key, name: t.name, logo: t.logo }))}" aria-label="${tr("فريق مفضل")}"><svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/></svg></button>`;
    return `<div class="mh">
      <div class="lg">${imgTag(c.lg.logo)}<span>${esc(leagueName(c.lg))}${c.lg.round ? ' • ' + esc(roundName(c.lg.round)) : ''}</span></div>
      <div class="mh-row">
        <div class="mh-team">${teamLink(c.h, c.slug, `${imgTag(c.h.logo)}<span>${esc(c.h.name)}</span>`)}${star(c.h)}</div>
        <div>${hasScore(c) ? `<div class="mh-score">${scoreTxt(c.g)}</div>` : `<div class="mh-time">${hhmm(c.ts)}</div>`}
          ${c.pen[0] != null ? `<div class="pen">${tr("ترجيح")} (${scoreTxt(c.pen)})</div>` : ''}
          <div class="mh-st${live ? ' live' : ''}">${statusText(c) || new Date(c.ts).toLocaleDateString(LOC, { weekday: 'long', day: 'numeric', month: 'long' })}</div></div>
        <div class="mh-team">${teamLink(c.a, c.slug, `${imgTag(c.a.logo)}<span>${esc(c.a.name)}</span>`)}${star(c.a)}</div>
      </div>
      <div class="mh-meta">${[c.ven, c.city].filter(Boolean).map(esc).join(tr("، "))}${c.ref ? tr(" • الحكم: ") + esc(c.ref) : ''}</div>
    </div>`;
  }
  function bindStars(root) {
    $$('.star[data-team]', root).forEach((b) => {
      b.onclick = () => {
        const t = JSON.parse(b.dataset.team);
        if (!validTeam(t)) return;
        const has = cfg.teams.some((x) => x.key === t.key);
        cfg.teams = has ? cfg.teams.filter((x) => x.key !== t.key) : [...cfg.teams, t];
        saveCfg();
        b.classList.toggle('on', !has);
        toast(has ? `${t.name} ${tr("اتشال من فرقك")}` : `${t.name} ${tr("اتضاف لفرقك")}`);
        renderMatches();
        renderTeams();
      };
    });
  }

  let matchTab = 'events';
  function renderMatch(d) {
    const body = $('#match-body');
    const tabs = [['events', tr("الأحداث")], ['stats', tr("الإحصائيات")], ['lineups', tr("التشكيل")]];
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
    if (!d.events.filter((e) => !e.sep).length) return `<div class="note">${d.notStarted ? tr("الماتش لسه مابدأش.") : tr("مفيش أحداث متسجلة.")}</div>`;
    return d.events.map((e) => {
      if (e.sep) return `<div class="ht-sep">— ${esc(e.sep)} —</div>`;
      const side = `<div class="side${e.side === 'h' ? '' : ' away'}">${IC[e.kind] || ''}<div class="who"><b>${esc(e.title)}</b><small>${esc(e.sub)}</small></div></div>`;
      return `<div class="ev">${e.side === 'h' ? side : '<div></div>'}<div class="min">${esc(e.min)}</div>${e.side === 'h' ? '<div></div>' : side}</div>`;
    }).join('');
  }
  function statsHtml(d) {
    if (!d.stats.length) return `<div class="note">${d.notStarted ? tr("الإحصائيات بتظهر لما الماتش يبدأ.") : tr("مفيش إحصائيات للماتش ده.")}</div>`;
    return d.stats.map((s) => {
      const tot = s.hv + s.av || 1;
      return `<div class="stat"><div class="stat-top"><span>${esc(s.h)}</span><span>${esc(s.label)}</span><span>${esc(s.a)}</span></div>
        <div class="bars"><div class="h"><i style="width:${(s.hv / tot) * 100}%"></i></div><div class="a"><i style="width:${(s.av / tot) * 100}%"></i></div></div></div>`;
    }).join('');
  }
  // ---- التشكيل على الملعب ----
  // عمق اللاعب في الملعب (من الحارس للهجوم) من اختصار مركزه
  function depth(pos) {
    const p = String(pos || '').toUpperCase();
    if (/^(G|GK)$/.test(p)) return 0;
    if (/^(CD|CB|D|DC|DL|DR|SW|LB|RB)(-|$)/.test(p)) return 1;
    if (/^(LWB|RWB|WB)/.test(p)) return 1.5;
    if (/^(DM|CDM)/.test(p)) return 2;
    if (/^(AM|CAM)/.test(p)) return 4;
    if (/^(F|CF|ST|S|FW|LW|RW|LF|RF)(-|$)/.test(p)) return 5;
    return 3; // وسط
  }
  // يمين/شمال من وجهة نظر الفريق: 0 شمال، 1 نص، 2 يمين
  function lateral(pos) {
    const p = String(pos || '').toUpperCase();
    if (/^L/.test(p)) return 0; // ظهير/جناح شمال
    if (/-L$/.test(p)) return 0.75; // نص شمال (زي CD-L)
    if (/^R/.test(p)) return 2;
    if (/-R$/.test(p)) return 1.25;
    return 1;
  }
  // بيرجّع خطوط من الحارس للهجوم، وكل خط مترتب من الشمال لليمين
  function lineupLines(t) {
    const xi = t.xi.slice(0, 11);
    if (xi.length && xi.every((p) => /^\d+:\d+$/.test(p.grid || ''))) { // API-Football: row:col
      const rows = new Map();
      for (const p of xi) { const [r, c] = p.grid.split(':').map(Number); if (!rows.has(r)) rows.set(r, []); rows.get(r).push({ ...p, c }); }
      return [...rows.keys()].sort((a, b) => a - b).map((r) => rows.get(r).sort((a, b) => a.c - b.c));
    }
    const gkIdx = xi.findIndex((p) => depth(p.pos) === 0);
    const gk = gkIdx >= 0 ? xi[gkIdx] : xi[0];
    const rest = xi.filter((p) => p !== gk).map((p, i) => ({ ...p, d: depth(p.pos), i })).sort((a, b) => a.d - b.d || a.i - b.i);
    const counts = String(t.formation || '').split('-').map(Number).filter((n) => n > 0);
    let lines = [];
    if (counts.length && counts.reduce((a, b) => a + b, 0) === rest.length) {
      let k = 0;
      for (const n of counts) { lines.push(rest.slice(k, k + n)); k += n; }
    } else { // من غير خطة: نجمّع حسب العمق
      const byD = new Map();
      for (const p of rest) { const d = Math.round(p.d); if (!byD.has(d)) byD.set(d, []); byD.get(d).push(p); }
      lines = [...byD.keys()].sort((a, b) => a - b).map((d) => byD.get(d));
    }
    return [[gk], ...lines.map((l) => l.sort((a, b) => lateral(a.pos) - lateral(b.pos) || a.d - b.d))];
  }
  function hexColor(c, fallback) { const h = String(c || '').replace('#', ''); return /^[0-9a-f]{6}$/i.test(h) ? '#' + h : fallback; }
  function inkFor(hex) {
    const n = parseInt(hex.slice(1), 16);
    const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; });
    return 0.2126 * r + 0.7152 * g + 0.0722 * b > 0.4 ? '#0a0e13' : '#ffffff';
  }
  const shortName = (p) => p.short || String(p.name || '').split(' ').slice(-1)[0];

  const rgb = (hex) => { const n = parseInt(hex.slice(1), 16); return [(n >> 16) & 255, (n >> 8) & 255, n & 255]; };
  const colorDist = (a, b) => { const [x, y] = [rgb(a), rgb(b)]; return Math.hypot(x[0] - y[0], x[1] - y[1], x[2] - y[2]); };
  const shade = (hex, k) => '#' + rgb(hex).map((v) => Math.max(0, Math.min(255, Math.round(v * k))).toString(16).padStart(2, '0')).join('');
  // ألوان طقم الفريق: الجسم، والأكمام/الياقة، ولون الرقم، وطقم الحارس
  function kit(t, fallback) {
    const body = hexColor(t.color, hexColor(t.club, fallback));
    let trim = hexColor(t.alt, hexColor(t.clubAlt, ''));
    if (!trim || colorDist(trim, body) < 60) trim = shade(body, inkFor(body) === '#ffffff' ? 1.6 : 0.6);
    let num = hexColor(t.num, '');
    if (!num || colorDist(num, body) < 110) num = inkFor(body);
    let gk = hexColor(t.gk, '');
    if (!gk || colorDist(gk, body) < 90) gk = colorDist('#f2c94c', body) > 120 ? '#f2c94c' : '#9b5de5';
    const gkNum = hexColor(t.gkNum, '') && colorDist(hexColor(t.gkNum, ''), gk) > 110 ? hexColor(t.gkNum, '') : inkFor(gk);
    return { body, trim, num, gk, gkTrim: shade(gk, 0.7), gkNum };
  }
  function jersey(body, trim, num, no) {
    return `<svg class="jersey" viewBox="0 0 40 40" aria-hidden="true">
      <path d="M14 4 8 6.5 2 12.5l5 5.5 3.2-2.7V37h19.6V15.3L33 18l5-5.5L32 6.5 26 4c-1.2 2.6-3.4 4-6 4s-4.8-1.4-6-4z" fill="${body}" stroke="rgba(0,0,0,.45)" stroke-width="1"/>
      <path d="M8 6.5 2 12.5l5 5.5 3.2-2.7zM32 6.5l6 6-5 5.5-3.2-2.7z" fill="${trim}"/>
      <path d="M14 4c1.2 2.6 3.4 4 6 4s4.8-1.4 6-4" fill="none" stroke="${trim}" stroke-width="2.4"/>
      <text x="20" y="28.5" text-anchor="middle" fill="${num}">${esc(no ?? '')}</text></svg>`;
  }

  function pitchHtml(d) {
    const [home, away] = d.lineups;
    const hk = kit(home, '#00e08a');
    let ak = away ? kit(away, '#4ea8ff') : null;
    if (ak && colorDist(hk.body, ak.body) < 80) { // الطقمين شبه بعض: الضيف يلبس لونه التاني
      const swapped = kit({ ...away, color: away.alt || away.clubAlt, alt: away.color }, '#ffffff');
      ak = colorDist(hk.body, swapped.body) >= 80 ? swapped : kit({ color: '#ffffff', alt: '#1b1b1b' }, '#ffffff');
    }
    if (ak && colorDist(hk.gk, ak.gk) < 80) { ak.gk = colorDist('#9b5de5', ak.body) > 100 ? '#9b5de5' : '#22d3ee'; ak.gkTrim = shade(ak.gk, 0.7); ak.gkNum = inkFor(ak.gk); }
    const place = (t, k, isHome) => {
      const lines = lineupLines(t);
      const L = lines.length;
      return lines.map((line, i) => line.map((p, j) => {
        const y = isHome ? 91.5 - (i * (91.5 - 55)) / Math.max(1, L - 1) : 4.5 + (i * (43 - 4.5)) / Math.max(1, L - 1);
        let x = ((j + 1) / (line.length + 1)) * 100;
        if (!isHome) x = 100 - x; // الفريق اللي فوق بيبص للناحية التانية
        const gk = i === 0;
        return `<div class="pp${p.id ? ' tap' : ''}"${p.id ? ` data-pid="${esc(p.id)}" data-tid="${esc(t.tid || '')}" data-slug="${esc(t.slug || '')}" role="button" tabindex="0"` : ''} style="left:${x.toFixed(1)}%;top:${y.toFixed(1)}%">
          <span class="shirt">${jersey(gk ? k.gk : k.body, gk ? k.gkTrim : k.trim, gk ? k.gkNum : k.num, p.no)}${p.out ? `<i class="out" title="${tr("خرج")}"></i>` : ''}</span>
          <span class="pn">${esc(shortName(p))}</span></div>`;
      }).join('')).join('');
    };
    const sw = (k) => `<i class="sw" style="background:${k.body};border-color:${k.trim}"></i>`;
    return `<div class="pitch-head"><span>${away ? sw(ak) : ''}${esc(away?.name || '')}</span><b>${esc(away?.formation || '')}</b></div>
      <div class="pitch" role="img" aria-label="${tr("التشكيل على الملعب")}">
        <div class="pl-line half"></div><div class="pl-circle"></div><div class="pl-spot"></div>
        <div class="pl-box at-top"></div><div class="pl-box6 at-top"></div><div class="pl-box at-bot"></div><div class="pl-box6 at-bot"></div>
        ${away ? place(away, ak, false) : ''}${place(home, hk, true)}
      </div>
      <div class="pitch-head"><span>${sw(hk)}${esc(home.name)}</span><b>${esc(home.formation || '')}</b></div>`;
  }

  function lineupsHtml(d) {
    if (!d.lineups.length) return `<div class="note">${tr("التشكيل بيتعلن عادةً قبل الماتش بساعة تقريبًا")}.</div>`;
    const pl = (p) => `<div class="pl${p.id ? ' tap' : ''}"${p.id ? ` data-pid="${esc(p.id)}" role="button" tabindex="0"` : ''}><span class="no">${esc(p.no ?? '')}</span><span>${esc(p.name)}</span>${p.in ? `<em class="in">${tr("دخل")}</em>` : ''}</div>`;
    const benches = `<div class="lineups">${d.lineups.map((t) => `<div class="lu"><h4>${esc(t.name)}</h4>
      ${t.coach ? `<div class="meta">${tr("المدرب")}: ${esc(t.coach)}</div>` : ''}
      ${t.subs.length ? `<div class="sub-title">${tr("الاحتياطي")}</div>` + t.subs.map(pl).join('') : ''}</div>`).join('')}</div>`;
    return pitchHtml(d) + benches;
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

  async function loadTable(force = false) {
    const key = cfg.tableLeague;
    const box = $('#table');
    if (!key) { box.innerHTML = `<div class="empty"><h4>${tr("مفيش دوريات")}</h4><div>${tr("ضيف دوري من الإعدادات")}.</div></div>`; return; }
    const l = cfg.leagues.find((x) => lkey(x) === key);
    $('#refresh').classList.add('spin');
    await fillTable(l, box, { force, alive: () => cfg.tableLeague === key && state.view === 'table' });
    $('#refresh').classList.remove('spin');
  }

  // بيرسم ترتيب الدوري l جوه box. alive() بترجّع false لو المستخدم راح لحاجة تانية
  async function fillTable(l0, box, { force = false, season: seasonOverride = null, alive = () => true } = {}) {
    box.innerHTML = '<div class="skeleton" style="margin:12px"></div>'.repeat(5);
    let l = l0;
    try {
      let table;
      if (l.src === 'af' && AF_TO_ESPN[l.id]) l = { src: 'es', slug: AF_TO_ESPN[l.id] };
      if (l.src === 'es') {
        const j = await cached(`es.table.${l.slug}`, 3600000, () => espn(`v2/sports/soccer/${l.slug}/standings`), { force });
        table = espnTable({ ...j, __slug: l.slug });
      } else {
        if (!cfg.key) throw new ApiErr(tr("ترتيب الدوري ده محتاج مفتاح API-Football من الإعدادات."), 'nokey');
        const season = seasonOverride || await cached('af.season.' + l.id, 7 * 86400000, async () => {
          const r = await af('leagues', { id: l.id, current: 'true' });
          const s = r[0]?.seasons?.find((x) => x.current) || r[0]?.seasons?.slice(-1)[0];
          return s ? s.year : null;
        });
        if (!season) throw new ApiErr(tr("مالقيتش موسم حالي للبطولة دي."));
        const data = await cached(`af.table.${l.id}.${season}`, 3 * 3600000, () => af('standings', { league: l.id, season }), { force });
        table = afTable(data, season);
      }
      if (alive()) renderTable(table, box);
    } catch (e) {
      if (!alive()) return;
      if (e.seasons) {
        const last = e.seasons[1];
        box.innerHTML = `<div class="empty"><h4>${tr("الموسم الحالي مش متاح في الخطة المجانية")}</h4><div>API-Football ${tr("المجاني بيعرض مواسم")} ${e.seasons[0]} ${tr("لحد")} ${last} ${tr("بس")}.</div>
          <button class="btn" data-season type="button">${tr("اعرض ترتيب موسم")} ${last}/${last + 1}</button></div>`;
        $('[data-season]', box).onclick = () => fillTable(l, box, { season: last, alive });
      } else box.innerHTML = `<div class="empty"><h4>${tr("حصلت مشكلة")}</h4><div style="white-space:pre-line">${esc(e.message)}</div></div>`;
    }
  }

  // الضغط على شكل الدوري في صفحة الماتشات
  let openLeague = null;
  function openLeagueTable(lg) {
    if (!lg) return;
    const esSlug = lg.slug || AF_TO_ESPN[lg.id];
    const l = esSlug ? { src: 'es', slug: esSlug, logo: lg.logo, name: lg.name } : { src: 'af', id: lg.id, logo: lg.logo, name: lg.name };
    openLeague = lkey(l);
    $('#lt-title').innerHTML = `${imgTag(lg.logo)}<span>${esc(leagueName(lg))}</span>`;
    showModal($('#league-modal'));
    $('#league-modal .sheet').scrollTop = 0;
    const key = openLeague;
    fillTable(l, $('#lt-table'), { alive: () => openLeague === key });
  }
  function closeLeagueTable() { $('#league-modal').classList.add('hidden'); openLeague = null; }
  $('#league-modal').addEventListener('click', (e) => { if (e.target.id === 'league-modal' || e.target.closest('[data-close]')) closeLeagueTable(); });

  function espnTable(j) {
    const kids = j.children?.length ? j.children : j.standings ? [j] : [];
    const groups = kids.map((c) => ({
      title: kids.length > 1 ? String(c.name || '').replace(/^.* - /, '').replace('Group', tr("مجموعة")) : '',
      rows: (c.standings?.entries || []).map((e) => {
        const st = Object.fromEntries((e.stats || []).map((s) => [s.name, s.value ?? s.displayValue]));
        return {
          rank: Number(st.rank) || 0, team: { key: 'es:' + e.team.id, name: e.team.shortDisplayName || e.team.displayName, logo: e.team.logos?.[0]?.href },
          p: st.gamesPlayed, w: st.wins, d: st.ties, l: st.losses, gd: Number(st.pointDifferential) || 0, pts: st.points,
          zone: e.note ? { desc: zoneAr(e.note.description), color: e.note.color } : null, form: '',
        };
      }).sort((a, b) => a.rank - b.rank),
    })).filter((g) => g.rows.length);
    return { groups, season: j.children?.[0]?.standings?.seasonDisplayName || j.season?.displayName || '', slug: j.__slug };
  }
  const ZONE_AR = [[/champions league qualif/i, tr("تصفيات دوري الأبطال")], [/champions league/i, tr("دوري الأبطال")], [/europa league qualif/i, tr("تصفيات الدوري الأوروبي")], [/europa league/i, tr("الدوري الأوروبي")],
    [/conference league qualif/i, tr("تصفيات دوري المؤتمر")], [/conference league/i, tr("دوري المؤتمر")], [/relegation play/i, tr("ملحق الهبوط")], [/relegation/i, tr("هبوط")],
    [/promotion play/i, tr("ملحق الصعود")], [/promotion/i, tr("صعود")], [/knockout|next round|qualif/i, tr("التأهل للدور الجاي")]];
  const zoneAr = (d) => { for (const [re, ar] of ZONE_AR) if (re.test(d || '')) return ar; return d; };
  function afZone(desc) {
    if (!desc) return null;
    if (/relegation/i.test(desc)) return { desc: zoneAr(desc), color: '#ff7f84' };
    if (/champions league|promotion|next round|round of 16/i.test(desc)) return { desc: zoneAr(desc), color: '#81d6ac' };
    return { desc: zoneAr(desc), color: '#4ea8ff' };
  }
  function afTable(data, season) {
    const groups = (data?.[0]?.league?.standings || []).map((g) => ({
      title: String(g[0]?.group || '').replace('Group', tr("مجموعة")),
      rows: g.map((r) => ({
        rank: r.rank, team: { key: 'af:' + r.team.id, name: r.team.name, logo: r.team.logo },
        p: r.all.played, w: r.all.win, d: r.all.draw, l: r.all.lose, gd: r.goalsDiff, pts: r.points, zone: afZone(r.description), form: r.form || '',
      })),
    }));
    if (groups.length === 1) groups[0].title = '';
    return { groups, season: `${tr("موسم")} ${season}/${season + 1}` };
  }
  function renderTable(t, box = $('#table')) {
    if (!t.groups.length) { box.innerHTML = `<div class="empty"><h4>${tr("مفيش جدول ترتيب")}</h4><div>${tr("البطولة دي ممكن تكون بنظام خروج المغلوب، أو الموسم لسه مابدأش")}.</div></div>`; return; }
    const T = teamKeys();
    const legend = new Map();
    const hasForm = t.groups.some((g) => g.rows.some((r) => r.form));
    let html = '';
    for (const g of t.groups) {
      if (g.title) html += `<div class="group-title">${esc(g.title)}</div>`;
      html += `<table><thead><tr><th>#</th><th class="tm">${tr("الفريق")}</th><th>${tr("لعب")}</th><th>${tr("ف")}</th><th>${tr("ت")}</th><th>${tr("خ")}</th><th>+/-</th><th>${tr("نقاط")}</th>${hasForm ? `<th class="f">${tr("آخر")} 5</th>` : ''}</tr></thead><tbody>`;
      for (const r of g.rows) {
        if (r.zone?.desc) legend.set(r.zone.desc, r.zone.color);
        const form = String(r.form).slice(-5).split('').filter(Boolean).map((x) => `<i class="${x}">${x === 'W' ? tr("ف") : x === 'D' ? tr("ت") : tr("خ")}</i>`).join('');
        html += `<tr data-row="${esc(r.team.key)}" class="${T.has(r.team.key) ? 'fav' : ''}"><td class="rk" style="--q:${esc(r.zone?.color || 'transparent')}">${esc(r.rank)}</td>
          <td class="tm">${teamLink(r.team, t.slug, `<div>${imgTag(r.team.logo)}<span>${esc(r.team.name)}</span></div>`)}</td>
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
  function renderLastErr() {
    const el = $('#last-err');
    if (!el) return;
    const e = ls.get('goal.lastErr', null);
    el.classList.toggle('hidden', !e);
    if (e) $('#last-err-text').textContent = `${new Date(e.t).toLocaleString(LOC)} • ${e.where}\n${e.msg}`;
  }
  function renderSettings() {
    renderLastErr();
    $('#welcome').classList.toggle('hidden', !!cfg.key || ls.get('goal.seenWelcome', false));
    ls.set('goal.seenWelcome', true);
    $('#key').value = cfg.key;
    $$('.af-only').forEach((el) => el.classList.toggle('off', !cfg.key));
    $$('#interval button').forEach((b) => b.classList.toggle('active', Number(b.dataset.v) === cfg.interval));
    $$('#lang button').forEach((b) => b.classList.toggle('active', b.dataset.v === LANG));
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
    if (!cfg.key) { box.textContent = tr("اتشال المفتاح. الدوري المصري مش هيظهر."); renderQuota(); return; }
    box.textContent = tr("بختبر المفتاح...");
    btn.disabled = true;
    try {
      const s = await af('status');
      const sub = s.subscription || {};
      const req = s.requests || {};
      if (req.limit_day != null) setQuota(req.limit_day - (req.current || 0), req.limit_day);
      box.className = 'status-box ok';
      box.innerHTML = `${tr("المفتاح شغال")} ✓<br>${tr("الخطة")}: <b>${esc(sub.plan || '—')}</b>${sub.end ? ` • ${tr("لحد")} ${esc(String(sub.end).slice(0, 10))}` : ''}<br>${tr("استخدمت النهارده")} <b>${esc(req.current ?? 0)}</b> ${tr("من")} <b>${esc(req.limit_day ?? '?')}</b> ${tr("طلب")}`;
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
        <small>${needsKey ? tr("محتاج مفتاح API-Football (تحت)") : l.src === 'af' ? tr("امبارح والنهارده وبكرة بس") : tr("أي يوم")}</small></div>
        ${i > 0 ? `<button type="button" class="act" data-up aria-label="${tr("لفوق")}">↑</button>` : ''}<button type="button" class="act" data-rm>${tr("شيل")}</button>`;
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
      b.textContent = tr("الدوري المصري (API-Football)");
      b.onclick = () => { cfg.leagues.unshift({ ...DEFAULT_LEAGUES[0] }); saveCfg(); state.days.clear(); renderLeagues(); renderCatalog(); };
      box.appendChild(b);
    }
    for (const [slug, name] of ESPN_CATALOG) {
      if (mine.has('es:' + slug)) continue;
      const b = document.createElement('button');
      b.type = 'button';
      b.className = 'cat';
      b.textContent = name;
      b.onclick = () => { cfg.leagues.push({ src: 'es', slug }); saveCfg(); state.days.clear(); renderLeagues(); renderCatalog(); toast(tr("اتضاف ") + name); };
      box.appendChild(b);
    }
  }

  $('#league-search').addEventListener('input', debounce(async (e) => {
    const q = e.target.value.trim();
    const box = $('#league-results');
    if (q.length < 3) { box.innerHTML = ''; return; }
    box.innerHTML = `<div class="hint">${tr("بدوّر")}...</div>`;
    try {
      const res = await cached('af.ls.' + q.toLowerCase(), 7 * 86400000, () => af('leagues', { search: q }));
      if (e.target.value.trim() !== q) return;
      const mine = myLeagueKeys();
      box.innerHTML = res.length ? '' : `<div class="hint">${tr("مالقيتش حاجة")}. ${tr("جرّب اسم الدولة بالإنجليزي")}.</div>`;
      for (const r of res.slice(0, 25)) {
        const l = { src: 'af', id: r.league.id, name: r.league.name, country: r.country?.name, logo: r.league.logo };
        const has = mine.has(lkey(l));
        const row = document.createElement('div');
        row.className = 'row';
        row.innerHTML = `${imgTag(l.logo)}<div class="tx"><b>${esc(leagueName(l))}</b><small>${esc(l.country || '')} • ${r.league.type === 'Cup' ? tr("كأس") : tr("دوري")}</small></div>
          <button type="button" class="act${has ? '' : ' add'}">${has ? tr("موجود") : tr("ضيف")}</button>`;
        const b = $('.act', row);
        if (!has) b.onclick = () => { cfg.leagues.push(l); saveCfg(); state.days.clear(); renderLeagues(); b.textContent = tr("موجود"); b.classList.remove('add'); b.onclick = null; };
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
      row.innerHTML = `${imgTag(t.logo)}<div class="tx"><b>${esc(t.name)}</b></div><button type="button" class="act">${tr("شيل")}</button>`;
      $('.act', row).onclick = () => { cfg.teams = cfg.teams.filter((x) => x.key !== t.key); saveCfg(); renderTeams(); };
      box.appendChild(row);
    }
  }
  $('#last-err-clear').onclick = () => { localStorage.removeItem('goal.lastErr'); renderLastErr(); };
  $$('#lang button').forEach((b) => { b.onclick = () => { if (b.dataset.v !== LANG) setLang(b.dataset.v); }; });
  $$('#interval button').forEach((b) => { b.onclick = () => { cfg.interval = Number(b.dataset.v); saveCfg(); $$('#interval button').forEach((x) => x.classList.toggle('active', x === b)); }; });


  // =====================================================================
  //                       صفحة الفريق وصفحة اللاعب
  // =====================================================================
  let zTop = 50;
  function showModal(el) { el.style.zIndex = String(++zTop); el.classList.remove('hidden'); }
  function teamLink(t, slug, inner) {
    if (!t || !t.key) return inner;
    return `<button type="button" class="team-link" data-team-key="${esc(t.key)}" data-slug="${esc(slug || '')}" data-name="${esc(t.name || '')}" data-logo="${esc(t.logo || '')}">${inner}</button>`;
  }
  document.addEventListener('click', (e) => {
    const tl = e.target.closest('[data-team-key]');
    if (tl) { e.preventDefault(); e.stopPropagation(); openTeam({ key: tl.dataset.teamKey, slug: tl.dataset.slug, name: tl.dataset.name, logo: tl.dataset.logo }); return; }
    const pp = e.target.closest('[data-pid]');
    if (pp) { e.preventDefault(); openPlayer(pp.dataset.pid, { tid: pp.dataset.tid, slug: pp.dataset.slug }); }
  }, true);
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && e.target.matches?.('[data-pid]')) openPlayer(e.target.dataset.pid, { tid: e.target.dataset.tid, slug: e.target.dataset.slug });
  });

  const POS_AR = { G: tr("حراس المرمى"), D: tr("الدفاع"), M: tr("الوسط"), F: tr("الهجوم") };
  const POS1_AR = { G: tr("حارس مرمى"), D: tr("مدافع"), M: tr("لاعب وسط"), F: tr("مهاجم") };
  const PSTAT_AR = {
    'starts-subIns': tr("أساسي (بديل)"), appearances: tr("ماتشات"), totalGoals: tr("أهداف"), goalAssists: tr("صناعة"), totalShots: tr("تسديدات"), shotsOnTarget: tr("على المرمى"),
    foulsCommitted: tr("أخطاء ارتكبها"), foulsSuffered: tr("أخطاء عليه"), yellowCards: tr("إنذارات"), redCards: tr("طرد"), offsides: tr("تسلل"), ownGoals: tr("في مرماه"),
    subIns: tr("نزل بديل"), saves: tr("تصديات"), shotsFaced: tr("تسديدات عليه"), goalsConceded: tr("أهداف دخلت فيه"), cleanSheet: tr("شباك نظيفة"),
  };
  const fmtDate = (ms) => new Date(ms).toLocaleDateString(LOC, { weekday: 'long', day: 'numeric', month: 'long' });
  const dobAr = (d) => { const m = String(d).match(/^(\d+)\/(\d+)\/(\d{4})$/); return m ? new Date(+m[3], +m[2] - 1, +m[1]).toLocaleDateString(LOC, { day: 'numeric', month: 'long', year: 'numeric' }) : d; };
  const cm = (h) => { const m = String(h || '').match(/(\d+)'\s*(\d+)?/); return m ? Math.round((Number(m[1]) * 12 + Number(m[2] || 0)) * 2.54) + tr(" سم") : h || ''; };

  const team = { key: null, slug: null, dl: null, tab: 'matches', sub: 'next', info: null };

  async function openTeam({ key, slug, name, logo }) {
    if (!key) return;
    Object.assign(team, { key, slug, dl: slug, tab: 'matches', sub: 'next', info: null, name, logo });
    showModal($('#team'));
    $('#team .sheet').scrollTop = 0;
    renderTeamShell();
    if (key.startsWith('es:')) {
      try {
        const id = key.slice(3);
        const j = await cached(`es.team.${id}.${slug}`, 3600000, () => espn(`site/v2/sports/soccer/${slug}/teams/${id}`));
        if (team.key !== key) return;
        team.info = j.team || null;
        const dl = team.info?.defaultLeague?.slug;
        if (dl) team.dl = dl;
        renderTeamShell();
      } catch { /* الصفحة بتشتغل من غير المعلومات الإضافية */ }
    }
  }
  function closeTeam() { $('#team').classList.add('hidden'); team.key = null; }
  $('#team').addEventListener('click', (e) => { if (e.target.id === 'team' || e.target.closest('[data-close]')) closeTeam(); });

  function standingAr(s) {
    if (!RTL) return s || '';
    const m = String(s || '').match(/^(\d+)\w*\s+in\s+(.+)$/i);
    if (!m) return s || '';
    return `${tr("المركز")} ${m[1]} ${tr("في")} ${ESPN_AR[team.dl] || m[2]}`;
  }

  function renderTeamShell() {
    const t = team.info;
    const name = t?.displayName || team.name || '';
    const logo = t?.logos?.[0]?.href || team.logo || '';
    const isEs = team.key.startsWith('es:');
    const fav = cfg.teams.some((x) => x.key === team.key);
    const tabs = isEs
      ? [['matches', tr("المباريات")], ['table', tr("الترتيب")], ['players', tr("اللاعبين")], ['stats', tr("الإحصائيات")], ['news', tr("الأخبار")]]
      : [['matches', tr("المباريات")], ['table', tr("الترتيب")]];
    $('#team-body').innerHTML = `
      <div class="tm-hero">
        <div class="tm-bar">
          <button class="icon-btn" data-close type="button" aria-label="${tr("رجوع")}"><svg viewBox="0 0 24 24"><path d="m9 6 6 6-6 6"/></svg></button>
          <button class="icon-btn tm-fav${fav ? ' on' : ''}" type="button" aria-label="${tr("فريق مفضل")}"><svg viewBox="0 0 24 24"><path d="M12 3.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L12 16.9l-5.2 2.7 1-5.8-4.3-4.1 5.9-.9z"/></svg></button>
        </div>
        <div class="tm-logo">${imgTag(logo)}</div>
        <h2>${esc(name)}</h2>
        <div class="tm-sum">${esc(standingAr(t?.standingSummary))}</div>
      </div>
      <div class="tm-tabs" role="tablist">${tabs.map(([k, l]) => `<button type="button" role="tab" class="tm-tab${team.tab === k ? ' active' : ''}" data-tab="${k}">${l}</button>`).join('')}</div>
      <div id="tm-pane" class="tm-pane"></div>`;
    $('.tm-fav', $('#team-body')).onclick = (e) => {
      const b = e.currentTarget;
      if (!validTeam({ key: team.key })) return;
      const has = cfg.teams.some((x) => x.key === team.key);
      cfg.teams = has ? cfg.teams.filter((x) => x.key !== team.key) : [...cfg.teams, { key: team.key, name, logo }];
      saveCfg();
      b.classList.toggle('on', !has);
      toast(has ? `${name} ${tr("اتشال من فرقك")}` : `${name} ${tr("اتضاف لفرقك")}`);
      renderMatches(); renderTeams();
    };
    $$('.tm-tab', $('#team-body')).forEach((b) => (b.onclick = () => { team.tab = b.dataset.tab; $$('.tm-tab').forEach((x) => x.classList.toggle('active', x === b)); drawTeamTab(); }));
    drawTeamTab();
  }

  function drawTeamTab() {
    const pane = $('#tm-pane');
    const key = team.key;
    const alive = () => team.key === key && $('#tm-pane') === pane;
    pane.innerHTML = '<div class="skeleton" style="margin:14px"></div>'.repeat(4);
    ({ matches: teamMatches, table: teamTable, players: teamPlayers, stats: teamStats, news: teamNews })[team.tab](pane, alive);
  }

  // ---- المباريات (القادمة / انتهت) ----
  async function teamMatches(pane, alive) {
    try {
      let next = [];
      let last = [];
      if (team.key.startsWith('es:')) {
        const id = team.key.slice(3);
        const conv = (j) => (j.events || []).map((e) => { const sl = e.league?.slug || team.slug; const f = esCompact(e, sl, e.league); f.lg.name = e.league?.name || f.lg.name; return f; });
        const [a, b] = await Promise.all([
          cached(`es.tfx.${id}`, 30 * 60000, async () => conv(await espn(`site/v2/sports/soccer/all/teams/${id}/schedule?fixture=true`))),
          cached(`es.tres.${id}`, 30 * 60000, async () => conv(await espn(`site/v2/sports/soccer/all/teams/${id}/schedule`))),
        ]);
        next = a; last = b;
      } else {
        const id = team.key.slice(3);
        const [a, b] = await Promise.allSettled([
          cached(`af.tfx.${id}`, 3 * 3600000, async () => (await af('fixtures', { team: id, next: 10, timezone: TZ })).map(afCompact)),
          cached(`af.tres.${id}`, 3 * 3600000, async () => (await af('fixtures', { team: id, last: 10, timezone: TZ })).map(afCompact)),
        ]);
        if (a.status === 'rejected' && b.status === 'rejected') throw a.reason;
        next = a.value || []; last = b.value || [];
      }
      if (!alive()) return;
      for (const f of [...next, ...last]) extraFx.set(f.id, f);
      const now = Date.now();
      next = next.filter((f) => !DONE.has(f.st) && f.ts > now - 3 * 3600000).sort((a, b) => a.ts - b.ts);
      last = last.filter((f) => DONE.has(f.st) || hasScore(f)).sort((a, b) => b.ts - a.ts);
      const draw = () => {
        const list = team.sub === 'next' ? next : last;
        let html = `<div class="seg2"><button type="button" data-sub="next" class="${team.sub === 'next' ? 'active' : ''}">${tr("القادمة")} <small>${next.length}</small></button><button type="button" data-sub="last" class="${team.sub === 'last' ? 'active' : ''}">${tr("انتهت")} <small>${last.length}</small></button></div>`;
        if (!list.length) html += `<div class="note">${team.sub === 'next' ? tr("مفيش ماتشات جاية متسجلة لسه.") : tr("مفيش نتايج متسجلة.")}</div>`;
        let lastLg = null;
        for (const f of list) {
          if (f.lg.key !== lastLg) { html += `<div class="tm-group">${imgTag(f.lg.logo)}<span>${esc(leagueName(f.lg))}</span>${f.lg.round ? `<small>${esc(roundName(f.lg.round))}</small>` : ''}</div>`; lastLg = f.lg.key; }
          html += teamMatchCard(f);
        }
        pane.innerHTML = html;
        $$('.seg2 button', pane).forEach((b) => (b.onclick = () => { team.sub = b.dataset.sub; draw(); }));
        $$('.tm-card', pane).forEach((b) => (b.onclick = () => openMatch(b.dataset.id)));
      };
      draw();
    } catch (e) {
      if (alive()) pane.innerHTML = `<div class="note" style="white-space:pre-line">${esc(e.message)}${team.key.startsWith('af:') ? tr("\nماتشات فرق الدوري المصري بتيجي من API-Football، والخطة المجانية ممكن ماتسمحش بالطلب ده.") : ''}</div>`;
    }
  }
  function teamMatchCard(f) {
    const done = hasScore(f);
    const me = (t) => (t.key === team.key ? ' me' : '');
    const res = done && f.h.w != null ? ((f.h.key === team.key ? f.h.w : f.a.w) ? 'W' : (f.h.w === false && f.a.w === false) || f.g[0] === f.g[1] ? 'D' : 'L') : '';
    const mid = done
      ? `<div class="score">${scoreTxt(f.g)}</div><div class="st">${esc(new Date(f.ts).toLocaleDateString('en-CA'))}</div>`
      : `<div class="time">${hhmm(f.ts)}</div><div class="st">${esc(fmtDate(f.ts))}</div>`;
    return `<button type="button" class="tm-card${LIVE.has(f.st) ? ' live' : ''}" data-id="${esc(f.id)}">
      <div class="team${me(f.h)}">${imgTag(f.h.logo)}<span>${esc(f.h.name)}</span></div>
      <div class="mid">${mid}${res ? `<i class="res ${res}">${res === 'W' ? tr("فوز") : res === 'D' ? tr("تعادل") : tr("خسارة")}</i>` : ''}</div>
      <div class="team away${me(f.a)}">${imgTag(f.a.logo)}<span>${esc(f.a.name)}</span></div></button>`;
  }

  // ---- الترتيب ----
  async function teamTable(pane, alive) {
    let l;
    if (team.key.startsWith('es:')) l = { src: 'es', slug: team.dl || team.slug };
    else { const af0 = cfg.leagues.find((x) => x.src === 'af'); l = { src: 'af', id: af0?.id || 233 }; }
    pane.innerHTML = '<div class="table-box tm-tbl"></div>';
    const box = $('.tm-tbl', pane);
    await fillTable(l, box, { alive });
    const row = box.querySelector(`tr[data-row="${CSS.escape(team.key)}"]`);
    if (row) { row.classList.add('me'); setTimeout(() => row.scrollIntoView({ block: 'center', behavior: 'smooth' }), 50); }
  }

  // ---- اللاعبين ----
  function rosterStats(a) {
    const out = {};
    for (const c of a?.statistics?.splits?.categories || []) for (const x of c.stats || []) out[x.name] = x.value ?? Number(x.displayValue);
    return out;
  }
  const rosterOf = (slug, id) => cached(`es.roster.${slug}.${id}`, 6 * 3600000, async () => (await espn(`site/v2/sports/soccer/${slug}/teams/${id}/roster`)).athletes || []);
  async function teamPlayers(pane, alive) {
    try {
      const id = team.key.slice(3);
      const slug = team.dl || team.slug;
      const list = await rosterOf(slug, id);
      if (!alive()) return;
      if (!list.length) { pane.innerHTML = `<div class="note">${tr("مفيش قايمة لاعبين متاحة")}.</div>`; return; }
      const groups = { G: [], D: [], M: [], F: [] };
      for (const a of list) (groups[a.position?.abbreviation] || groups.M).push(a);
      let html = '';
      for (const [k, arr] of Object.entries(groups)) {
        if (!arr.length) continue;
        arr.sort((a, b) => (Number(a.jersey) || 99) - (Number(b.jersey) || 99));
        html += `<div class="tm-group"><span>${POS_AR[k]}</span><small>${arr.length}</small></div><div class="plist">`;
        for (const a of arr) {
          const st = rosterStats(a);
          const chips = k === 'G'
            ? [[tr("ماتش"), st.appearances], [tr("تصدي"), st.saves], [tr("دخل فيه"), st.goalsConceded]]
            : [[tr("ماتش"), st.appearances], [tr("جون"), st.totalGoals], [tr("صناعة"), st.goalAssists]];
          html += `<button type="button" class="prow" data-pid="${esc(a.id)}" data-tid="${esc(id)}" data-slug="${esc(slug)}">
            <span class="pno">${esc(a.jersey ?? '')}</span>
            ${photoSpan(a.id, a.displayName, a.headshot?.href)}
            <span class="ptx"><b>${esc(a.displayName)}</b><small>${a.flag?.href ? `<img src="${esc(a.flag.href)}" alt="" loading="lazy" onerror="this.remove()">` : ''}${esc(a.citizenship || '')}${a.age ? ` • ${esc(a.age)} ${tr("سنة")}` : ''}</small></span>
            <span class="pst">${chips.filter(([, v]) => v != null && !Number.isNaN(v)).map(([l, v]) => `<i><b>${esc(v)}</b>${l}</i>`).join('')}</span></button>`;
        }
        html += '</div>';
      }
      pane.innerHTML = html;
      fillPhotos(pane);
    } catch (e) {
      if (alive()) pane.innerHTML = `<div class="note">${esc(e.message)}</div>`;
    }
  }

  // ---- الإحصائيات ----
  async function teamStats(pane, alive) {
    const t = team.info;
    const rec = Object.fromEntries((t?.record?.items?.[0]?.stats || []).map((x) => [x.name, x.value]));
    let top = [];
    try {
      const list = await rosterOf(team.dl || team.slug, team.key.slice(3));
      top = list.map((a) => ({ a, s: rosterStats(a) }));
    } catch { /* من غير الهدافين */ }
    if (!alive()) return;
    if (!Object.keys(rec).length && !top.length) { pane.innerHTML = `<div class="note">${tr("مفيش إحصائيات متاحة للفريق ده")}.</div>`; return; }
    const box = (v, l, cls = '') => `<div class="sbox ${cls}"><b>${esc(v ?? '—')}</b><span>${l}</span></div>`;
    let html = '';
    if (Object.keys(rec).length) {
      const gd = rec.pointDifferential;
      html += `<div class="tm-group"><span>${esc(ESPN_AR[team.dl] || tr("الدوري"))}</span><small>${esc(t?.standingSummary ? standingAr(t.standingSummary) : '')}</small></div>
        <div class="sgrid">${box(rec.points, tr("نقطة"), 'big')}${box(rec.gamesPlayed, tr("ماتش"))}${box(rec.wins, tr("فوز"), 'w')}${box(rec.ties, tr("تعادل"), 'd')}${box(rec.losses, tr("خسارة"), 'l')}
        ${box(rec.pointsFor, tr("جون له"))}${box(rec.pointsAgainst, tr("جون عليه"))}${box(gd > 0 ? '+' + gd : gd, tr("الفارق"))}</div>
        <div class="hsplit">
          <div><h4>${tr("في ملعبه")}</h4><p>${esc(rec.homeWins ?? 0)} ${tr("فوز")} • ${esc(rec.homeTies ?? 0)} ${tr("تعادل")} • ${esc(rec.homeLosses ?? 0)} ${tr("خسارة")}</p><p class="muted2">${esc(rec.homePointsFor ?? 0)} ${tr("له")} • ${esc(rec.homePointsAgainst ?? 0)} ${tr("عليه")}</p></div>
          <div><h4>${tr("برة ملعبه")}</h4><p>${esc(rec.awayWins ?? 0)} ${tr("فوز")} • ${esc(rec.awayTies ?? 0)} ${tr("تعادل")} • ${esc(rec.awayLosses ?? 0)} ${tr("خسارة")}</p><p class="muted2">${esc(rec.awayPointsFor ?? 0)} ${tr("له")} • ${esc(rec.awayPointsAgainst ?? 0)} ${tr("عليه")}</p></div>
        </div>`;
    }
    const leaders = (stat, label) => {
      const arr = top.filter((x) => (x.s[stat] || 0) > 0).sort((a, b) => b.s[stat] - a.s[stat]).slice(0, 5);
      if (!arr.length) return '';
      return `<div class="tm-group"><span>${label}</span></div><div class="plist">${arr.map((x) => `<button type="button" class="prow" data-pid="${esc(x.a.id)}" data-tid="${esc(team.key.slice(3))}" data-slug="${esc(team.dl || team.slug)}">
        ${photoSpan(x.a.id, x.a.displayName, x.a.headshot?.href)}
        <span class="ptx"><b>${esc(x.a.displayName)}</b><small>${esc(POS1_AR[x.a.position?.abbreviation] || '')}</small></span><span class="lead">${esc(x.s[stat])}</span></button>`).join('')}</div>`;
    };
    setTimeout(() => fillPhotos(pane), 0);
    html += leaders('totalGoals', tr("الهدافين")) + leaders('goalAssists', tr("صناع الأهداف"));
    pane.innerHTML = html;
  }

  // ---- الأخبار ----
  async function teamNews(pane, alive) {
    try {
      const id = team.key.slice(3);
      const slug = team.dl || team.slug;
      const j = await cached(`es.news.${id}`, 30 * 60000, () => espn(`site/v2/sports/soccer/${slug}/news?team=${id}`), { persist: false });
      if (!alive()) return;
      const arts = j.articles || [];
      if (!arts.length) { pane.innerHTML = `<div class="note">${tr("مفيش أخبار دلوقتي")}.</div>`; return; }
      pane.innerHTML = `<p class="hint" style="padding:10px 16px 0">${tr("الأخبار من")} ESPN ${tr("بالإنجليزي")}.</p>` + arts.map((a) => `<a class="news" href="${esc(a.links?.web?.href || '#')}" target="_blank" rel="noopener">
        ${a.images?.[0]?.url ? `<img src="${esc(a.images[0].url)}" alt="" loading="lazy" onerror="this.remove()">` : ''}
        <div><b dir="auto">${esc(a.headline)}</b><small>${a.published ? esc(new Date(a.published).toLocaleDateString(LOC, { day: 'numeric', month: 'long' })) : ''}</small></div></a>`).join('');
    } catch (e) {
      if (alive()) pane.innerHTML = `<div class="note">${esc(e.message)}</div>`;
    }
  }

  // ---- صفحة اللاعب ----
  let openPid = null;
  async function openPlayer(pid, { tid, slug } = {}) {
    if (!pid) return;
    openPid = pid;
    showModal($('#player'));
    const body = $('#player-body');
    body.innerHTML = `<div class="note">${tr("جاري التحميل")}...</div>`;
    try {
      const j = await cached(`es.ath.${pid}`, 6 * 3600000, () => espn(`common/v3/sports/soccer/athletes/${pid}`));
      const at = j.athlete || j;
      let rs = {};
      const teamId = tid || at.team?.id;
      const sl = slug || team.dl || null;
      if (teamId && sl) {
        try { const list = await rosterOf(sl, teamId); rs = rosterStats(list.find((x) => String(x.id) === String(pid))); } catch { /* من غير تفاصيل */ }
      }
      if (openPid !== pid) return;
      const summary = at.statsSummary?.statistics || [];
      const seen = new Set();
      const cells = [];
      for (const x of summary) { seen.add(x.name); cells.push([PSTAT_AR[x.name] || x.displayName, x.displayValue]); }
      for (const [k, v] of Object.entries(rs)) if (!seen.has(k) && PSTAT_AR[k] && v != null && !(at.position?.abbreviation !== 'G' && ['saves', 'shotsFaced', 'goalsConceded'].includes(k))) cells.push([PSTAT_AR[k], v]);
      const tm = at.team;
      body.innerHTML = `
        <div class="pl-hero">
          <div class="tm-bar"><button class="icon-btn" data-close type="button" aria-label="${tr("رجوع")}"><svg viewBox="0 0 24 24"><path d="m9 6 6 6-6 6"/></svg></button></div>
          <div class="pl-photo" data-ph="${esc(pid)}" data-nm="${esc(at.displayName)}" data-hs="${esc(at.headshot?.href || '')}" data-one="1">${photoImg(pid)}<span>${esc(at.jersey ?? '')}</span></div>
          <h2 dir="auto">${esc(at.displayName)}</h2>
          <div class="tm-sum">${esc(POS1_AR[at.position?.abbreviation] || at.position?.displayName || '')}${tm ? ' • ' : ''}${tm ? teamLink({ key: 'es:' + tm.id, name: tm.displayName, logo: tm.logos?.[0]?.href || tm.logo }, sl || team.slug, `<span class="inl">${esc(tm.displayName)}</span>`) : ''}</div>
        </div>
        <div class="pinfo">
          ${at.age ? `<div><b>${esc(at.age)}</b><span>${tr("سنة")}</span></div>` : ''}
          ${at.displayHeight ? `<div><b>${esc(cm(at.displayHeight))}</b><span>${tr("الطول")}</span></div>` : ''}
          ${at.citizenship ? `<div><b>${at.flag?.href ? `<img src="${esc(at.flag.href)}" alt="" onerror="this.remove()">` : ''}${esc(at.citizenship)}</b><span>${tr("الجنسية")}</span></div>` : ''}
          ${at.displayDOB ? `<div><b>${esc(dobAr(at.displayDOB))}</b><span>${tr("تاريخ الميلاد")}</span></div>` : ''}
        </div>
        ${cells.length ? `<div class="tm-group"><span>${esc(String(at.statsSummary?.displayName || tr("إحصائيات الموسم")).replace(/^(\d{4}-\d{2}) (.+) Stats$/, tr("إحصائيات $2 $1")))}</span></div>
          <div class="sgrid">${cells.map(([l, v]) => `<div class="sbox"><b>${esc(v)}</b><span>${esc(l)}</span></div>`).join('')}</div>` : `<div class="note">${tr("مفيش إحصائيات للاعب ده في الموسم ده")}.</div>`}`;
      fillPhotos(body);
    } catch (e) {
      if (openPid === pid) body.innerHTML = `<div class="tm-bar"><button class="icon-btn" data-close type="button" aria-label="${tr("رجوع")}"><svg viewBox="0 0 24 24"><path d="m9 6 6 6-6 6"/></svg></button></div><div class="note">${esc(e.message)}</div>`;
    }
  }
  // ---- صور اللاعبين: ESPN لو موجودة، وإلا Wikipedia (مع كاش 30 يوم) ----
  const PH_KEY = 'goal.ph';
  const phStore = ls.get(PH_KEY, {});
  let phBackoff = 0;
  const phGet = (id) => { const r = phStore[id]; if (!r) return undefined; const ttl = r.u ? 30 * 86400000 : 7 * 86400000; return Date.now() - r.t < ttl ? r.u : undefined; };
  const phSet = (id, u) => { phStore[id] = { u: u || '', t: Date.now() }; };
  const phSave = debounce(() => { if (!ls.set(PH_KEY, phStore)) { /* المساحة خلصت: نمسح القديم */ for (const k of Object.keys(phStore).slice(0, 300)) delete phStore[k]; ls.set(PH_KEY, phStore); } }, 500);
  function photoImg(id) { const u = phGet(id); return u ? `<img src="${esc(u)}" alt="" loading="lazy" referrerpolicy="no-referrer" onerror="this.remove()">` : ''; }
  function photoSpan(id, name, hs) {
    if (hs && phGet(id) === undefined) phSet(id, hs);
    return `<span class="pimg" data-ph="${esc(id)}" data-nm="${esc(name || '')}">${photoImg(id)}</span>`;
  }
  async function wiki(params) {
    if (Date.now() < phBackoff) throw new Error('backoff');
    const q = new URLSearchParams({ action: 'query', format: 'json', formatversion: '2', origin: '*', redirects: '1', prop: 'pageimages|pageprops', ppprop: 'disambiguation', piprop: 'thumbnail', pithumbsize: '240', ...params });
    const r = await fetch('https://en.wikipedia.org/w/api.php?' + q);
    if (r.status === 429) { phBackoff = Date.now() + 5 * 60000; throw new Error('rate'); }
    if (!r.ok) throw new Error('wiki ' + r.status);
    return (await r.json()).query || {};
  }
  const goodPage = (pg) => pg && pg.thumbnail && !(pg.pageprops && 'disambiguation' in pg.pageprops) ? pg.thumbnail.source : '';
  async function wikiBatch(people) { // people: [{id, name}] — طلب واحد لحد 50 لاعب
    for (let i = 0; i < people.length; i += 50) {
      const chunk = people.slice(i, i + 50);
      const d = await wiki({ titles: chunk.map((x) => x.name).join('|'), pilimit: '50' });
      const map = new Map();
      for (const n of d.normalized || []) map.set(n.from, n.to);
      for (const n of d.redirects || []) map.set(n.from, n.to);
      const byTitle = new Map((d.pages || []).map((pg) => [pg.title, pg]));
      for (const x of chunk) {
        let t = x.name;
        for (let k = 0; k < 3 && map.has(t); k++) t = map.get(t);
        phSet(x.id, goodPage(byTitle.get(t)));
      }
    }
    phSave();
  }
  async function wikiOne(name) {
    const d = await wiki({ generator: 'search', gsrsearch: `${name} footballer`, gsrlimit: '1' });
    return goodPage((d.pages || [])[0]);
  }
  async function fillPhotos(root) {
    const els = [...root.querySelectorAll('[data-ph]')].filter((el) => !el.querySelector('img'));
    if (!els.length) return;
    const put = () => els.forEach((el) => { const u = phGet(el.dataset.ph); if (u && !el.querySelector('img')) el.insertAdjacentHTML('afterbegin', photoImg(el.dataset.ph)); });
    els.forEach((el) => { if (el.dataset.hs && phGet(el.dataset.ph) === undefined) phSet(el.dataset.ph, el.dataset.hs); });
    put();
    try {
      const one = els.find((el) => el.dataset.one);
      if (one && !phGet(one.dataset.ph)) { phSet(one.dataset.ph, await wikiOne(one.dataset.nm)); phSave(); }
      const need = els.filter((el) => !el.dataset.one && phGet(el.dataset.ph) === undefined && el.dataset.nm).map((el) => ({ id: el.dataset.ph, name: el.dataset.nm }));
      if (need.length) await wikiBatch([...new Map(need.map((x) => [x.id, x])).values()]);
    } catch { /* من غير صور */ }
    put();
  }

  function closePlayer() { $('#player').classList.add('hidden'); openPid = null; }
  $('#player').addEventListener('click', (e) => { if (e.target.id === 'player' || e.target.closest('[data-close]')) closePlayer(); });

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
    if (view === 'matches') {
      renderDates(); loadDay(state.date);
      if (!ls.get('goal.swipeHint', false) && matchMedia('(pointer: coarse)').matches) { ls.set('goal.swipeHint', true); setTimeout(() => toast(tr("اسحب يمين أو شمال عشان تغيّر اليوم")), 1200); }
    }
    if (view === 'table') { renderTableChips(); loadTable(); }
    if (view === 'settings') renderSettings();
  }
  $$('#nav .nav-btn').forEach((b) => (b.onclick = () => go(b.dataset.view)));

  window.__back = () => { // زرار الرجوع في الأندرويد: بيقفل أعلى نافذة
    const top = $$('.modal:not(.hidden)').sort((a, b) => (Number(b.style.zIndex) || 50) - (Number(a.style.zIndex) || 50))[0];
    if (top) { ({ match: closeMatch, 'league-modal': closeLeagueTable, team: closeTeam, player: closePlayer })[top.id]?.(); return true; }
    if (state.view !== 'matches') { go('matches'); return true; }
    return false;
  };
  document.addEventListener('keydown', (e) => { if (e.key === 'Escape') window.__back(); });

  setInterval(() => { // يوم جديد؟ نرجّع لـ "النهارده"
    if (state.view === 'matches' && state.date < today() && $('#dates .date.active b')?.textContent === tr("النهارده")) { state.date = today(); renderDates(); loadDay(state.date); }
  }, 60000);

  renderQuota();
  go(ls.get('goal.seenWelcome', false) || cfg.key ? 'matches' : 'settings');
})();
