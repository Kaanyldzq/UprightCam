import { signToken } from "./auth.mjs";

// DEMO kullanıcılar (DB yerine). İstersen DB'ye bağlayabilirsin.
const USERS = {
  "streamer@example.com":  { id: 1, email: "streamer@example.com",  password: "123456", role: "streamer"  },
  "inspector@example.com": { id: 2, email: "inspector@example.com", password: "123456", role: "inspector" },
};

export async function login(req, res) {
  const { email, password } = req.body || {};
  const user = USERS[email];
  if (!user || user.password !== password) {
    return res.status(401).json({ message: "Invalid credentials" });
  }

  const token = signToken({ id: user.id, email: user.email, role: user.role });

  // Android LoginResponse ile uyumlu
  return res.json({
    token,
    role: user.role,
    email: user.email
  });
}

export async function userRole(req, res) {
  // authRequired middleware'i req.user'ı dolduruyor
  return res.json({ role: req.user?.role || null });
}

export async function logout(req, res) {
  // istersen req.token'ı revoked tablosuna ekleyebilirsin
  return res.json({ message: "ok" });
}
