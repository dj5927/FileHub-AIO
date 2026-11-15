'use strict';

const http = require('node:http');
const {createHash, timingSafeEqual} = require('node:crypto');

const DEFAULT_TTL_MS = 120_000;
const MAX_PACKET_BYTES = 4 * 1024 * 1024 + 128;
const MAX_SESSIONS = 64;
const MAX_TOTAL_BYTES = 16 * 1024 * 1024;
const MAX_CREATES_PER_MINUTE = 30;
const idPattern = /^[A-Za-z0-9_-]{22}$/;
const tokenPattern = /^[A-Za-z0-9_-]{43}$/;
// Camera scanners recognize HTTPS URLs. The invitation and encryption key
// are only inside the URL fragment, which browsers never send via HTTP.
const PAIR_PAGE = [
  '<!doctype html><html lang="ko"><head><meta charset="utf-8">',
  '<meta name="viewport" content="width=device-width,initial-scale=1">',
  '<title>FileHub AIO · TV 설정 보내기</title>',
  '<style>body{font-family:system-ui,sans-serif;background:#11171c;color:#eef2f6;',
  'padding:28px;max-width:450px;margin:30px auto;line-height:1.7}',
  'a,button{display:none;background:#4ac1b3;color:#10212a;padding:14px 24px;',
  'text-decoration:none;border:0;border-radius:12px;font-weight:700;',
  'margin-bottom:8px}small{color:#b7c3ca}</style>',
  '</head><body><h2>FileHub AIO</h2><p id="help">TV 연결을 확인하는 중…</p>',
  '<p><a id="open" href="#">FileHub에서 열기</a></p>',
  '<p><button id="copy" type="button">연결 주소 복사</button></p>',
  '<small>앱을 실행한 뒤 TV에 표시된 6자리 확인 코드를 비교하고,',
  ' 본인의 TV일 때만 암호화 전송을 승인하세요.</small>',
  '<script>(function(){try{',
  'var text=decodeURIComponent(location.hash.slice(1));',
  'var link=new URL(text);',
  'if(link.protocol!=="filehubaio:"||link.hostname!=="relay")throw Error();',
  'var target=document.getElementById("open");target.href=text;',
  'target.style.display="inline-block";',
  'var copy=document.getElementById("copy");',
  'copy.style.display="inline-block";',
  'copy.onclick=function(){navigator.clipboard.writeText(text).then(',
  'function(){copy.textContent="복사 완료 · FileHub에 붙여넣기";},',
  'function(){copy.textContent="브라우저 복사 권한을 확인해 주세요.";});};',
  'document.getElementById("help").textContent="아래 버튼으로 FileHub 앱을 여세요.";',
  'history.replaceState(null,"","/pair");',
  '}catch(e){document.getElementById("help").textContent=',
  '"QR이 유효하지 않습니다. TV에서 새 QR을 만들어 주세요.";',
  '}})();</script></body></html>'
].join('\n');

function sha256(token) {
  return createHash('sha256').update(token, 'ascii').digest();
}

function tokenMatches(actual, hash) {
  return tokenPattern.test(actual || '') &&
    timingSafeEqual(sha256(actual), hash);
}

function bearer(req) {
  const authorization = req.headers.authorization || '';
  return authorization.startsWith('Bearer ') ? authorization.slice(7) : '';
}

async function readBody(req, maxBytes) {
  const pieces = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > maxBytes) {
      const error = new Error('Payload too large');
      error.statusCode = 413;
      throw error;
    }
    pieces.push(chunk);
  }
  return Buffer.concat(pieces, total);
}

function createRelayServer(options = {}) {
  const ttlMs = options.ttlMs || DEFAULT_TTL_MS;
  const now = options.now || Date.now;
  const sessions = new Map();
  const rate = new Map();
  let storedBytes = 0;
  const MAX_AGE_MS = 60_000;

  function erase(id) {
    const item = sessions.get(id);
    if (!item) return;
    if (item.packet) {
      storedBytes -= item.packet.length;
      item.packet.fill(0);
    }
    sessions.delete(id);
  }

  function sweep() {
    const time = now();
    for (const [id, session] of sessions) {
      if (session.expiresAt <= time) erase(id);
    }
    for (const [address, state] of rate) {
      if (time - state.started >= MAX_AGE_MS) rate.delete(address);
    }
  }

  function json(res, code, payload) {
    const data = Buffer.from(JSON.stringify(payload));
    res.writeHead(code, {
      'Content-Type': 'application/json; charset=utf-8',
      'Content-Length': data.length,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff'
    });
    res.end(data);
  }

  function empty(res, code) {
    res.writeHead(code, {'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff'});
    res.end();
  }

  function allowedIp(address) {
    const time = now();
    let state = rate.get(address);
    if (!state || time - state.started >= MAX_AGE_MS) {
      state = {started: time, count: 0};
      rate.set(address, state);
    }
    state.count++;
    return state.count <= MAX_CREATES_PER_MINUTE;
  }

  async function serve(req, res) {
    sweep();
    const path = new URL(req.url || '/', 'http://localhost').pathname;
    if (req.method === 'GET' && path === '/health') {
      return json(res, 200, {status: 'ok'});
    }
    if (req.method === 'GET' && path === '/pair') {
      const page = Buffer.from(PAIR_PAGE, 'utf8');
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Content-Length': page.length,
        'Cache-Control': 'no-store',
        'Referrer-Policy': 'no-referrer',
        'X-Content-Type-Options': 'nosniff',
        'Content-Security-Policy':
          "default-src 'none'; script-src 'unsafe-inline'; " +
          "style-src 'unsafe-inline'; base-uri 'none'; " +
          "frame-ancestors 'none'; form-action 'none'"
      });
      return res.end(page);
    }

    if (req.method === 'POST' && path === '/v1/sessions') {
      if (!allowedIp(req.socket.remoteAddress || 'unknown')) {
        return json(res, 429, {error: 'Try again later'});
      }
      if (sessions.size >= MAX_SESSIONS) {
        return json(res, 503, {error: 'Relay busy'});
      }
      if (!(req.headers['content-type'] || '').startsWith('application/json')) {
        return json(res, 415, {error: 'Expected JSON'});
      }
      let request;
      try {
        const data = await readBody(req, 1024);
        request = JSON.parse(data.toString('utf8'));
      } catch (error) {
        return json(res, error.statusCode || 400, {error: 'Invalid session'});
      }
      const {id, ownerToken, sendToken} = request;
      if (!idPattern.test(id || '') ||
          !tokenPattern.test(ownerToken || '') ||
          !tokenPattern.test(sendToken || '') ||
          sessions.has(id)) {
        return json(res, 400, {error: 'Invalid session'});
      }
      sessions.set(id, {
        expiresAt: now() + ttlMs,
        ownerHash: sha256(ownerToken),
        writerHash: sha256(sendToken),
        packet: null
      });
      return json(res, 201, {expiresIn: Math.ceil(ttlMs / 1000)});
    }

    const match = path.match(/^\/v1\/sessions\/([A-Za-z0-9_-]{22})(\/payload)?$/);
    if (!match) return json(res, 404, {error: 'Not found'});
    const id = match[1];
    const payloadRoute = match[2] === '/payload';
    const session = sessions.get(id);
    if (!session || session.expiresAt <= now()) {
      erase(id);
      return json(res, 404, {error: 'Session unavailable'});
    }

    if (req.method === 'DELETE' && !payloadRoute) {
      if (!tokenMatches(bearer(req), session.ownerHash)) {
        return json(res, 404, {error: 'Session unavailable'});
      }
      erase(id);
      return empty(res, 204);
    }

    if (req.method === 'POST' && payloadRoute) {
      if (!tokenMatches(bearer(req), session.writerHash)) {
        return json(res, 404, {error: 'Session unavailable'});
      }
      if (session.packet) return json(res, 409, {error: 'Already sent'});
      if (req.headers['content-type'] !== 'application/octet-stream') {
        return json(res, 415, {error: 'Expected encrypted bytes'});
      }
      let packet;
      try {
        packet = await readBody(req, MAX_PACKET_BYTES);
      } catch (error) {
        return json(res, error.statusCode || 400, {error: 'Invalid payload'});
      }
      if (packet.length < 29) {
        packet.fill(0);
        return json(res, 400, {error: 'Invalid payload'});
      }
      if (storedBytes + packet.length > MAX_TOTAL_BYTES) {
        packet.fill(0);
        return json(res, 503, {error: 'Relay busy'});
      }
      session.packet = packet;
      storedBytes += packet.length;
      return empty(res, 202);
    }

    if (req.method === 'GET' && payloadRoute) {
      if (!tokenMatches(bearer(req), session.ownerHash)) {
        return json(res, 404, {error: 'Session unavailable'});
      }
      if (!session.packet) return empty(res, 204);
      // Copy before zeroing the retained buffer: response I/O is asynchronous.
      const copy = Buffer.from(session.packet);
      erase(id);
      res.writeHead(200, {
        'Content-Type': 'application/octet-stream',
        'Content-Length': copy.length,
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff'
      });
      return res.end(copy);
    }

    return json(res, 405, {error: 'Method not allowed'});
  }

  const server = http.createServer((req, res) => {
    serve(req, res).catch(() => {
      if (!res.headersSent) json(res, 500, {error: 'Temporary relay error'});
      else res.end();
    });
  });
  server.requestTimeout = 20_000;
  server.headersTimeout = 15_000;
  server.keepAliveTimeout = 5_000;
  const cleanup = setInterval(sweep, Math.min(ttlMs, 15_000));
  cleanup.unref();
  server.on('close', () => {
    clearInterval(cleanup);
    for (const id of sessions.keys()) erase(id);
    rate.clear();
  });
  return server;
}

if (require.main === module) {
  const port = Number(process.env.PORT || 31887);
  const host = process.env.HOST || '0.0.0.0';
  createRelayServer().listen(port, host, () => {
    process.stdout.write('FileHub encrypted relay listening on port ' + port + '\n');
  });
}

module.exports = {createRelayServer};
