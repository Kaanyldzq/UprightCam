// src/auth.mjs
import jwt from "jsonwebtoken";
import dotenv from "dotenv";
import { getDb } from "./db.mjs";
dotenv.config();

export function signToken(payload) {
  return jwt.sign(payload, process.env.JWT_SECRET, { expiresIn: "7d" });
}

export async function isRevoked(token) {
  const db = await getDb();
  const row = await db.get(
    "SELECT id FROM revoked_tokens WHERE token = ?",
    token
  );
  return !!row;
}

export function authRequired() {
  return async (req, res, next) => {
    try {
      const auth = req.headers.authorization || "";
      const token = auth.startsWith("Bearer ")
        ? auth.slice(7)
        : (req.body?.token || "");

      if (!token) {
        return res.status(401).json({ message: "Token gerekli" });
      }

      if (await isRevoked(token)) {
        return res.status(401).json({ message: "Token geçersiz" });
      }

      const decoded = jwt.verify(token, process.env.JWT_SECRET);
      // decoded içinde { id, email, role } bekliyoruz
      if (!decoded?.role) {
        return res.status(401).json({ message: "Token geçersiz (rol eksik)" });
      }

      req.user = decoded; // { id, email, role, ... }
      req.token = token;
      next();
    } catch (e) {
      return res.status(401).json({ message: "Token geçersiz" });
    }
  };
}

/**
 * Belirtilen rollerden en az birine sahip olmayı zorunlu kılar.
 * Örnek kullanım:
 *   roleRequired("streamer")
 *   roleRequired("inspector")
 *   roleRequired("streamer", "inspector")
 */
export function roleRequired(...roles) {
  return (req, res, next) => {
    const role = req.user?.role;
    if (!role) {
      return res.status(401).json({ message: "Yetkisiz" });
    }
    if (!roles.includes(role)) {
      return res.status(403).json({ message: "Yetki yok" });
    }
    next();
  };
}

// Kolay kısayollar (isteğe bağlı):
export const requireStreamer  = () => roleRequired("streamer");
export const requireInspector = () => roleRequired("inspector");
export const requireAnyUser   = () => roleRequired("streamer", "inspector");
