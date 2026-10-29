const CFG_KEY = "hw_notify_cfg";

export function getNotifyCfg() {
  try {
    const c = JSON.parse(localStorage.getItem(CFG_KEY) || "null");
    return c || { on: false, start: "09:00", end: "21:00", every: 90 };
  } catch {
    return { on: false, start: "09:00", end: "21:00", every: 90 };
  }
}
export function saveNotifyCfg(cfg) {
  try { localStorage.setItem(CFG_KEY, JSON.stringify(cfg)); } catch {}
}
export function notifSupported() {
  return typeof window !== "undefined" && "Notification" in window;
}
export function notifGranted() {
  return notifSupported() && Notification.permission === "granted";
}
export async function askNotif() {
  if (!notifSupported()) return false;
  if (Notification.permission === "granted") return true;
  try {
    const r = await Notification.requestPermission();
    return r === "granted";
  } catch {
    return false;
  }
}
export function notify(title, body) {
  try {
    if (notifGranted()) {
      new Notification(title, { body, icon: "./icon-192.png", tag: "healthwish" });
      return true;
    }
  } catch {}
  return false;
}
export function toMin(str) {
  const m = /^(\d{1,2}):(\d{2})/.exec(String(str || "").trim());
  if (!m) return null;
  const h = +m[1], mi = +m[2];
  if (h > 23 || mi > 59) return null;
  return h * 60 + mi;
}
export function cleanTime(str) {
  const s = String(str || "").trim().toUpperCase();
  const m12 = /^(\d{1,2}):(\d{2})\s*(AM|PM)$/.exec(s);
  if (m12) {
    let h = +m12[1] % 12;
    if (m12[3] === "PM") h += 12;
    return `${String(h).padStart(2, "0")}:${m12[2]}`;
  }
  const m24 = /^(\d{1,2}):(\d{2})$/.exec(s);
  if (m24) return `${String(+m24[1]).padStart(2, "0")}:${m24[2]}`;
  return null;
}
export function todayStr() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}
const MED_RE = /medicine|dawa|dawai|tablet|vitamin|supplement|💊|💉/i;
export function medTasks(tasks) {
  return (tasks || []).filter((t) => MED_RE.test(`${t.emoji || ""} ${t.title || ""}`) && t.time);
}
export function tickWater(cfg) {
  if (!cfg || !cfg.on) return false;
  const now = new Date();
  const mins = now.getHours() * 60 + now.getMinutes();
  const a = toMin(cfg.start), b = toMin(cfg.end);
  if (a == null || b == null || mins < a || mins > b) return false;
  const last = Number(localStorage.getItem("hw_wr_last") || 0);
  const gap = Math.max(30, Number(cfg.every) || 90) * 60000;
  if (Date.now() - last < gap) return false;
  const fired = notify("Paani ka samay 💧", "Aaj ka paani target poora karein — ek glass paani pi lein.");
  if (fired) localStorage.setItem("hw_wr_last", String(Date.now()));
  return fired;
}
export function tickMed(day) {
  if (!day) return 0;
  const tasks = medTasks(day.tasks);
  if (!tasks.length) return 0;
  const now = new Date();
  const cur = `${String(now.getHours()).padStart(2, "0")}:${String(now.getMinutes()).padStart(2, "0")}`;
  let n = 0;
  for (const t of tasks) {
    if (t.done) continue;
    const ct = cleanTime(t.time);
    if (!ct || ct !== cur) continue;
    const key = `hw_med_${todayStr()}_${t.id}`;
    if (localStorage.getItem(key)) continue;
    if (notify("Reminder", `${t.emoji || "💊"} ${t.title} — ab time ho gaya hai.`)) {
      localStorage.setItem(key, "1");
      n++;
    }
  }
  return n;
}
