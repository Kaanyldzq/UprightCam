// index.js (ENV destekli, senkron protokol + eski istemciyle uyumlu + DISKE YAZAR)
// Kurulum:
//   npm i express cors multer dotenv
// Çalıştırma:
//   node index.js

require('dotenv').config();

const express = require('express');
const cors = require('cors');
const multer = require('multer');
const fs = require('fs');
const path = require('path');

const app = express();
app.use(cors());
app.use(express.json());

// ---- ENV'den yapılandırmalar ----
const HOST = process.env.HOST || '0.0.0.0';
const PORT = Number(process.env.PORT || 3000);
const PUBLIC_BASE = process.env.BASE_URL || null;   // ör: http://192.168.1.136:3000
const FILES_DIR = path.resolve(__dirname, process.env.UPLOAD_DIR || 'files'); // varsayılan ./files

// ---- Statik dosyalar ----
// Klasör yoksa oluştur
if (!fs.existsSync(FILES_DIR)) {
  fs.mkdirSync(FILES_DIR, { recursive: true });
}
// /files altındaki her şeyi statik servis et
app.use('/files', express.static(FILES_DIR));

// Tam URL üretimi: .env'de BASE_URL varsa onu kullan, yoksa request host
function fullUrl(req, relativePath) {
  if (!relativePath) return null;
  // relativePath "/files/..." değilse düzelt
  const rel = relativePath.startsWith('/files/')
    ? relativePath
    : '/files/' + relativePath.replace(/^\/+/, '');

  if (PUBLIC_BASE) {
    return `${PUBLIC_BASE}${rel}`;
  }
  const host = req.get('host');
  const proto = req.protocol; // genelde "http"
  return `${proto}://${host}${rel}`;
}

// ---- Basit kullanıcılar ----
const USERS = {
  'streamer@example.com':  { password: '123456', role: 'streamer' },
  'inspector@example.com': { password: '123456', role: 'inspector' },
};

// ---- Bearer decode (mock) ----
function decodeBearer(req) {
  const auth = req.header('Authorization') || '';
  const token = auth.replace(/^Bearer\s+/i, '');
  if (!token) return { ok: false, reason: 'No token' };
  try {
    const decoded = Buffer.from(token, 'base64').toString('utf8'); // email:expiry
    const [email, expiryStr] = decoded.split(':');
    const expiry = Number(expiryStr || 0);
    if (!email || !expiry || Date.now() > expiry) return { ok: false, reason: 'Expired/invalid token' };
    const u = USERS[email];
    if (!u) return { ok: false, reason: 'Unknown user' };
    return { ok: true, email, role: u.role };
  } catch {
    return { ok: false, reason: 'Invalid token' };
  }
}
function requireAuth(req, res, next) {
  const check = decodeBearer(req);
  if (!check.ok) return res.status(401).json({ message: check.reason });
  req.user = { email: check.email, role: check.role };
  next();
}

// ---- AUTH ----
app.post('/login', (req, res) => {
  const { email, password } = req.body || {};
  const u = USERS[email];
  if (!u || u.password !== password) {
    return res.status(401).json({ message: 'Invalid credentials' });
  }
  const expiry = Date.now() + 24 * 60 * 60 * 1000;
  const token = Buffer.from(`${email}:${expiry}`).toString('base64');

  // İstenen sözleşme: token + role + email
  return res.json({ token, role: u.role, email });
});

app.post('/user/role', requireAuth, (req, res) => {
  return res.json({ message: req.user.role });
});

app.post('/logout', (_req, res) => {
  return res.json({ message: 'ok' });
});

// ---- Kanallar (opsiyonel) ----
const CHANNELS = [
  { id: 1, name: 'News 24',  status: 'active'  },
  { id: 2, name: 'Sports HD', status: 'passive' },
  { id: 3, name: 'Movies+',   status: 'active'  },
];
app.get('/channels', (_req, res) => res.json({ data: CHANNELS }));
app.post('/channels', (_req, res) => res.json({ data: CHANNELS }));

// ---- Media store ----
// path/thumbnail_url = "dosya-adı" (relative). Cevap dönerken fullUrl ile tam URL'ye çevrilecek.
let MEDIA = [
  { id: 201, type: 'photo', path: 'i2.jpg',
    thumbnail_url: 'i2_thumb.jpg',
    status: 'waiting', channel_id: 1,
    email: 'streamer@example.com',
    meta: {}, comments: [], created_at: '2025-09-02T09:00:00Z' },

  { id: 202, type: 'video', path: 'v3.mp4',
    thumbnail_url: null,
    status: 'waiting', channel_id: 1,
    email: 'streamer@example.com',
    meta: {}, comments: [], created_at: '2025-09-04T10:00:00Z' },
];
let MEDIA_SEQ = 300;

// ---- Upload ----
const upload = multer({ storage: multer.memoryStorage() });
// /media/upload  (multipart)  -> fields: file, type("photo"/"video"), meta(JSON), channel_id (veya channel)
app.post('/media/upload', requireAuth, upload.single('file'), (req, res) => {
  const { type, meta } = req.body || {};
  // Geriye dönük uyumluluk: channel veya channel_id
  let channel_id = req.body.channel_id ?? req.body.channel;
  if (channel_id != null) channel_id = Number(channel_id);

  const file = req.file;
  if (!file) return res.status(400).json({ message: 'file is required' });
  if (!type || !['video','photo'].includes(type)) {
    return res.status(400).json({ message: 'type must be "video" or "photo"' });
  }
  if (!channel_id || Number.isNaN(channel_id)) {
    return res.status(400).json({ message: 'channel_id is required' });
  }

  // --- DOSYAYI DISKE YAZ ---
  // Güvenli dosya adı üret (sadece temel temizlik)
  const original = file.originalname || (type === 'video' ? 'video.mp4' : 'image.jpg');
  const safeName = original.replace(/[^a-zA-Z0-9._-]/g, '_');
  const filename = `${Date.now()}_${safeName}`; // FILES_DIR altına yazılacak ad

  try {
    fs.writeFileSync(path.join(FILES_DIR, filename), file.buffer); // <--- diske yaz
  } catch (err) {
    console.error('file write error:', err);
    return res.status(500).json({ message: 'file write failed' });
  }

  // Meta JSON'u parse et (hatalıysa boş nesne)
  let metaObj = {};
  try { metaObj = meta ? JSON.parse(meta) : {}; } catch { metaObj = {}; }

  const id = MEDIA_SEQ++;
  const newItem = {
    id,
    type,
    path: filename,                          // relative isim (full URL'ye maplenecek)
    thumbnail_url: type === 'photo' ? filename : null, // basitçe aynı
    status: 'waiting',
    channel_id,
    email: req.user.email,
    meta: metaObj,
    comments: [],
    created_at: new Date().toISOString(),
  };
  MEDIA.push(newItem);

  return res.status(201).json({ id });
});

// ---- Listeleme ----
// İstenen: GET /media  → [MediaItemDto]
function listMediaCore(query) {
  let { status, email, channel_id, from, to, page = 1, pageSize = 50 } = query;
  page = Number(page); pageSize = Number(pageSize);

  let list = [...MEDIA];

  if (status)      list = list.filter(m => m.status === status);
  if (email)       list = list.filter(m => m.email === email);
  if (channel_id)  list = list.filter(m => String(m.channel_id) === String(channel_id));
  if (from)        list = list.filter(m => new Date(m.created_at) >= new Date(from));
  if (to)          list = list.filter(m => new Date(m.created_at) <= new Date(to));

  const total = list.length;
  const start = (page - 1) * pageSize;
  const data  = list.slice(start, start + pageSize);
  return { data, total, page, pageSize };
}

// Tam URL’leri map’lemek için yardımcı
function mapWithFullUrls(req, items) {
  return items.map(m => ({
    ...m,
    path: fullUrl(req, `/files/${m.path}`),
    thumbnail_url: m.thumbnail_url ? fullUrl(req, `/files/${m.thumbnail_url}`) : null
  }));
}

app.get('/media', requireAuth, (req, res) => {
  const { data } = listMediaCore(req.query);
  res.json(mapWithFullUrls(req, data));
});

// Geriye dönük: /media/list de çalışsın
app.get('/media/list', requireAuth, (req, res) => {
  const { data } = listMediaCore(req.query);
  res.json(mapWithFullUrls(req, data));
});

// ---- Validate ----
// Body: { status: "valid" | "not_valid" }
app.post('/media/:id/validate', requireAuth, (req, res) => {
  const id = Number(req.params.id);
  const { status } = req.body || {};
  if (!['valid', 'not_valid', 'waiting'].includes(status || '')) {
    return res.status(400).json({ message: 'invalid status' });
  }
  const item = MEDIA.find(m => m.id === id);
  if (!item) return res.status(404).json({ message: 'not found' });
  item.status = status;
  res.json({ message: 'ok' });
});

// ---- Comment ----
// Body: { text: "..." }   (geriye dönük: { comment: "..." } da çalışır)
app.post('/media/:id/comment', requireAuth, (req, res) => {
  const id = Number(req.params.id);
  const text = (req.body && (req.body.text ?? req.body.comment)) || '';
  if (!text || !String(text).trim()) {
    return res.status(400).json({ message: 'text is required' });
  }
  const item = MEDIA.find(m => m.id === id);
  if (!item) return res.status(404).json({ message: 'not found' });
  item.comments.push({ by: req.user.email, text: String(text), at: new Date().toISOString() });
  res.json({ message: 'ok' });
});

// ---- Sağlık ucu ----
app.get('/', (_req, res) => res.send('OK'));

// ---- Sunucu ----
app.listen(PORT, HOST, () => {
  console.log(`API listening on http://${HOST}:${PORT}`);
  if (PUBLIC_BASE) console.log(`Public base: ${PUBLIC_BASE}`);
  console.log(`Files dir: ${FILES_DIR}`);
});
