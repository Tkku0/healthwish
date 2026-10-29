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

// ---- tone (app khula ho tab custom sound; OS notification sound alag hota hai) ----
const TONE_KEY = "hw_tone";
export function getTonePref() {
  try { return localStorage.getItem(TONE_KEY) || "chime"; } catch { return "chime"; }
}
export function setTonePref(v) {
  try { localStorage.setItem(TONE_KEY, v); } catch {}
}
let audioCtx = null;
function ctx() {
  try {
    audioCtx = audioCtx || new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === "suspended") audioCtx.resume();
    return audioCtx;
  } catch { return null; }
}
export function primeAudio() {
  const c = ctx();
  if (!c) return;
  try {
    const o = c.createOscillator();
    const g = c.createGain();
    g.gain.value = 0.0001;
    o.connect(g); g.connect(c.destination);
    o.start(); o.stop(c.currentTime + 0.05);
  } catch {}
}
export function saveCustomTone(file) {
  return new Promise((resolve, reject) => {
    if (!file) return reject(new Error("file nahi mili"));
    if (file.size > 250 * 1024) return reject(new Error("audio 250KB se chhota rakho"));
    const fr = new FileReader();
    fr.onload = () => {
      try { localStorage.setItem("hw_tone_custom", String(fr.result)); setTonePref("custom"); resolve(true); }
      catch { reject(new Error("storage full — chhota file try karo")); }
    };
    fr.onerror = () => reject(new Error("file read nahi hui"));
    fr.readAsDataURL(file);
  });
}
export function playTone() {
  const pref = getTonePref();
  if (pref === "none") return;
  if (pref === "custom") {
    try {
      const raw = localStorage.getItem("hw_tone_custom");
      if (raw) { const a = new Audio(raw); a.volume = 0.9; a.play().catch(() => {}); return; }
    } catch {}
  }
  const c = ctx();
  if (!c) return;
  const t = c.currentTime;
  const seq = pref === "bell" ? [[880, 0, 0.18], [1320, 0.2, 0.3]]
    : pref === "soft" ? [[523, 0, 0.25], [659, 0.28, 0.35]]
    : [[784, 0, 0.12], [988, 0.14, 0.12], [1175, 0.28, 0.22]];
  for (const [f, at, dur] of seq) {
    const o = c.createOscillator();
    const g = c.createGain();
    o.type = "sine";
    o.frequency.value = f;
    g.gain.setValueAtTime(0.0001, t + at);
    g.gain.exponentialRampToValueAtTime(0.25, t + at + 0.02);
    g.gain.exponentialRampToValueAtTime(0.0001, t + at + dur);
    o.connect(g); g.connect(c.destination);
    o.start(t + at); o.stop(t + at + dur + 0.05);
  }
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
export function noteWater(day) {
  const tk = todayStr();
  let st = { day: tk, val: -1, addAt: 0 };
  try { st = JSON.parse(localStorage.getItem("hw_water_state") || "null") || st; } catch {}
  if (st.day !== tk) st = { day: tk, val: -1, addAt: 0 };
  const cur = Number(day && day.waterGlasses) || 0;
  if (st.val < 0) {
    st.val = cur;
    st.addAt = cur > 0 ? Date.now() : 0;
  } else if (cur > st.val) {
    st.addAt = Date.now();
    st.val = cur;
  } else if (cur < st.val) {
    st.val = cur;
  }
  try { localStorage.setItem("hw_water_state", JSON.stringify(st)); } catch {}
  return st;
}
export function tickWater(cfg, day) {
  if (!cfg || !cfg.on || !day) return false;
  const st = noteWater(day);
  if ((Number(day.waterGlasses) || 0) >= 8) return false;
  if (Date.now() - (st.addAt || 0) < 2 * 3600 * 1000) return false;
  const now = new Date();
  const mins = now.getHours() * 60 + now.getMinutes();
  const a = toMin(cfg.start), b = toMin(cfg.end);
  if (a == null || b == null || mins < a || mins > b) return false;
  const last = Number(localStorage.getItem("hw_wr_last") || 0);
  const gap = Math.max(30, Number(cfg.every) || 90) * 60000;
  if (Date.now() - last < gap) return false;
  notify("Paani ka samay 💧", "2 ghante se paani log nahi hua — ek glass paani pi lein.");
  playTone();
  localStorage.setItem("hw_wr_last", String(Date.now()));
  return true;
}
export function tickMed(day) {
  if (!day) return 0;
  const tasks = medTasks(day.tasks);
  if (!tasks.length) return 0;
  const now = new Date();
  const curMin = now.getHours() * 60 + now.getMinutes();
  let n = 0;
  for (const t of tasks) {
    if (t.done) continue;
    const ct = cleanTime(t.time);
    const tMin = toMin(ct);
    if (tMin == null) continue;
    if (curMin < tMin || curMin > tMin + 10) continue;
    const key = `hw_med_${todayStr()}_${t.id}`;
    if (localStorage.getItem(key)) continue;
    notify("Reminder", `${t.emoji || "💊"} ${t.title} — ab time ho gaya hai.`);
    playTone();
    localStorage.setItem(key, "1");
    n++;
  }
  return n;
}
