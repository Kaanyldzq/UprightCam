// index.mjs
import express from "express";
import cors from "cors";
import dotenv from "dotenv";
import bcrypt from "bcryptjs";
import multer from "multer";
import fs from "fs";
import path from "path";
import dayjs from "dayjs";
import { fileURLToPath } from "url";
import { initDb, getDb } from "./db.mjs";
import { signToken, authRequired, roleRequired } from "./auth.mjs";
import { seed } from "./seed.mjs";

dotenv.config();

const app = express();
app.use(cors());
app.use(express.json({ limit: "25mb" }));

// __dirname/__filename (ESM)
const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

// Statik dosyalar: /files/... -> uploads klasörüne gider
app.use("/files", express.static(path.join(__dirname, "uploads")));

// Basit istek log'u
app.use((req, _res, next) => {
  console.log(`[REQ] ${req.method} ${req.url}`);
  next();
});

// ---- helper: absolute/relative dosya yolunu /files/... public URL’e çevir ----
function toPublic(p) {
  if (!p) return p;
  const normalized = String(p).replace(/\\/g, "/");
  if (normalized.startsWith("/files/")) return normalized;
  const absUploads = path.join(__dirname, "uploads").replace(/\\/g, "/");
  const rel = normalized.replace(absUploads, "").replace(/^\/+/, "");
  return `/files/${rel}`;
}

// ---------------- Multer ----------------
const storage = multer.diskStorage({
  destination: (_req, _file, cb) => {
    const dir = path.join(__dirname, "uploads", dayjs().format("YYYY-MM-DD"));
    fs.mkdirSync(dir, { recursive: true });
    cb(null, dir);
  },
  filename: (_req, file, cb) => {
    const ext = path.extname(file.originalname);
    const base = `${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;
    cb(null, base + ext);
  }
});
const upload = multer({ storage });

// ============== AUTH ==============

// POST /login  -> { token, role, email }
app.post("/login", async (req, res) => {
  try {
    const { email, password } = req.body || {};
    const db = await getDb();
    const u = await db.get("SELECT * FROM users WHERE email = ?", email);
    if (!u) return res.status(401).json({ message: "E-Posta veya Şifre yanlış" });

    const ok = await bcrypt.compare(password, u.password_hash);
    if (!ok) return res.status(401).json({ message: "E-Posta veya Şifre yanlış" });

    const token = signToken({ id: u.id, email: u.email, role: u.role });
    return res.json({ token, role: u.role, email: u.email });
  } catch (e) {
    console.error("[/login] ERROR:", e);
    return res.status(503).json({ message: "Sunucuya ulaşılamıyor. Tekrar deneyin ya da yöneticinize ulaşın." });
  }
});

// POST /logout
app.post("/logout", authRequired(), async (req, res) => {
  try {
    const db = await getDb();
    await db.run("INSERT INTO revoked_tokens (token) VALUES (?)", req.token);
    return res.json({ message: "Çıkış başarılı." });
  } catch (e) {
    console.error("[/logout] ERROR:", e);
    return res.status(503).json({ message: "Sunucuya ulaşılamıyor. Tekrar deneyin ya da yöneticinize ulaşın." });
  }
});

// POST /user/role -> { role }
app.post("/user/role", authRequired(), async (req, res) => {
  try {
    return res.json({ role: req.user.role || null });
  } catch (e) {
    console.error("[/user/role] ERROR:", e);
    return res.status(503).json({ message: "Sunucuya ulaşılamıyor. Tekrar deneyin ya da yöneticinize ulaşın." });
  }
});

// (Opsiyonel) /user/info
app.post("/user/info", authRequired(), async (req, res) => {
  try {
    const db = await getDb();
    const u = await db.get(
      "SELECT email, firstname, middlename, surname, company, team FROM users WHERE id = ?",
      req.user.id
    );
    return res.json({ data: u || {} });
  } catch (e) {
    console.error("[/user/info] ERROR:", e);
    return res.status(503).json({ message: "Sunucuya ulaşılamıyor. Tekrar deneyin ya da yöneticinize ulaşın." });
  }
});

// (Opsiyonel) /get-channels
app.post("/get-channels", authRequired(), async (_req, res) => {
  try {
    const db = await getDb();
    const list = await db.all("SELECT id,name,status FROM channels ORDER BY id ASC");
    return res.json({ data: list });
  } catch (e) {
    console.error("[/get-channels] ERROR:", e);
    return res.status(503).json({ message: "Sunucuya ulaşılamıyor. Tekrar deneyin ya da yöneticinize ulaşın." });
  }
});

// ============== KANONİK UÇLAR (ANDROID ile uyumlu) ==============

// POST /media/upload
app.post(
  "/media/upload",
  authRequired(),
  roleRequired("streamer"),
  upload.single("file"),
  async (req, res) => {
    try {
      console.log("[UPLOAD] content-type:", req.headers["content-type"]);
      console.log("[UPLOAD] body(raw):", req.body);
      console.log("[UPLOAD] file:", req.file);

      const channelId = Number(req.body.channel ?? req.body.channelId);
      if (!channelId || Number.isNaN(channelId)) {
        return res.status(400).json({ message: "channel (veya channelId) gerekli" });
      }

      const kind = (req.body.type || "").toString().toLowerCase(); // "video" | "photo"
      if (!["video", "photo"].includes(kind)) {
        return res.status(400).json({ message: "type 'video' veya 'photo' olmalı" });
      }

      let meta = {};
      try { meta = req.body.meta ? JSON.parse(req.body.meta) : {}; }
      catch (e) {
        console.error("[UPLOAD] meta JSON parse ERROR:", e);
        return res.status(400).json({ message: "meta JSON geçersiz" });
      }

      const db = await getDb();
      const st = await db.get("SELECT status FROM channels WHERE id = ?", channelId);
      if (!st || st.status !== "active") {
        return res.status(400).json({ message: "Kanal pasif veya yok" });
      }

      if (!req.file) return res.status(400).json({ message: "Dosya gerekli (file)" });

      const p = req.file.path.replace(/\\/g, "/");
      await db.run(
        `INSERT INTO media 
         (type, filename, path, uploaded_by_email, channel_id, shot_date, shot_time, loc_x, loc_y, resolution_w, resolution_h, filesize_bytes, duration_sec)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)`,
        [
          kind === "photo" ? "image" : "video",
          req.file.filename,
          p,
          req.user.email,
          channelId,
          meta.shot_date || null,
          meta.shot_time || null,
          meta.loc_x || null,
          meta.loc_y || null,
          meta.resolution_w || null,
          meta.resolution_h || null,
          req.file.size || null,
          kind === "video" ? (meta.duration_sec || null) : null
        ]
      );

      return res.json({ data: "Upload başarılı", path: toPublic(p) });
    } catch (e) {
      console.error("[/media/upload] ERROR:", e);
      return res.status(503).json({ message: "Sunucuya ulaşılamıyor. Tekrar deneyin ya da yöneticinize ulaşın." });
    }
  }
);

// GET /media/list?status=&email=&channel=&page=&pageSize=
app.get(
  "/media/list",
  authRequired(),
  roleRequired("streamer", "inspector"),
  async (req, res) => {
    try {
      const { status, email, channel, page, pageSize } = req.query || {};
      const db = await getDb();

      const where = [];
      const params = [];

      if (status) {
        const s = String(status).toLowerCase();
        if (s === "waiting") {
          where.push("validation_status IS NULL");
        } else if (s === "valid" || s === "not_valid") {
          where.push("validation_status = ?");
          params.push(s);
        }
      }

      if (email) {
        where.push("uploaded_by_email = ?");
        params.push(String(email));
      }

      if (channel) {
        where.push("channel_id = ?");
        params.push(Number(channel));
      }

      const limit = Math.max(1, Math.min(200, Number(pageSize) || 100));
      const offset = Math.max(0, ((Number(page) || 1) - 1) * limit);

      const sql =
        `SELECT id, type, path, uploaded_by_email, channel_id,
                validation_status AS status,
                NULL AS thumbnail_url
         FROM media
         ${where.length ? "WHERE " + where.join(" AND ") : ""}
         ORDER BY id DESC
         LIMIT ? OFFSET ?`;

      const rows = await db.all(sql, [...params, limit, offset]);

      // path alanını /files/... olarak dön
      const mapped = rows.map(r => ({
        ...r,
        path: toPublic(r.path)
      }));

      return res.json(mapped);
    } catch (e) {
      console.error("[/media/list] ERROR:", e);
      return res.status(503).json({ message: "Sunucuya ulaşılamıyor. Tekrar deneyin ya da yöneticinize ulaşın." });
    }
  }
);

// POST /media/:id/validate
app.post(
  "/media/:id/validate",
  authRequired(),
  roleRequired("inspector"),
  async (req, res) => {
    try {
      const id = Number(req.params.id);
      const { status } = req.body || {};
      const norm = String(status || "").toLowerCase();

      if (!["valid", "not_valid"].includes(norm)) {
        return res.status(400).json({ message: "status 'valid' veya 'not_valid' olmalı" });
      }

      const db = await getDb();
      const row = await db.get("SELECT validation_status FROM media WHERE id = ?", id);
      if (!row) return res.status(404).json({ message: "Bulunamadı" });
      if (row.validation_status) {
        return res.status(400).json({ message: "Daha önce onaylanmış" });
      }

      await db.run(
        "UPDATE media SET validation_status=?, validated_by_email=? WHERE id=?",
        [norm, req.user.email, id]
      );

      return res.json({ message: "İşlem başarılı" });
    } catch (e) {
      console.error("[/media/:id/validate] ERROR:", e);
      return res.status(503).json({ message: "Sunucuya ulaşılamıyor. Tekrar deneyin ya da yöneticinize ulaşın." });
    }
  }
);

// POST /media/:id/comment
app.post(
  "/media/:id/comment",
  authRequired(),
  roleRequired("inspector"),
  async (req, res) => {
    try {
      const id = Number(req.params.id);
      const { comment } = req.body || {};
      if (!comment || !String(comment).trim()) {
        return res.status(400).json({ message: "comment gerekli" });
      }

      const db = await getDb();
      const ex = await db.get("SELECT id FROM media WHERE id = ?", id);
      if (!ex) return res.status(404).json({ message: "Bulunamadı" });

      await db.run(
        "INSERT INTO comments (media_id,email,comment) VALUES (?,?,?)",
        [id, req.user.email, String(comment)]
      );

      return res.json({ message: "Yorum kaydedildi" });
    } catch (e) {
      console.error("[/media/:id/comment] ERROR:", e);
      return res.status(503).json({ message: "Sunucuya ulaşılamıyor. Tekrar deneyin ya da yöneticinize ulaşın." });
    }
  }
);

// ============== Root & Health ==============
app.get("/", (_req, res) => {
  res.json({ message: "Server çalışıyor 🚀" });
});
app.get("/health", (_req, res) => res.type("text/plain").send("ok"));

// -------------- START --------------
const start = async () => {
  await initDb();
  await seed();
  const PORT = Number(process.env.PORT) || 3000;
  const HOST = "0.0.0.0";
  app.listen(PORT, HOST, () => {
    console.log(`API running on http://${HOST}:${PORT}`);
  });
};
start();
