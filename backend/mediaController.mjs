export async function upload(req, res) {
  // Sadece STREAMER (route'ta guard var)
  return res.json({ message: "Uploaded (stub)" });
}

export async function validate(req, res) {
  const { id } = req.params;
  const { status } = req.body || {}; // "valid" | "not_valid"
  if (!["valid", "not_valid"].includes(status)) {
    return res.status(400).json({ message: "status must be valid|not_valid" });
  }
  // Sadece INSPECTOR
  return res.json({ message: `Status of ${id} -> ${status}` });
}

export async function comment(req, res) {
  const { id } = req.params;
  const { comment } = req.body || {};
  // Sadece INSPECTOR
  return res.json({ message: `Comment saved for ${id}`, comment });
}

export async function list(req, res) {
  // STREAMER + INSPECTOR
  return res.json([
    { id: 101, type: "video", path: "https://example.com/v1.mp4",  thumbnail_url: null, status: "waiting" },
    { id: 102, type: "photo", path: "https://example.com/p1.jpg", thumbnail_url: null, status: "waiting" },
  ]);
}

export async function listVideos(req, res) {
  return res.json({ items: [
    { id: 201, path: "https://example.com/v2.mp4", thumbnail_url: null, status: "waiting", channel_id: 1 },
  ]});
}
