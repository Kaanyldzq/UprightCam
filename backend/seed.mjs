import bcrypt from "bcryptjs";
import { getDb } from "./db.mjs";

export async function seed() {
  const db = await getDb();

  const users = [
    {
      email: "streamer@example.com",
      password: "123456",
      firstname: "Ali",
      surname: "Streamer",
      role: "streamer"
    },
    {
      email: "inspector@example.com",
      password: "123456",
      firstname: "Ayşe",
      surname: "Inspector",
      role: "inspector"
    }
  ];

  for (const u of users) {
    const exists = await db.get("SELECT id FROM users WHERE email = ?", u.email);
    if (!exists) {
      const hash = await bcrypt.hash(u.password, 10);
      await db.run(
        `INSERT INTO users (email,password_hash,firstname,surname,role)
         VALUES (?,?,?,?,?)`,
        [u.email, hash, u.firstname, u.surname, u.role]
      );
    }
  }

  const channels = [
    { name: "Kanal 1", status: "active" },
    { name: "Kanal 2", status: "passive" },
    { name: "Kanal 3", status: "active" },
  ];

  for (const c of channels) {
    const exists = await db.get("SELECT id FROM channels WHERE name = ?", c.name);
    if (!exists) {
      await db.run(`INSERT INTO channels (name,status) VALUES (?,?)`, [c.name, c.status]);
    }
  }
}
