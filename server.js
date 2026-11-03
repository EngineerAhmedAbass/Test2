// مشغل IPTV - سيرفر محلي بسيط بدون أي مكتبات خارجية (Node.js 18+)
// وظيفته: يعرض واجهة البرنامج + يعمل Proxy للطلبات عشان نتفادى مشاكل CORS و HTTP/HTTPS في المتصفح.

const http = require('http');
const fs = require('fs');
const path = require('path');
const { Readable } = require('stream');

const PORT = Number(process.env.PORT) || 3000;
const HOST = process.env.HOST || '127.0.0.1';
const PUBLIC_DIR = path.join(__dirname, 'public');
const USER_AGENT = process.env.IPTV_USER_AGENT || 'VLC/3.0.20 LibVLC/3.0.20';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.json': 'application/json; charset=utf-8',
};

function sendJson(res, status, obj) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(obj));
}

function isHttpUrl(u) {
  try {
    const p = new URL(u);
    return p.protocol === 'http:' || p.protocol === 'https:';
  } catch {
    return false;
  }
}

function serveStatic(req, res, pathname) {
  let file = pathname === '/' ? '/index.html' : pathname;
  file = path.normalize(path.join(PUBLIC_DIR, decodeURIComponent(file)));
  if (!file.startsWith(PUBLIC_DIR)) {
    res.writeHead(403);
    return res.end('Forbidden');
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404);
      return res.end('Not found');
    }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
}

async function upstreamFetch(url, req, extraHeaders = {}) {
  const controller = new AbortController();
  req.on('close', () => controller.abort());
  const headers = { 'User-Agent': USER_AGENT, Accept: '*/*', ...extraHeaders };
  return fetch(url, { headers, redirect: 'follow', signal: controller.signal });
}

// ---------- Xtream Codes API ----------
async function handleXtream(req, res, params) {
  const server = (params.get('server') || '').replace(/\/+$/, '');
  if (!isHttpUrl(server)) return sendJson(res, 400, { error: 'عنوان السيرفر غير صحيح' });

  const api = new URL(server + '/player_api.php');
  for (const [k, v] of params) {
    if (k !== 'server') api.searchParams.set(k, v);
  }
  try {
    const r = await upstreamFetch(api.toString(), req);
    const text = await r.text();
    res.writeHead(r.status, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(text);
  } catch (e) {
    sendJson(res, 502, { error: 'تعذر الاتصال بالسيرفر: ' + e.message });
  }
}

// ---------- M3U playlist ----------
async function handleM3u(req, res, params) {
  const url = params.get('url');
  if (!isHttpUrl(url)) return sendJson(res, 400, { error: 'رابط القائمة غير صحيح' });
  try {
    const r = await upstreamFetch(url, req);
    if (!r.ok) return sendJson(res, r.status, { error: 'السيرفر رد بخطأ ' + r.status });
    const text = await r.text();
    res.writeHead(200, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(text);
  } catch (e) {
    sendJson(res, 502, { error: 'تعذر تحميل القائمة: ' + e.message });
  }
}

// ---------- Stream proxy ----------
function proxify(u) {
  return '/proxy?url=' + encodeURIComponent(u);
}

function rewritePlaylist(text, baseUrl) {
  const abs = (u) => {
    try {
      return new URL(u, baseUrl).toString();
    } catch {
      return u;
    }
  };
  return text
    .split(/\r?\n/)
    .map((line) => {
      const t = line.trim();
      if (!t) return line;
      if (t.startsWith('#')) {
        return line.replace(/URI="([^"]+)"/g, (_, u) => `URI="${proxify(abs(u))}"`);
      }
      return proxify(abs(t));
    })
    .join('\n');
}

async function handleProxy(req, res, params) {
  const url = params.get('url');
  if (!isHttpUrl(url)) {
    res.writeHead(400);
    return res.end('Bad url');
  }
  const extra = {};
  if (req.headers.range) extra.Range = req.headers.range;

  let r;
  try {
    r = await upstreamFetch(url, req, extra);
  } catch (e) {
    if (!res.headersSent) {
      res.writeHead(502);
      res.end('Upstream error: ' + e.message);
    }
    return;
  }

  const finalUrl = r.url || url;
  const ct = (r.headers.get('content-type') || '').toLowerCase();
  const isPlaylist =
    ct.includes('mpegurl') || /\.m3u8?(\?|$)/i.test(new URL(finalUrl).pathname + new URL(finalUrl).search);

  if (isPlaylist && r.ok) {
    const text = await r.text();
    if (text.trimStart().startsWith('#EXTM3U')) {
      res.writeHead(200, {
        'Content-Type': 'application/vnd.apple.mpegurl',
        'Cache-Control': 'no-cache',
      });
      return res.end(rewritePlaylist(text, finalUrl));
    }
    res.writeHead(r.status, { 'Content-Type': ct || 'text/plain' });
    return res.end(text);
  }

  const headers = { 'Cache-Control': 'no-cache' };
  for (const h of ['content-type', 'content-length', 'content-range', 'accept-ranges']) {
    const v = r.headers.get(h);
    if (v) headers[h] = v;
  }
  res.writeHead(r.status, headers);
  if (!r.body) return res.end();
  const body = Readable.fromWeb(r.body);
  body.on('error', () => res.destroy());
  res.on('close', () => body.destroy());
  body.pipe(res);
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  const p = u.pathname;
  if (p === '/api/xtream') return handleXtream(req, res, u.searchParams);
  if (p === '/api/m3u') return handleM3u(req, res, u.searchParams);
  if (p === '/proxy') return handleProxy(req, res, u.searchParams);
  if (req.method === 'GET') return serveStatic(req, res, p);
  res.writeHead(405);
  res.end();
});

server.listen(PORT, HOST, () => {
  console.log('==========================================');
  console.log('  مشغل IPTV شغال 🎬');
  console.log(`  افتح المتصفح على: http://localhost:${PORT}`);
  console.log('==========================================');
});
