'use strict';
/**
 * SEM-TV Atlas server
 * - haalt reserveringen op uit Smart Event Manager (SEM) en cachet ze
 * - levert schone JSON aan players (HTML-pagina en Android-app)
 * - beheer-API voor schermen, berichten, instellingen en opdrachten (herstart, touch lock, ...)
 * Geen externe npm-dependencies: alleen Node 18+.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { URL } = require('url');

const PORT = parseInt(process.env.PORT || '80', 10);
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, 'data');
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'admin';
const DEVICE_KEY = process.env.DEVICE_KEY || ''; // optioneel: deel-sleutel voor de Android-apps
const SEM_MOCK = process.env.SEM_MOCK === '1';
const WEB_ROOT = process.env.WEB_ROOT || path.join(__dirname, '..');
const SECRET = crypto.createHash('sha256').update('semtv|' + ADMIN_PASSWORD).digest();

fs.mkdirSync(DATA_DIR, { recursive: true });
const DB_FILE = path.join(DATA_DIR, 'db.json');

/* ------------------------------------------------------------------ opslag */
const DEFAULT_DB = {
  settings: {
    semUrl: process.env.SEM_URL || '',   // verplicht: instellen via de omgevingsvariabele SEM_URL (of in het portaal)
    companyCode: 'ATL',
    preTime: 300,
    postTime: 0,
    removeAtEndOffset: 60,
    pollSeconds: 600,
    dateOffset: 0,
    title: 'ATLAS Theater',
    footerText: 'Voel je thuis in ATLAS! Kunnen we iets voor je doen? Laat het weten!',
    roomFallback: 'Kleedkamer niet in gebruik',
    overviewFallback: 'Geen kleedkamer beschikbaar op dit moment.',
    weatherLat: 52.0, weatherLon: 4.4, weatherPlace: 'Wildlands',
    // Zalen: een reservering op een kleedkamer hoort bij zaal X als dezelfde reserveringsnaam ook op facility X staat.
    // Volgorde = prioriteit als een naam bij meerdere zalen voorkomt.
    zones: [
      { key: 'grote-zaal', label: 'Grote Zaal', facilityId: 10, showLabel: true, bg: '#501237', accent: '#ff5000', headerBg: '', headerText: '', logo: '', bgImage: '/media/grotezaal.jpg', bgDim: 55 },
      { key: 'kleine-zaal', label: 'Kleine Zaal', facilityId: 13, showLabel: true, bg: '#501237', accent: '#ff5000', headerBg: '', headerText: '', logo: '', bgImage: '/media/kleinezaal.jpg', bgDim: 62 }
    ],
    // Standaard achtergrond voor kleedkamers zonder zaal (of zonder reservering)
    defaultBgImage: '/media/atlastheater.jpg', defaultBgDim: 70,
    // Badge-stijl: roof | glass | line | fade | 3d | both | plain
    badgeStyle: 'lines',
    // Inbrandbeveiliging
    ssIdleMinutes: 5,   // scherm zonder reserveringen: na zoveel minuten screensaver (0 = uit)
    ssPixelShift: 1,    // 1 = inhoud verschuift elke minuut een paar pixels
    defaultTouchDisableAfterSec: 60,
    adminPin: '1234'
  },
  screens: [],
  messages: [],
  commands: {}, // screenId -> [{id,type,params,created}]
  devices: {}   // screenId -> laatste heartbeat
};

function loadDb() {
  try {
    const db = JSON.parse(fs.readFileSync(DB_FILE, 'utf8'));
    db.settings = Object.assign({}, DEFAULT_DB.settings, db.settings || {});
    if (db.settings.pollSeconds === 60) db.settings.pollSeconds = 600; // oude standaard (1 min) -> 10 min
    db.screens = db.screens || []; db.messages = db.messages || [];
    db.commands = db.commands || {}; db.devices = db.devices || {};
    (db.settings.zones || []).forEach(z => {
      const d = DEFAULT_DB.settings.zones.find(x => x.key === z.key);
      // eerdere voorbeeldkleuren vervangen door de huisstijl uit semtv.css (alleen als ze nog ongewijzigd zijn)
      if (['#0d2b45', '#1d3b2a'].includes(z.bg) && ['#ffb400', '#7fe0a1'].includes(z.accent)) { z.bg = '#501237'; z.accent = '#ff5000'; }
      if (d && z.bgImage === undefined) z.bgImage = d.bgImage;
      if (d && z.bgDim === undefined) z.bgDim = d.bgDim;
    });
    return db;
  } catch (e) {
    const db = JSON.parse(JSON.stringify(DEFAULT_DB));
    seedKleedkamers(db);
    fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2));
    return db;
  }
}
let saveTimer = null;
function saveDb() {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    const tmp = DB_FILE + '.tmp';
    fs.writeFileSync(tmp, JSON.stringify(db, null, 2));
    fs.renameSync(tmp, DB_FILE);
  }, 200);
}

/** Eerste start: de 17 bestaande kleedkamers + overzicht uit de oude HTML-pagina's. */
function seedKleedkamers(d) {
  const rooms = [
    ['-1.1', 137], ['-1.2', 138], ['0.1', 22], ['0.2', 125], ['0.3', 21], ['0.4', 126], ['0.5', 127],
    ['0.6', 128], ['1.1', 25], ['1.2', 129], ['1.3', 130], ['1.4', 131], ['1.5', 132], ['1.6', 133],
    ['1.7', 134], ['1.8', 136], ['1.9', 135]
  ];
  rooms.forEach(([nr, fid]) => {
    d.screens.push(newScreen({
      id: 'kleedkamer-' + nr.replace('-', 'min').replace('.', '-'),
      name: 'Kleedkamer ' + nr, group: 'Kleedkamers',
      slides: [{ kind: 'room', facilityIds: [fid], title: 'KLEEDKAMER ' + nr, durationSec: 30 }]
    }));
  });
  d.screens.push(newScreen({
    id: 'overzicht', name: 'Kleedkameroverzicht', group: 'Overzicht',
    slides: [
      { kind: 'overview', facilityIds: rooms.map(r => r[1]), title: 'Welkom in ATLAS Theater!', durationSec: 40 },
      { kind: 'messages', title: 'Mededelingen', durationSec: 15 },
      { kind: 'clock', durationSec: 8 }
    ]
  }));
}
function newScreen(o) {
  return Object.assign({
    id: '', name: '', group: '', enabled: true,
    slides: [],
    touchDisableAfterSec: -1, // -1 = standaard uit instellingen, 0 = nooit uitschakelen
    touchMode: 'timed',        // always | timed | off
    brightness: -1,            // -1 = systeem, 0-100
    schedule: { enabled: false, on: '07:00', off: '23:30' }
  }, o);
}
let db = loadDb();

/* ---------------------------------------------------------------- SEM-adapter */
// SEM geeft kant-en-klare HTML terug op basis van een template. We sturen een template mee die
// per reservering één tabelrij met herkenbare cellen oplevert en parsen die naar JSON.
const SEM_TEMPLATE =
  "<tr class='r'><td class='rn'>{RecordNumber}</td><td class='w'>{WelcomeText}</td><td class='n'>{ReservationName}</td>" +
  "<td class='f'>{FacilityWelcomeText}</td><td class='fn'>{FacilityName}</td></tr>";

const semCache = new Map(); // facilityId -> {items, fetchedAt, ok, error}

function decode(s) {
  return String(s || '').replace(/<[^>]*>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&#(\d+);/g, (_, n) => String.fromCharCode(+n)).trim();
}
function parseSemHtml(html) {
  const out = [];
  const rows = String(html || '').match(/<tr[^>]*class=['"]r['"][^>]*>[\s\S]*?<\/tr>/gi) || [];
  rows.forEach(r => {
    const cell = c => { const m = r.match(new RegExp("<td[^>]*class=['\"]" + c + "['\"][^>]*>([\\s\\S]*?)</td>", 'i')); return m ? decode(m[1]) : ''; };
    const item = { recordNumber: cell('rn'), welcome: cell('w'), name: cell('n'), facilityWelcome: cell('f'), facilityName: cell('fn') };
    if (item.welcome || item.name || item.facilityWelcome) out.push(item);
  });
  return out;
}

const semBase = () => db.settings.semUrl || process.env.SEM_URL || '';
async function fetchFacility(fid) {
  const s = db.settings;
  if (SEM_MOCK) return mockFacility(fid);
  if (!semBase()) throw new Error('SEM-adres niet ingesteld: zet de omgevingsvariabele SEM_URL of vul het in bij Instellingen');
  const body = JSON.stringify({
    Type: 'ReservationFacility', Html: SEM_TEMPLATE, CompanyCode: s.companyCode, DateOffset: s.dateOffset,
    ShowFirstOnly: false, PreTime: s.preTime, PostTime: s.postTime, RemoveAtEndOffset: s.removeAtEndOffset,
    FacilitySequenceNumber: false, FacilityIds: [fid]
  });
  const url = semBase().replace(/\/$/, '') + '/ReservationFacility?jsonTemplate=' + encodeURIComponent(body);
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 15000);
  try {
    const res = await fetch(url, { signal: ctl.signal, headers: { Accept: 'application/json' } });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const json = await res.json();
    return { raw: json.html, items: parseSemHtml(json.html) };
  } finally { clearTimeout(t); }
}

function mockFacility(fid) {
  const names = ['Toneelgroep Vondel', 'Kinderkoor Rijnmond', 'Dansschool Lenore', 'Orkest Haagland'];
  if (fid % 3 === 0) return { raw: '', items: [] };
  const n = names[(process.env.SEM_MOCK_MULTI === '1' && fid === 13 ? 10 : fid) % names.length]; // SEM_MOCK_MULTI=1: Grote en Kleine Zaal met dezelfde reservering (test)
  return { raw: '', items: [{ recordNumber: '1', welcome: 'Welkom ' + n, name: n, facilityWelcome: 'Veel plezier vandaag!', facilityName: 'Kleedkamer ' + fid }] };
}

function allFacilityIds() {
  const ids = new Set();
  (db.settings.zones || []).forEach(z => z.facilityId && ids.add(Number(z.facilityId)));
  db.screens.forEach(sc => sc.enabled !== false && (sc.slides || []).forEach(sl => (sl.facilityIds || []).forEach(i => ids.add(Number(i)))));
  return [...ids].filter(Boolean);
}
let semBusy = false;
async function refreshSem() {
  if (semBusy) return; semBusy = true;
  try {
    const ids = allFacilityIds();
    const queue = ids.slice();
    const worker = async () => {
      while (queue.length) {
        const fid = queue.shift();
        try {
          const r = await fetchFacility(fid);
          semCache.set(fid, { items: r.items, raw: r.raw, fetchedAt: Date.now(), ok: true, error: null });
        } catch (e) {
          const prev = semCache.get(fid) || { items: [] };
          semCache.set(fid, Object.assign({}, prev, { ok: false, error: String(e.message || e), failedAt: Date.now() }));
        }
      }
    };
    await Promise.all([worker(), worker(), worker(), worker()]);
  } finally { semBusy = false; }
}
function scheduleSem() {
  refreshSem().finally(() => setTimeout(scheduleSem, Math.max(10, db.settings.pollSeconds) * 1000));
}

/* ---------------------------------------------------------------- weer (Open-Meteo, server-side) */
let weatherCache = { at: 0, data: null };
async function getWeather() {
  const s = db.settings;
  if (weatherCache.data && Date.now() - weatherCache.at < 10 * 60 * 1000) return weatherCache.data;
  try {
    const u = `https://api.open-meteo.com/v1/forecast?latitude=${s.weatherLat}&longitude=${s.weatherLon}` +
      `&current=temperature_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min,weather_code&timezone=Europe%2FAmsterdam&forecast_days=3`;
    const r = await fetch(u, { signal: AbortSignal.timeout(8000) });
    const j = await r.json();
    weatherCache = { at: Date.now(), data: { place: s.weatherPlace, current: j.current, daily: j.daily } };
  } catch (e) { /* oude waarde blijft staan */ }
  return weatherCache.data;
}

/* ---------------------------------------------------------------- hulp */
const MIME = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8',
  '.json': 'application/json', '.woff': 'font/woff', '.otf': 'font/otf', '.png': 'image/png', '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg', '.svg': 'image/svg+xml', '.ico': 'image/x-icon', '.apk': 'application/vnd.android.package-archive' };

function send(res, code, body, headers) {
  const isObj = typeof body === 'object' && !Buffer.isBuffer(body);
  res.writeHead(code, Object.assign({ 'Content-Type': isObj ? 'application/json' : 'text/plain; charset=utf-8', 'Cache-Control': 'no-store' }, headers || {}));
  res.end(isObj ? JSON.stringify(body) : body);
}
function readBody(req) {
  return new Promise((resolve, reject) => {
    let d = ''; req.on('data', c => { d += c; if (d.length > 5e6) { reject(new Error('te groot')); req.destroy(); } });
    req.on('end', () => { try { resolve(d ? JSON.parse(d) : {}); } catch (e) { reject(e); } });
  });
}
function makeToken() { return crypto.createHmac('sha256', SECRET).update('admin-token').digest('hex'); }
function isAdmin(req) {
  const h = req.headers.authorization || '';
  const t = h.startsWith('Bearer ') ? h.slice(7) : '';
  return t.length === 64 && crypto.timingSafeEqual(Buffer.from(t), Buffer.from(makeToken()));
}
function deviceOk(req) { return !DEVICE_KEY || req.headers['x-device-key'] === DEVICE_KEY; }
const uid = () => crypto.randomBytes(6).toString('hex');

function screenById(id) { return db.screens.find(s => s.id === id); }

function activeMessages(screen) {
  const now = Date.now();
  return db.messages.filter(m => {
    if (m.enabled === false) return false;
    if (m.from && Date.parse(m.from) > now) return false;
    if (m.until && Date.parse(m.until) < now) return false;
    const t = m.targets || [];
    if (t.length && !t.includes('*') && !t.includes(screen.id) && !t.includes('group:' + screen.group)) return false;
    return true;
  });
}

const normName = v => String(v || '').toLowerCase().replace(/\s+/g, ' ').trim();
/** Bepaalt bij welke zaal een reservering hoort: eigen facility = zaal, of dezelfde naam op de zaal-facility. */
function zoneOf(item, ownFid) {
  const key = normName(item.name || item.welcome), hits = [];
  for (const z of (db.settings.zones || [])) {
    if (!z.facilityId) continue;
    const c = semCache.get(Number(z.facilityId));
    if (Number(z.facilityId) === Number(ownFid) || (key && c && c.items.some(i => normName(i.name || i.welcome) === key))) hits.push(z);
  }
  // meerdere zalen in één reservering: geen eigen vormgeving, de speler valt terug op de standaardachtergrond
  if (hits.length > 1) return { zone: 'multi', zoneLabels: hits.map(z => z.label || z.key) };
  return { zone: hits.length ? hits[0].key : null };
}

function buildState(screen) {
  const facilities = {};
  (screen.slides || []).forEach(sl => (sl.facilityIds || []).forEach(fid => {
    const c = semCache.get(Number(fid));
    facilities[fid] = c
      ? { items: c.items.map(it => Object.assign({}, it, zoneOf(it, fid))), ok: c.ok, fetchedAt: c.fetchedAt }
      : { items: [], ok: false, fetchedAt: 0 };
  }));
  const zones = {};
  (db.settings.zones || []).forEach(z => { zones[z.key] = { label: z.label, showLabel: z.showLabel !== false, bg: z.bg, accent: z.accent, headerBg: z.headerBg, headerText: z.headerText, logo: z.logo, bgImage: z.bgImage || '', bgDim: z.bgDim == null ? 65 : z.bgDim }; });
  const s = db.settings;
  return {
    serverTime: Date.now(),
    screen: { id: screen.id, name: screen.name, slides: screen.slides, touchMode: screen.touchMode, brightness: screen.brightness },
    texts: { title: s.title, footer: s.footerText, roomFallback: s.roomFallback, overviewFallback: s.overviewFallback },
    facilities, zones,
    badgeStyle: s.badgeStyle || 'lines',
    defaults: { bgImage: s.defaultBgImage || '', bgDim: s.defaultBgDim == null ? 70 : s.defaultBgDim },
    screensaver: { idleMinutes: Number(s.ssIdleMinutes) || 0, pixelShift: Number(s.ssPixelShift) ? 1 : 0 },
    messages: activeMessages(screen).map(m => ({ id: m.id, title: m.title, text: m.text, image: m.image, priority: m.priority || 0 }))
  };
}

function deviceConfigFor(screen) {
  const s = db.settings;
  const dis = screen.touchDisableAfterSec;
  return {
    touchMode: screen.touchMode || 'timed',
    touchDisableAfterSec: dis >= 0 ? dis : s.defaultTouchDisableAfterSec,
    brightness: screen.brightness,
    adminPin: s.adminPin,
    playerPath: '/player/?screen=' + encodeURIComponent(screen.id)
  };
}

// bevestigingen van schermen: commando-id -> tijdstip waarop het scherm meldde dat het is uitgevoerd
const cmdDone = {};
const cmdExpired = {};   // commando-id -> tijdstip waarop het verliep (niet opgepakt binnen de tijd)
const cmdMeta = {};      // commando-id -> { screen, type }
const lastDone = {};     // scherm-id -> { type, at }: laatst bevestigde commando
const COMMAND_TTL = Number(process.env.COMMAND_TTL_MS) || 60000; // een commando dat na 1 minuut niet is opgepakt, wordt gestopt
function pruneCommands() {
  const now = Date.now(); let changed = false;
  for (const id of Object.keys(db.commands)) {
    if (!db.screens.some(sc => sc.id === id)) { delete db.commands[id]; changed = true; continue; }
    const keep = db.commands[id].filter(c => { const ok = now - c.created < COMMAND_TTL; if (!ok) { cmdExpired[c.id] = now; changed = true; } return ok; });
    if (keep.length !== db.commands[id].length) db.commands[id] = keep;
  }
  for (const k of Object.keys(cmdExpired)) if (now - cmdExpired[k] > 3600000) { delete cmdExpired[k]; delete cmdMeta[k]; }
  if (changed) saveDb();
}
setInterval(pruneCommands, 10000);
function apkInfo() {
  try { const st = fs.statSync(path.join(DATA_DIR, 'downloads', 'app-release.apk')); return { exists: true, size: st.size, mtime: st.mtimeMs }; } catch (e) { return { exists: false }; }
}
function queueCommand(screenId, type, params) {
  pruneCommands();
  const list = db.commands[screenId] = db.commands[screenId] || [];
  // dubbele identieke commando's niet opstapelen
  const same = list.find(c => c.type === type && JSON.stringify(c.params || {}) === JSON.stringify(params || {}));
  if (same) return same;
  const cmd = { id: uid(), type, params: params || {}, created: Date.now() };
  cmdMeta[cmd.id] = { screen: screenId, type };
  list.push(cmd); saveDb(); return cmd;
}

/* ---------------------------------------------------------------- scherm-schema (aan/uit) */
const lastSchedule = {};
function tickSchedules() {
  const now = new Date();
  const hhmm = now.toLocaleTimeString('nl-NL', { hour: '2-digit', minute: '2-digit', hour12: false, timeZone: 'Europe/Amsterdam' });
  db.screens.forEach(sc => {
    const sch = sc.schedule; if (!sch || !sch.enabled) return;
    const inOn = sch.on <= sch.off ? (hhmm >= sch.on && hhmm < sch.off) : (hhmm >= sch.on || hhmm < sch.off);
    const want = inOn ? 'screen_on' : 'screen_off';
    if (lastSchedule[sc.id] !== want) { lastSchedule[sc.id] = want; queueCommand(sc.id, want, { by: 'schedule' }); }
  });
}
setInterval(tickSchedules, 30000);

/* ---------------------------------------------------------------- routes */
async function handleApi(req, res, url) {
  const p = url.pathname; const m = req.method;

  if (p === '/api/health') return send(res, 200, { ok: true, time: Date.now(), mock: SEM_MOCK });

  // ---- player
  let mt = p.match(/^\/api\/player\/([^/]+)\/state$/);
  if (mt && m === 'GET') {
    const sc = screenById(decodeURIComponent(mt[1]));
    if (!sc) return send(res, 404, { error: 'onbekend scherm' });
    return send(res, 200, buildState(sc));
  }
  if (p === '/api/player/weather' && m === 'GET') return send(res, 200, (await getWeather()) || {});
  if (p === '/api/player/screens' && m === 'GET') return send(res, 200, db.screens.map(s => ({ id: s.id, name: s.name, group: s.group })));

  // ---- android device
  mt = p.match(/^\/api\/device\/([^/]+)\/heartbeat$/);
  if (mt && m === 'POST') {
    if (!deviceOk(req)) return send(res, 401, { error: 'ongeldige device key' });
    const id = decodeURIComponent(mt[1]);
    const sc = screenById(id);
    if (!sc) return send(res, 404, { error: 'onbekend scherm' });
    const body = await readBody(req);
    (body.ackIds || []).forEach(a => { cmdDone[a] = Date.now(); if (cmdMeta[a]) lastDone[id] = { type: cmdMeta[a].type, at: Date.now() }; db.commands[id] = (db.commands[id] || []).filter(c => c.id !== a); });
    Object.keys(cmdDone).forEach(k => { if (Date.now() - cmdDone[k] > 3600000) delete cmdDone[k]; });
    db.devices[id] = Object.assign({}, body, { ackIds: undefined, lastSeen: Date.now(), ip: (req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').toString() });
    pruneCommands(); saveDb();
    return send(res, 200, { commands: db.commands[id] || [], config: deviceConfigFor(sc), serverTime: Date.now() });
  }
  mt = p.match(/^\/api\/device\/([^/]+)\/config$/);
  if (mt && m === 'GET') {
    if (!deviceOk(req)) return send(res, 401, { error: 'ongeldige device key' });
    const sc = screenById(decodeURIComponent(mt[1]));
    return sc ? send(res, 200, deviceConfigFor(sc)) : send(res, 404, { error: 'onbekend scherm' });
  }

  // ---- admin
  if (p === '/api/admin/login' && m === 'POST') {
    const b = await readBody(req);
    if (b.password === ADMIN_PASSWORD) return send(res, 200, { token: makeToken() });
    return send(res, 401, { error: 'Onjuist wachtwoord' });
  }
  if (p.startsWith('/api/admin/')) {
    if (!isAdmin(req)) return send(res, 401, { error: 'Niet ingelogd' });

    if (p === '/api/admin/state' && m === 'GET') {
      pruneCommands();
      return send(res, 200, {
        settings: db.settings, screens: db.screens, messages: db.messages,
        devices: db.devices, pending: Object.fromEntries(Object.entries(db.commands).map(([k, v]) => [k, v.length])),
        sem: [...semCache.entries()].map(([fid, c]) => ({ fid, ok: c.ok, error: c.error, items: c.items.length, fetchedAt: c.fetchedAt })),
        mock: SEM_MOCK, serverTime: Date.now(), apk: apkInfo(), lastDone
      });
    }
    if (p === '/api/admin/apk' && m === 'POST') {
      // de Android-app (apk) uploaden; daarna te downloaden op /downloads/app-release.apk
      const dir = path.join(DATA_DIR, 'downloads'); fs.mkdirSync(dir, { recursive: true });
      const tmp = path.join(dir, '.upload.tmp'), out = fs.createWriteStream(tmp); let size = 0, bad = false;
      await new Promise((resolve) => {
        req.on('data', c => { size += c.length; if (size > 150e6 && !bad) { bad = true; req.destroy(); out.destroy(); resolve(); } });
        req.pipe(out); out.on('finish', resolve); out.on('error', resolve); req.on('error', resolve);
      });
      if (bad || size < 1000) { try { fs.unlinkSync(tmp); } catch (e) {} return send(res, 400, { error: bad ? 'bestand te groot (max 150 MB)' : 'leeg bestand' }); }
      fs.renameSync(tmp, path.join(dir, 'app-release.apk'));
      return send(res, 200, apkInfo());
    }
    if (p === '/api/admin/settings' && m === 'PUT') {
      const b = await readBody(req);
      if (Array.isArray(b.zones)) {
        const used = new Set();
        b.zones = b.zones.map((z, i) => {
          let k = String(z.key || z.label || 'zaal' + i).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || 'zaal' + i;
          while (used.has(k)) k += '-' + i; used.add(k);
          return Object.assign({}, z, { key: k, facilityId: Number(z.facilityId) || 0 });
        });
      }
      db.settings = Object.assign({}, db.settings, b); saveDb(); refreshSem(); return send(res, 200, db.settings);
    }
    if (p === '/api/admin/screens' && m === 'POST') {
      const b = await readBody(req);
      const id = (b.id || b.name || uid()).toString().toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || uid();
      if (screenById(id)) return send(res, 409, { error: 'ID bestaat al' });
      const sc = newScreen(Object.assign({}, b, { id })); db.screens.push(sc); saveDb(); refreshSem(); return send(res, 200, sc);
    }
    mt = p.match(/^\/api\/admin\/screens\/([^/]+)$/);
    if (mt) {
      const id = decodeURIComponent(mt[1]); const sc = screenById(id);
      if (!sc) return send(res, 404, { error: 'onbekend scherm' });
      if (m === 'PUT') {
        Object.assign(sc, await readBody(req), { id }); saveDb();
        try { await refreshSem(); } catch (e) {}
        // na opslaan: scherm automatisch herladen; het portaal volgt via /api/admin/commands/:id of het scherm dit heeft uitgevoerd
        const cmd = queueCommand(id, 'reload', { by: 'save' });
        return send(res, 200, Object.assign({}, sc, { _cmd: cmd && cmd.id }));
      }
      if (m === 'DELETE') { db.screens = db.screens.filter(s => s.id !== id); delete db.devices[id]; delete db.commands[id]; saveDb(); return send(res, 200, { ok: true }); }
    }
    mt = p.match(/^\/api\/admin\/commands\/([^/]+)$/);
    if (mt && m === 'GET') {
      const cid = decodeURIComponent(mt[1]);
      pruneCommands();
      return send(res, 200, { done: !!cmdDone[cid], expired: !cmdDone[cid] && !!cmdExpired[cid], at: cmdDone[cid] || null });
    }
    mt = p.match(/^\/api\/admin\/screens\/([^/]+)\/command$/);
    if (mt && m === 'POST') {
      const b = await readBody(req);
      const ALLOWED = ['reload', 'restart_app', 'reboot', 'screen_on', 'screen_off', 'touch_lock', 'touch_unlock', 'clear_cache', 'set_brightness'];
      if (!ALLOWED.includes(b.type)) return send(res, 400, { error: 'onbekend commando' });
      const ids = mt[1] === '_all' ? db.screens.map(s => s.id) : (mt[1] === '_group' ? db.screens.filter(s => s.group === b.group).map(s => s.id) : [decodeURIComponent(mt[1])]);
      // herladen: eerst de nieuwste data uit SEM ophalen, daarna pas naar de schermen pushen
      if (['reload', 'clear_cache', 'restart_app'].includes(b.type)) { try { await refreshSem(); } catch (e) {} }
      ids.forEach(i => screenById(i) && queueCommand(i, b.type, b.params));
      return send(res, 200, { queued: ids.length });
    }
    if (p === '/api/admin/messages' && m === 'POST') {
      const b = await readBody(req); const msg = Object.assign({ id: uid(), enabled: true, targets: ['*'], priority: 0 }, b, { id: uid() });
      db.messages.push(msg); saveDb(); return send(res, 200, msg);
    }
    mt = p.match(/^\/api\/admin\/messages\/([^/]+)$/);
    if (mt) {
      const msg = db.messages.find(x => x.id === mt[1]);
      if (!msg) return send(res, 404, { error: 'onbekend bericht' });
      if (m === 'PUT') { Object.assign(msg, await readBody(req), { id: msg.id }); saveDb(); return send(res, 200, msg); }
      if (m === 'DELETE') { db.messages = db.messages.filter(x => x.id !== msg.id); saveDb(); return send(res, 200, { ok: true }); }
    }
    if (p === '/api/admin/sem-test' && m === 'POST') {
      const b = await readBody(req);
      try { const r = await fetchFacility(Number(b.facilityId)); return send(res, 200, { ok: true, parsed: r.items, rawHtml: r.raw }); }
      catch (e) { return send(res, 200, { ok: false, error: String(e.message || e) }); }
    }
    // Verkenner: stuurt een eigen template naar SEM en geeft de volledige, ruwe respons terug.
    if (p === '/api/admin/sem-probe' && m === 'POST') {
      const b = await readBody(req); const st = db.settings;
      const type = String(b.type || 'ReservationFacility').replace(/[^A-Za-z0-9]/g, '');
      const body = JSON.stringify({
        Type: type, Html: String(b.html || ''), CompanyCode: st.companyCode, DateOffset: Number(b.dateOffset ?? st.dateOffset),
        ShowFirstOnly: false, PreTime: Number(b.preTime ?? st.preTime), PostTime: Number(b.postTime ?? st.postTime),
        RemoveAtEndOffset: Number(b.removeAtEndOffset ?? st.removeAtEndOffset), FacilitySequenceNumber: false,
        FacilityIds: (b.facilityIds || []).map(Number)
      });
      if (SEM_MOCK) return send(res, 200, { ok: true, mock: true, json: { html: '(mock-modus: geen echte SEM-aanroep)' } });
      try {
        const url = semBase().replace(/\/$/, '') + '/' + type + '?jsonTemplate=' + encodeURIComponent(body);
        const r = await fetch(url, { signal: AbortSignal.timeout(15000), redirect: 'manual' });
        const text = await r.text(); let json = null; try { json = JSON.parse(text); } catch (e) {}
        return send(res, 200, { ok: r.ok, status: r.status, urlLength: url.length, location: r.headers.get('location') || undefined,
          json, text: json ? undefined : text.slice(0, 3000) });
      } catch (e) { return send(res, 200, { ok: false, error: String(e.message || e) }); }
    }
    if (p === '/api/admin/refresh' && m === 'POST') { await refreshSem(); return send(res, 200, { ok: true }); }
    return send(res, 404, { error: 'onbekende admin-route' });
  }
  return send(res, 404, { error: 'onbekende route' });
}

function serveStatic(req, res, url) {
  let p = decodeURIComponent(url.pathname);
  // /downloads wijst naar het data-volume: zet daar de gebouwde APK neer (zie DEPLOYMENT.md)
  const maps = [['/player', path.join(WEB_ROOT, 'player')], ['/admin', path.join(WEB_ROOT, 'admin')],
    ['/legacy', path.join(WEB_ROOT, 'html')], ['/downloads', path.join(DATA_DIR, 'downloads')],
    ['/media', fs.existsSync(path.join(DATA_DIR, 'media', p.slice(7))) && p.length > 7 ? path.join(DATA_DIR, 'media') : path.join(WEB_ROOT, 'media')]];
  let base = null;
  for (const [pre, dir] of maps) {
    if (p === pre) { res.writeHead(302, { Location: pre + '/' }); return res.end(); }
    if (p.startsWith(pre + '/')) { base = dir; p = p.slice(pre.length); break; }
  }
  if (!base) {
    if (p === '/') { res.writeHead(302, { Location: '/admin/' }); return res.end(); }
    return send(res, 404, 'Niet gevonden');
  }
  if (p.endsWith('/')) p += 'index.html';
  const file = path.normalize(path.join(base, p));
  if (!file.startsWith(base)) return send(res, 403, 'Verboden');
  fs.readFile(file, (err, buf) => {
    if (err) return send(res, 404, 'Niet gevonden');
    const cache = /\.(woff|otf|png|jpg|svg)$/.test(file) ? 'public, max-age=86400' : 'no-cache';
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': cache });
    if (file.endsWith('.html') && buf.includes('{{SEM_URL}}')) return res.end(buf.toString().split('{{SEM_URL}}').join(semBase()));
    res.end(buf);
  });
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization, X-Device-Key');
    res.setHeader('Access-Control-Allow-Methods', 'GET,POST,PUT,DELETE,OPTIONS');
    if (req.method === 'OPTIONS') { res.writeHead(204); return res.end(); }
    if (url.pathname.startsWith('/api/')) return await handleApi(req, res, url);
    return serveStatic(req, res, url);
  } catch (e) {
    console.error(e); send(res, 500, { error: String(e.message || e) });
  }
});

if (require.main === module) {
  server.listen(PORT, () => console.log(`SEM-TV Atlas server op poort ${PORT} (mock=${SEM_MOCK})`));
  scheduleSem();
  process.on('SIGTERM', () => { try { fs.writeFileSync(DB_FILE, JSON.stringify(db, null, 2)); } catch (e) {} process.exit(0); });
}
module.exports = { parseSemHtml, server };
