// GOAL — نتايج الماتشات: سيرفر محلي صغير بدون مكتبات (Node.js 18+)
// بيعرض الواجهة ويوصّل طلبات /api/football/* لـ API-Football بمفتاحك.

const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT) || 3100;
const HOST = process.env.HOST || '127.0.0.1';
const BASE = (process.env.FOOTBALL_API_BASE || 'https://v3.football.api-sports.io').replace(/\/+$/, '');
const PUBLIC_DIR = path.join(__dirname, 'public');
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.svg': 'image/svg+xml', '.png': 'image/png' };
const PASS_HEADERS = ['x-ratelimit-requests-limit', 'x-ratelimit-requests-remaining', 'x-ratelimit-limit', 'x-ratelimit-remaining'];

function serveStatic(res, pathname) {
  const file = path.normalize(path.join(PUBLIC_DIR, decodeURIComponent(pathname === '/' ? '/index.html' : pathname)));
  if (!file.startsWith(PUBLIC_DIR)) { res.writeHead(403); return res.end(); }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end('Not found'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
}

async function proxy(req, res, u) {
  const endpoint = u.pathname.slice('/api/football/'.length).replace(/[^a-z/]/gi, '');
  const key = u.searchParams.get('_k') || '';
  u.searchParams.delete('_k');
  const qs = u.searchParams.toString();
  try {
    const r = await fetch(`${BASE}/${endpoint}${qs ? '?' + qs : ''}`, { headers: { 'x-apisports-key': key, Accept: 'application/json' } });
    const headers = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' };
    for (const h of PASS_HEADERS) { const v = r.headers.get(h); if (v != null) headers[h] = v; }
    res.writeHead(r.status, headers);
    res.end(await r.text());
  } catch (e) {
    res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ errors: { network: 'تعذر الاتصال بـ API-Football: ' + e.message } }));
  }
}

http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  if (u.pathname.startsWith('/api/football/')) return proxy(req, res, u);
  if (req.method === 'GET') return serveStatic(res, u.pathname);
  res.writeHead(405); res.end();
}).listen(PORT, HOST, () => {
  console.log(`GOAL شغال ⚽  افتح: http://localhost:${PORT}`);
});
