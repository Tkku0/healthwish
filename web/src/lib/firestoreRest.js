// Firestore REST client — browser se seedha, koi SDK nahi (light + fast).
// Auth: Firebase Auth token hota hai to Authorization header; warna (purana open-rules mode) bare request.
import { getValidToken } from "./firebaseAuth.js";

const PROJECT_ID = "healthwish-6ca22";
const API_KEY = "AIzaSyCoi_9edMNxWd__lBHb0Kj2sPHOXDaHOiI";
const BASE = `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents`;
const COUPLE_ID = "LOVE1";

export const PROFILE_PRESETS = ["Tkku", "Shumu"];

export function todayKey() {
  const d = new Date();
  const p = (n) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

export function shiftDateKey(key, days) {
  const [y, m, d] = key.split("-").map(Number);
  const dt = new Date(y, m - 1, d + days);
  const p = (n) => String(n).padStart(2, "0");
  return `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`;
}

export function docIdFor(dateKey, ownerName) {
  return `${dateKey}_${ownerName.toLowerCase()}`;
}

// ---- default tasks (Android DayLog.defaultTasks jaisa) ----
export function defaultTasks() {
  return [
    { id: "morning_walk", title: "Morning Walk", emoji: "🚶", done: false, isDefault: true, time: "6:30 AM" },
    { id: "exercise", title: "Exercise", emoji: "💪", done: false, isDefault: true, time: "" },
    { id: "meditation", title: "Meditation", emoji: "🧘", done: false, isDefault: true, time: "" },
    { id: "evening_walk", title: "Evening Walk", emoji: "🌇", done: false, isDefault: true, time: "6:00 PM" },
  ];
}

export function emptyDay(dateKey, ownerName) {
  return {
    id: docIdFor(dateKey, ownerName),
    ownerName,
    coupleId: COUPLE_ID,
    dateKey,
    breakfast: "", lunch: "", dinner: "", snacks: "",
    breakfastKcal: 0, lunchKcal: 0, dinnerKcal: 0, snacksKcal: 0,
    waterGlasses: 0,
    wakeupTime: "", sleepTime: "", sleepMood: "", dayMood: "",
    dayNote: "",
    screenOnTime: "",
    weightKg: 0,
    tasks: defaultTasks(),
  };
}

// ---- value converters ----
function toFs(v) {
  if (typeof v === "string") return { stringValue: v };
  if (typeof v === "boolean") return { booleanValue: v };
  if (Number.isInteger(v)) return { integerValue: String(v) };
  if (typeof v === "number") return { doubleValue: v };
  if (Array.isArray(v)) {
    return { arrayValue: { values: v.length ? v.map(toFs) : [] } };
  }
  if (v && typeof v === "object") {
    const fields = {};
    for (const [k, val] of Object.entries(v)) fields[k] = toFs(val);
    return { mapValue: { fields } };
  }
  return { stringValue: String(v ?? "") };
}

function fromFs(f) {
  if (f == null) return undefined;
  if ("stringValue" in f) return f.stringValue ?? "";
  if ("booleanValue" in f) return f.booleanValue;
  if ("integerValue" in f) return parseInt(f.integerValue, 10) || 0;
  if ("doubleValue" in f) return f.doubleValue ?? 0;
  if ("arrayValue" in f) {
    const vals = (f.arrayValue && f.arrayValue.values) || [];
    return vals.map(fromFs);
  }
  if ("mapValue" in f) {
    const out = {};
    const fields = (f.mapValue && f.mapValue.fields) || {};
    for (const [k, v] of Object.entries(fields)) out[k] = fromFs(v);
    return out;
  }
  return undefined;
}

function parseDoc(name, fields) {
  // name like "projects/x/databases/(default)/documents/couples/LOVE1/days/2026-10-04_tkku"
  const id = name.split("/").pop();
  const raw = {};
  for (const [k, v] of Object.entries(fields || {})) raw[k] = fromFs(v);
  // defaults merge — schema khali ho to 0/"" se bharo
  const base = emptyDay(raw.dateKey || id.split("_")[0], raw.ownerName || id.split("_").slice(1).join("_"));
  const merged = { ...base, ...raw, id };
  if (!Array.isArray(merged.tasks) || !merged.tasks.length) merged.tasks = defaultTasks();
  else
    merged.tasks = merged.tasks.map((t, i) => ({
      id: String(t.id ?? `t${i}`),
      title: String(t.title ?? "Task"),
      emoji: String(t.emoji ?? "✅"),
      done: !!t.done,
      isDefault: t.isDefault !== false,
      time: String(t.time ?? ""),
    }));
  merged.weightKg = Number(merged.weightKg) || 0;
  for (const m of ["breakfastKcal", "lunchKcal", "dinnerKcal", "snacksKcal", "waterGlasses"])
    merged[m] = Number(merged[m]) || 0;
  return merged;
}

async function fsFetch(url, opts) {
  const token = await getValidToken();
  const headers = Object.assign({}, (opts && opts.headers) || {});
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(url, Object.assign({}, opts, { headers }));
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try {
      const j = await res.json();
      if (j.error && j.error.message) msg = j.error.message;
    } catch {}
    throw new Error(msg);
  }
  return res;
}

// ---- API ----

// Login ke baad: apne uid ko couple doc ke members me daal do (email whitelist pe depend).
// couples/LOVE1 missing → create {members:[uid], partnerEmails:[own email]};
// exists → agar uid member nahi to members=+uid (maan lo partner ne email whitelist kiya hoga).
export async function registerMembership(email) {
  const token = await getValidToken();
  if (!token || !email) return;
  const url = `${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`;
  let doc = null;
  try {
    const res = await fsFetch(url);
    doc = await res.json();
  } catch {
    doc = null; // 404 ya (rules ke baad) 403 — dono me create koshiysh
  }
  let members = [];
  let partnerEmails = [];
  if (doc && doc.fields) {
    members = (doc.fields.members && doc.fields.members.values || []).map((v) => v.stringValue).filter(Boolean);
    partnerEmails = (doc.fields.partnerEmails && doc.fields.partnerEmails.values || []).map((v) => v.stringValue).filter(Boolean);
  }
  if (members.includes(emailToUidHint())) return; // uid already member
  // token me se actual uid chahiye
  try {
    const uid = await authUid();
    if (members.includes(uid)) return;
    members.push(uid);
    if (!partnerEmails.includes(email.toLowerCase())) partnerEmails.push(email.toLowerCase());
    const fields = {
      members: { arrayValue: { values: members.map((m) => ({ stringValue: m })) } },
      partnerEmails: { arrayValue: { values: partnerEmails.map((m) => ({ stringValue: m })) } },
    };
    if (doc) {
      const mask = ["members", "partnerEmails"].map((f) => `updateMask.fieldPaths=${f}`).join("&");
      await fsFetch(`${url}&${mask}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ fields }),
      });
    } else {
      // fixed-ID doc create — PATCH (bina updateMask) existing doc create/replace karta hai
      await fsFetch(url, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ fields }),
      });
    }
  } catch {
    // rules strict bane to bhi web chalega agar pehle se member tha — chup raho
  }
}

let cachedUid = null;
function emailToUidHint() {
  return cachedUid || "";
}

async function authUid() {
  const token = await getValidToken();
  if (!token) throw new Error("no auth");
  const payload = JSON.parse(atob(token.split(".")[1]));
  cachedUid = payload.user_id || payload.sub;
  return cachedUid;
}

export async function getDay(dateKey, ownerName) {
  const id = docIdFor(dateKey, ownerName);
  const url = `${BASE}/couples/${COUPLE_ID}/days/${id}?key=${API_KEY}`;
  const res = await fsFetch(url);
  const j = await res.json();
  return parseDoc(j.name, j.fields);
}

export async function existsDay(dateKey, ownerName) {
  try {
    await getDay(dateKey, ownerName);
    return true;
  } catch {
    return false;
  }
}

// Full-doc write (Android saveDay jaisa) + screenOnTime preserve (web usko touch nahi karta)
export async function saveDay(log) {
  let prev = {};
  try {
    prev = await getDay(log.dateKey, log.ownerName);
  } catch {}
  const merged = { ...prev, ...log, screenOnTime: prev.screenOnTime || log.screenOnTime || "" };
  merged.updatedAt = new Date().toISOString(); // browser timestamp string; Android Timestamp? nahi — sirf string field
  const fields = {};
  for (const [k, v] of Object.entries(merged)) {
    if (k === "updatedAt") continue; // avoid type clash with legacy timestamps
    fields[k] = toFs(v);
  }
  const id = docIdFor(log.dateKey, log.ownerName);
  const url = `${BASE}/couples/${COUPLE_ID}/days/${id}?key=${API_KEY}`;
  await fsFetch(url, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields }),
  });
  return { ...merged, id };
}

// Recent N days for one profile (History + CSV + weight chart)
export async function listRecent(ownerName, limit = 30) {
  const url = `${BASE}:runQuery?key=${API_KEY}`;
  const q = {
    structuredQuery: {
      from: [{ collectionId: "days" }],
      where: {
        compositeFilter: {
          op: "AND",
          filters: [
            { fieldFilter: { field: { fieldPath: "coupleId" }, op: "EQUAL", value: { stringValue: COUPLE_ID } } },
            { fieldFilter: { field: { fieldPath: "ownerName" }, op: "EQUAL", value: { stringValue: ownerName } } },
          ],
        },
      },
      orderBy: [{ field: { fieldPath: "dateKey" }, direction: "DESCENDING" }],
      limit,
    },
  };
  try {
    const res = await fsFetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(q),
    });
    const rows = await res.json();
    const out = [];
    for (const r of rows) {
      if (r.document) out.push(parseDoc(r.document.name, r.document.fields));
    }
    return out;
  } catch (e) {
    // Structured query block ho (rules) to seedha-doc fallback: aaj + kal
    const out = [];
    for (const dk of [todayKey(), shiftDateKey(todayKey(), -1)]) {
      try {
        out.push(await getDay(dk, ownerName));
      } catch {}
    }
    return out;
  }
}

// Legacy ENC1 sanitize (Android jaisa) — agar purani cipher dikhe to blank + default name
export function cleanTasks(tasks) {
  return (tasks || []).map((t) => {
    let title = String(t.title || "");
    let emoji = String(t.emoji || "✅");
    if (title.startsWith("ENC1:") || !title.trim()) {
      const defaults = defaultTasks();
      const d = defaults.find((x) => x.id === t.id);
      return { ...t, title: d ? d.title : "", emoji: d ? d.emoji : "✅", isDefault: !!d };
    }
    return { ...t, title, emoji };
  });
}
