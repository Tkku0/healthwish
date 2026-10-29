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
    // macros (g) per meal — AI estimate / manual
    breakfastProtein: 0, breakfastCarbs: 0, breakfastFat: 0, breakfastFiber: 0, breakfastSugar: 0,
    lunchProtein: 0, lunchCarbs: 0, lunchFat: 0, lunchFiber: 0, lunchSugar: 0,
    dinnerProtein: 0, dinnerCarbs: 0, dinnerFat: 0, dinnerFiber: 0, dinnerSugar: 0,
    snacksProtein: 0, snacksCarbs: 0, snacksFat: 0, snacksFiber: 0, snacksSugar: 0,
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
  for (const k of Object.keys(merged)) {
    if (/(Protein|Carbs?|Fat|Fiber|Sugar)$/.test(k) || /Kcal$/.test(k) || k === "waterGlasses")
      merged[k] = Number(merged[k]) || 0;
  }
  return merged;
}

// ---- live sync-diagnostics (user ko exact error dikhane ke liye) ----
export const SYNC = { failCount: 0, lastCode: "", lastMsg: "", lastAt: 0, okAt: 0 };
export function syncStatus() { return { ...SYNC }; }

async function fsFetch(url, opts) {
  const token = await getValidToken();
  const headers = Object.assign({}, (opts && opts.headers) || {});
  if (token) headers.Authorization = `Bearer ${token}`;
  const res = await fetch(url, Object.assign({}, opts, { headers }));
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try {
      const j = await res.json();
      if (j.error && j.error.message) msg = `HTTP ${res.status}: ${j.error.message}`;
    } catch {}
    SYNC.failCount += 1;
    SYNC.lastCode = String(res.status);
    SYNC.lastMsg = String(msg).slice(0, 200);
    SYNC.lastAt = Date.now();
    throw new Error(msg);
  }
  SYNC.okAt = Date.now();
  SYNC.failCount = 0;
  return res;
}

// ---- API ----

// Login ke baad: apne uid ko couple doc ke members me daal do (email whitelist pe depend).
// couples/LOVE1 missing → create {members:[uid], partnerEmails:[own email]};
// exists → agar uid member nahi to members=+uid (maan lo partner ne email whitelist kiya hoga).
async function appendMembership(uid, email) {
  // Array-union transforms — doc padhe bina members/partnerEmails me apni uid/email append karo.
  const docPath = `projects/${PROJECT_ID}/databases/(default)/documents/couples/${COUPLE_ID}`;
  await fsFetch(`${BASE}:commit?key=${API_KEY}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      writes: [{
        transform: {
          document: docPath,
          fieldTransforms: [
            { fieldPath: "members", appendMissingElements: { values: [{ stringValue: uid }] } },
            { fieldPath: "partnerEmails", appendMissingElements: { values: [{ stringValue: String(email).toLowerCase() }] } },
          ],
        },
      }],
    }),
  });
}

let BOOTSTATE = "";
export function membershipStatus() { return BOOTSTATE; }

export async function registerMembership(email) {
  const token = await getValidToken();
  if (!token || !email) return;
  const url = `${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`;
  let doc = null;
  let docState = "unknown"; // found | notfound | forbidden | unknown
  try {
    const res = await fsFetch(url);
    doc = await res.json(); docState = "found";
  } catch (e) {
    const m = String(e.message || "");
    if (/404|not-found/i.test(m)) docState = "notfound";
    else if (/403|permission/i.test(m)) docState = "forbidden";
    doc = null;
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
    if (docState === "found") {
      const mask = ["members", "partnerEmails"].map((f) => `updateMask.fieldPaths=${f}`).join("&");
      try {
        await fsFetch(`${url}&${mask}`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ fields }),
        });
        BOOTSTATE = "member";
      } catch (eUpd) {
        BOOTSTATE = /403|permission/i.test(String(eUpd.message)) ? "update-denied" : "update-fail";
        throw eUpd;
      }
    } else {
      // notfound/forbidden/unknown — SAFE create try karo.
      // Precondition exists=false ka matlab: agar doc EXISTS hai to Firestore 409 dega,
      // hum kabhi bhi purana couple doc replace NAHI karenge (wo hi clobber bug tha).
      // Isse missing-doc deadlock khud theek ho jata hai (missing doc pe rules 403 deta hai).
      try {
        await fsFetch(`${url}&currentDocument.exists=false`, {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ fields }),
        });
        BOOTSTATE = "created";
      } catch (e2) {
        const m2 = String(e2.message || "");
        if (/409|ALREADY_EXISTS|already exists/i.test(m2)) {
          // doc exists (purane account ka) — recovery clause se append-repair try karo
          try {
            await appendMembership(uid, email);
            BOOTSTATE = "repaired";
          } catch (e3) {
            BOOTSTATE = "exists-locked";
          }
        } else {
          BOOTSTATE = /403|permission/i.test(m2) ? "create-denied" : "create-fail";
        }
      }
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

// ---- offline write queue (network down -> save later) ----
const OFFLINE_Q = "hw_offline_q";
function qRead() { try { return JSON.parse(localStorage.getItem(OFFLINE_Q) || "{}"); } catch { return {}; } }
function qWrite(q) { try { localStorage.setItem(OFFLINE_Q, JSON.stringify(q)); } catch {} }
export function pendingCount() { return Object.keys(qRead()).length; }
export async function flushOffline() {
  const q = qRead();
  let done = 0;
  for (const id of Object.keys(q)) {
    try {
      const url = `${BASE}/couples/${COUPLE_ID}/days/${id}?key=${API_KEY}`;
      await fsFetch(url, { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ fields: q[id].fields }) });
      delete q[id]; qWrite(q); done++;
    } catch (e) {
      const m = String(e.message || "");
      if (/^HTTP 4/.test(m)) { delete q[id]; qWrite(q); } // permanent reject — drop
      else break; // network issue — ruk jao
    }
  }
  return done;
}

// ---- couple doc extras: nudges / goals / cycle / weekly helpers ----
async function getCoupleFields() {
  const res = await fsFetch(`${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`);
  const j = await res.json();
  return j.fields || {};
}
export async function getNudges() {
  try {
    const f = await getCoupleFields();
    const arr = (((f.nudges || {}).arrayValue) || {}).values || [];
    return arr.map((v) => fromFs(v)).filter(Boolean);
  } catch { return []; }
}
export async function sendNudge(from, to, text) {
  const list = await getNudges();
  const next = [...list.slice(-9), { from, to, text: String(text).slice(0, 90), ts: Date.now() }];
  const url = `${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`;
  await fsFetch(`${url}&updateMask.fieldPaths=nudges`, {
    method: "PATCH", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields: { nudges: toFs(next) } }),
  });
  return next;
}
export async function getGoals() {
  try {
    const f = await getCoupleFields();
    const arr = (((f.goals || {}).arrayValue) || {}).values || [];
    return arr.map((v) => fromFs(v)).filter(Boolean);
  } catch { return []; }
}
export async function saveGoals(list) {
  const url = `${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`;
  await fsFetch(`${url}&updateMask.fieldPaths=goals`, {
    method: "PATCH", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields: { goals: toFs(list.slice(0, 30)) } }),
  });
}
export async function getCycle(profile) {
  try {
    const f = await getCoupleFields();
    const mv = f[`cycle_${profile}`];
    if (!mv || !mv.mapValue) return null;
    const out = {};
    for (const [k, v] of Object.entries(mv.mapValue.fields || {})) out[k] = fromFs(v);
    return out;
  } catch { return null; }
}
export async function setCycle(profile, obj) {
  const url = `${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`;
  await fsFetch(`${url}&updateMask.fieldPaths=cycle_${profile}`, {
    method: "PATCH", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields: { [`cycle_${profile}`]: toFs(obj) } }),
  });
}

// ---- lab report storage (days collection me lab_* docs — existing rules ke andar) ----
export async function listLabs(limit = 30) {
  try {
    const res = await fsFetch(`${BASE}:runQuery?key=${API_KEY}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ structuredQuery: { from: [{ collectionId: "days" }], limit: 300 } }),
    });
    const rows = await res.json();
    const out = [];
    for (const r of rows || []) {
      if (!r.document) continue;
      const id = r.document.name.split("/").pop();
      if (!id.startsWith("lab_")) continue;
      const o = { id };
      for (const [k, v] of Object.entries(r.document.fields || {})) o[k] = fromFs(v);
      if (!o.deleted) out.push(o);
    }
    return out.sort((a, b) => (Number(b.ts) || 0) - (Number(a.ts) || 0)).slice(0, limit);
  } catch { return []; }
}
export async function saveLab(lab) {
  const id = lab.id || `lab_${Date.now().toString(36)}`;
  const fields = {};
  for (const [k, v] of Object.entries(lab)) if (k !== "id") fields[k] = toFs(v);
  await fsFetch(`${BASE}/couples/${COUPLE_ID}/days/${id}?key=${API_KEY}`, {
    method: "PATCH", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields }),
  });
  return id;
}
export async function deleteLab(id) {
  await fsFetch(`${BASE}/couples/${COUPLE_ID}/days/${id}?key=${API_KEY}`, {
    method: "PATCH", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields: { deleted: toFs(true), dataUrl: toFs(""), title: toFs("(deleted)") } }),
  });
}

// ---- couple membership management (Settings) ----
export async function getCoupleInfo() {
  try {
    const res = await fsFetch(`${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`);
    const j = await res.json();
    const f = j.fields || {};
    return {
      members: ((f.members || {}).values || []).map((v) => v.stringValue).filter(Boolean),
      partnerEmails: ((f.partnerEmails || {}).values || []).map((v) => v.stringValue).filter(Boolean),
    };
  } catch {
    return { members: [], partnerEmails: [] };
  }
}

export async function setPartnerEmails(emails) {
  const url = `${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`;
  const fields = { partnerEmails: { arrayValue: { values: emails.map((e) => ({ stringValue: e.toLowerCase() })) } } };
  const mask = "updateMask.fieldPaths=partnerEmails";
  await fsFetch(`${url}&${mask}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields }),
  });
}

// ---- shared AI config (tumne set ki ho to partner ko bhi chale) ----
// NOTE: galti-khane ka rakam — getSharedAI errors ko khata hai (return null).
// Diagnostic ke liye rawCoupleProbe use karo jo asli status batata hai.
export async function rawCoupleProbe() {
  try {
    const res = await fsFetch(`${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`);
    let hint = "";
    try {
      const j = await res.json();
      const f = (j.fields && Object.keys(j.fields)) || [];
      hint = `fields: ${f.length ? f.join(",") : "khali"}`;
    } catch {
      hint = "body-parse-fail";
    }
    return `OK HTTP ${res.status} — ${hint}`;
  } catch (e) {
    const code = e && e.status ? `HTTP ${e.status}` : "HTTP ?";
    return `ERR ${code} — ${String((e && e.message) || e).slice(0, 100)}`;
  }
}

export async function getSharedAI() {
  try {
    const res = await fsFetch(`${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`);
    const j = await res.json();
    const mv = j.fields && j.fields.aiCfg;
    if (!mv || !mv.mapValue) return null;
    const out = {};
    for (const [k, v] of Object.entries(mv.mapValue.fields || {})) out[k] = fromFs(v);
    return out.key && out.key.length >= 4 ? out : null;
  } catch {
    return null;
  }
}

export async function setSharedAI(cfg) {
  const url = `${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`;
  const fields = { aiCfg: toFs(cfg) };
  const mask = "updateMask.fieldPaths=aiCfg";
  await fsFetch(`${url}&${mask}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields }),
  });
}

// ---- per-person health profile (medical history / meds) — alag field per profile, no mismatch ----
export function healthLocal(profile) {
  try { return JSON.parse(localStorage.getItem(`hw_health_${profile}`) || "null") || {}; } catch { return {}; }
}
export function saveHealthLocal(profile, obj) {
  localStorage.setItem(`hw_health_${profile}`, JSON.stringify(obj));
}
export async function getHealthRemote(profile) {
  try {
    const res = await fsFetch(`${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`);
    const j = await res.json();
    const mv = j.fields && j.fields[`health_${profile}`];
    if (!mv || !mv.mapValue) return null;
    const out = {};
    for (const [k, v] of Object.entries(mv.mapValue.fields || {})) out[k] = fromFs(v);
    return out;
  } catch { return null; }
}
export async function getNoFap(profile) {
  try {
    const res = await fsFetch(`${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`);
    const j = await res.json();
    const mv = j.fields && j.fields[`nofap_${profile}`];
    if (!mv || !mv.mapValue) return [];
    const fm = mv.mapValue.fields || {};
    const em = fm.events && fm.events.arrayValue ? fm.events.arrayValue.values : [];
    const out = (em || []).map((x) => {
      const f = (x.mapValue && x.mapValue.fields) || {};
      return { d: fromFs(f.d) || "", t: fromFs(f.t) || "", ts: fromFs(f.ts) || 0 };
    });
    return out.filter((x) => x.d);
  } catch { return []; }
}

export async function setNoFap(profile, events) {
  const url = `${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`;
  const fp = `nofap_${profile}`;
  const mask = `updateMask.fieldPaths=${fp}`;
  await fsFetch(`${url}&${mask}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields: { [fp]: toFs({ events }) } }),
  });
}

export async function setHealthRemote(profile, obj) {
  const url = `${BASE}/couples/${COUPLE_ID}?key=${API_KEY}`;
  const fp = `health_${profile}`;
  const mask = `updateMask.fieldPaths=${fp}`;
  await fsFetch(`${url}&${mask}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ fields: { [fp]: toFs(obj) } }),
  });
}

export async function getDay(dateKey, ownerName) {
  const id = docIdFor(dateKey, ownerName);
  const url = `${BASE}/couples/${COUPLE_ID}/days/${id}?key=${API_KEY}`;
  try {
    const res = await fsFetch(url);
    const j = await res.json();
    const doc = parseDoc(j.name, j.fields);
    try { localStorage.setItem(`hw_daycache_${id}`, JSON.stringify(doc)); } catch {}
    return doc;
  } catch (e) {
    const m = String(e.message || "");
    if (!/^HTTP \d/.test(m)) {
      try {
        const cached = JSON.parse(localStorage.getItem(`hw_daycache_${id}`) || "null");
        if (cached) return { ...cached, offline: true };
      } catch {}
    }
    throw e;
  }
}

export async function existsDay(dateKey, ownerName) {
  try {
    await getDay(dateKey, ownerName);
    return true;
  } catch {
    return false;
  }
}

// Full-doc write (Android saveDay jaisa) + screenOnTime preserve
export async function saveDay(log) {
  let prev = {};
  try {
    prev = await getDay(log.dateKey, log.ownerName);
  } catch {}
  const merged = { ...prev, ...log, screenOnTime: log.screenOnTime ?? (prev.screenOnTime || "") };
  merged.updatedAt = new Date().toISOString(); // browser timestamp string; Android Timestamp? nahi — sirf string field
  const fields = {};
  for (const [k, v] of Object.entries(merged)) {
    if (k === "updatedAt") continue; // avoid type clash with legacy timestamps
    fields[k] = toFs(v);
  }
  const id = docIdFor(log.dateKey, log.ownerName);
  const url = `${BASE}/couples/${COUPLE_ID}/days/${id}?key=${API_KEY}`;
  try {
    await fsFetch(url, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ fields }),
    });
    try { localStorage.setItem(`hw_daycache_${id}`, JSON.stringify({ ...merged, id })); } catch {}
  } catch (e) {
    const m = String(e.message || "");
    if (!/^HTTP \d/.test(m)) {
      const q = qRead();
      q[id] = { fields, at: Date.now() };
      qWrite(q);
      try { localStorage.setItem(`hw_daycache_${id}`, JSON.stringify({ ...merged, id })); } catch {}
      return { ...merged, id, offline: true };
    }
    throw e;
  }
  return { ...merged, id };
}

// Recent N days for one profile (History + CSV + weight chart)
// runQuery scan rules me get() (couple-membership) ho jab Firestore QUERY-level 403 abort kar deta hai —
// isliye pehle single-collection scan chalta hai; fail/khali pe batchGet (per-doc reads, rules-safe)
// pichhle N din ke doc-IDs seedha marta hai. Doc-ID format hi authority hai: {date}_{profile-lower}.
async function batchGetDays(ownerName, spanDays) {
  // Firestore hard limit: 300 docs per batchGet → chunked 250 rakho (rules ke get() ka per-doc check bhi members ke liye safe rahta hai)
  const span = Math.min(Math.max(spanDays, 1), 400);
  const names = [];
  for (let i = 0; i < span; i++) {
    names.push(`projects/${PROJECT_ID}/databases/(default)/documents/couples/${COUPLE_ID}/days/${docIdFor(shiftDateKey(todayKey(), -i), ownerName)}`);
  }
  const CH = 250;
  const out = [];
  for (let ci = 0; ci < names.length; ci += CH) {
    const slice = names.slice(ci, ci + CH);
    const res = await fsFetch(`${BASE}:batchGet?key=${API_KEY}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ documents: slice }),
    });
    const rows = await res.json();
    for (const r of rows) {
      if (r.found) out.push(parseDoc(r.found.name, r.found.fields));
    }
  }
  const seen = new Set();
  const ded = out.filter((d) => (seen.has(d.id) ? false : (seen.add(d.id), true)));
  ded.sort((a, b) => (b.id > a.id ? 1 : b.id < a.id ? -1 : 0));
  return ded;
}

export async function listRecent(ownerName, limit = 30) {
  const url = `${BASE}:runQuery?key=${API_KEY}`;
  const q = {
    structuredQuery: {
      from: [{ collectionId: "days" }],
      limit: 300,
    },
  };
  let scanned = null;
  try {
    const res = await fsFetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(q),
    });
    const rows = await res.json();
    if (Array.isArray(rows) && !(rows.length === 1 && rows[0].error)) {
      const suffix = "_" + ownerName.toLowerCase();
      const out = [];
      for (const r of rows) {
        if (!r.document) continue;
        const id = r.document.name.split("/").pop();
        if (!id.endsWith(suffix)) continue;
        out.push(parseDoc(r.document.name, r.document.fields));
      }
      scanned = out;
    }
  } catch {
    scanned = null;
  }
  if (scanned && scanned.length) {
    scanned.sort((a, b) => (b.id > a.id ? 1 : b.id < a.id ? -1 : 0));
    return scanned.slice(0, limit);
  }
  try {
    return await batchGetDays(ownerName, Math.min(limit + 90, 400));
  } catch {
    // last resort: aaj + kal
    const out = [];
    for (const dk of [todayKey(), shiftDateKey(todayKey(), -1)]) {
      try { out.push(await getDay(dk, ownerName)); } catch {}
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
