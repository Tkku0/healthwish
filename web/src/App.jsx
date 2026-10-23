import React, { useEffect, useMemo, useRef, useState, useCallback } from "react";
import {
  todayKey, shiftDateKey, docIdFor, emptyDay, getDay, saveDay, listRecent, defaultTasks,
  PROFILE_PRESETS, cleanTasks, registerMembership, getCoupleInfo, setPartnerEmails, getSharedAI, setSharedAI,
  healthLocal, saveHealthLocal, getHealthRemote, setHealthRemote, getNoFap, setNoFap, syncStatus, rawCoupleProbe, membershipStatus,
} from "./lib/firestoreRest.js";
import {
  PRESETS, DEFAULT_RULES, getAI, saveAI, clearAI, isConfigured, testAI, parseFoodInput, estimateKcal,
  estimateMealNutrition, storeShared, usingShared, istSlot, istNow, getTone, setTone,
  coupleCompareOn, setCoupleCompare, coachMessage, coachChat,
} from "./lib/ai.js";
import { getSession, emailAuth, logout as authLogout, getValidToken } from "./lib/firebaseAuth.js";
import { dailyScore, weeklyScore, monthlyScore, sleepHours, screenHours, taskScheduled } from "./lib/score.js";

// ---------- helpers ----------
const SLEEP_MOODS = ["😍", "😊", "😐", "🥱", "😩"];
const DAY_MOODS = ["❤️", "😄", "🙂", "😔", "😤"];
const MEALS = [
  ["breakfast", "Breakfast", "🌅"],
  ["lunch", "Lunch", "☀️"],
  ["dinner", "Dinner", "🌙"],
  ["snacks", "Snacks", "🍪"],
];
const sleepMoodIdx = (t) => Math.max(0, SLEEP_MOODS.indexOf(t));
const dayMoodIdx = (t) => Math.max(0, DAY_MOODS.indexOf(t));

function fmtTime12(t) {
  if (!t) return "";
  const [h, m] = t.split(":").map(Number);
  const ap = h >= 12 ? "PM" : "AM";
  const hh = h % 12 === 0 ? 12 : h % 12;
  return `${hh}:${String(m).padStart(2, "0")} ${ap}`;
}
function prettyDate(dk) {
  if (dk === todayKey()) return "Today";
  if (dk === shiftDateKey(todayKey(), -1)) return "Yesterday";
  const [y, m, d] = dk.split("-").map(Number);
  return new Date(y, m - 1, d).toLocaleDateString("en-IN", { day: "numeric", month: "short", day: "numeric" });
}
function dayTotal(d) {
  return (d.breakfastKcal || 0) + (d.lunchKcal || 0) + (d.dinnerKcal || 0) + (d.snacksKcal || 0);
}
const MACRO_FIELDS = [["Protein", "protein"], ["Carbs", "carbs"], ["Fat", "fat"], ["Fiber", "fiber"], ["Sugar", "sugar"]];
function dayMacros(d) {
  const t = { protein: 0, carbs: 0, fat: 0, fiber: 0, sugar: 0 };
  for (const m of ["breakfast", "lunch", "dinner", "snacks"]) {
    for (const [F, K] of MACRO_FIELDS) t[K] += Number(d[m + F]) || 0;
  }
  return t;
}
function macroTargets() {
  try { return JSON.parse(localStorage.getItem("hw_macro_targets") || "null") || { p: 60, c: 250, f: 70 }; }
  catch { return { p: 60, c: 250, f: 70 }; }
}
async function sha256(s) {
  const buf = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function statusFor(doneTasks) {
  const n = doneTasks;
  return n === 0 ? ["Get started 💪", "from-slate-600 to-slate-500"]
    : n === 1 ? ["Good start 🌱", "from-lime-600 to-emerald-500"]
    : n === 2 ? ["Half way there 🔥", "from-lime-500 to-emerald-500"]
    : n === 3 ? ["Almost there ✨", "from-emerald-500 to-teal-500"]
    : ["Perfect day! 🎉", "from-emerald-500 to-cyan-500"];
}
void statusFor;

// ---------- tiny UI atoms ----------
function Card({ className = "", children, ...p }) {
  return <div className={`hw-card p-4 ${className}`} {...p}>{children}</div>;
}
function Chip({ on, onClick, children, className = "" }) {
  return (
    <button onClick={onClick}
      className={`px-3 py-1.5 rounded-full text-sm border transition ${on ? "bg-lime-400 text-neutral-900 border-lime-400 font-semibold" : "bg-white/5 border-white/10 text-neutral-300 hover:bg-white/10"} ${className}`}>
      {children}
    </button>
  );
}
function Btn({ children, onClick, kind = "primary", className = "", disabled }) {
  const styles = {
    primary: "bg-gradient-to-r from-lime-400 to-emerald-400 text-neutral-900 font-bold",
    ghost: "bg-white/5 border border-white/10 text-neutral-200 hover:bg-white/10",
    danger: "bg-red-500/15 border border-red-500/30 text-red-300",
  };
  return (
    <button disabled={disabled} onClick={onClick}
      className={`px-4 py-2 rounded-xl text-sm transition disabled:opacity-40 ${styles[kind]} ${className}`}>
      {children}
    </button>
  );
}
function Field({ label, ...p }) {
  return (
    <label className="block">
      <span className="text-xs text-neutral-400 mb-1 block">{label}</span>
      <input {...p} className="w-full bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60" />
    </label>
  );
}

// ---------- App ----------
export default function App() {
  const [auth, setAuth] = useState(() => getSession());
  const [skipAuth, setSkipAuth] = useState(() => localStorage.getItem("hw_skip_auth") === "1");
  const [profile, setProfile] = useState(() => localStorage.getItem("hw_profile") || "");
  const [lockHash, setLockHash] = useState(() => localStorage.getItem("hw_lock_hash") || "");
  const [unlocked, setUnlocked] = useState(false);
  const [tab, setTab] = useState("today");
  const [dateKey, setDateKey] = useState(todayKey());
  const [day, setDay] = useState(null);
  const [exists, setExists] = useState(false);
  const [loadErr, setLoadErr] = useState("");
  const [syncState, setSyncState] = useState("idle"); // idle | saving | saved
  const [recent, setRecent] = useState([]);
  const [target, setTarget] = useState(() => Number(localStorage.getItem("hw_kcal_target")) || 0);
  const [installEvt, setInstallEvt] = useState(null);
  const saveTimer = useRef(null);
  const docLoadedFor = useRef("");

  // PWA install prompt + SW
  useEffect(() => {
    const h = (e) => { e.preventDefault(); setInstallEvt(e); };
    window.addEventListener("beforeinstallprompt", h);
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`).catch(() => {});
    }
    return () => window.removeEventListener("beforeinstallprompt", h);
  }, []);

  // load day for (profile, dateKey)
  useEffect(() => {
    if (!profile) return;
    let alive = true;
    setLoadErr(""); setSyncState("idle");
    const key = `${profile}|${dateKey}`;
    docLoadedFor.current = "";
    (async () => {
      try {
        const doc = await getDay(dateKey, profile);
        if (!alive) return;
        docLoadedFor.current = key;
        setDay(doc); setExists(true);
      } catch (e) {
        if (!alive) return;
        docLoadedFor.current = key;
        setDay(emptyDay(dateKey, profile)); setExists(false);
        if (!/404/i.test(String(e.message))) setLoadErr(`Data load fail — ${String(e.message).slice(0, 120)}`);
      }
    })();
    return () => { alive = false; };
  }, [profile, dateKey]); // eslint-disable-line

  const reloadRecent = useCallback(async () => {
    if (!profile) return;
    try {
      const docs = (await listRecent(profile, 40)).map((d) => ({ ...d, tasks: cleanTasks(d.tasks) }));
      setRecent(docs);
    } catch { setRecent([]); }
  }, [profile]);

  useEffect(() => { if (profile && tab === "history") reloadRecent(); }, [profile, tab, reloadRecent, dateKey]);

  // History apni full-scan fetch khud karti hai (300 din tak) — clean cleaned docs
  const fetchRecentAll = useCallback(async (name) => {
    try {
      return (await listRecent(name, 300)).map((d) => ({ ...d, tasks: cleanTasks(d.tasks) }));
    } catch { return []; }
  }, []);

  // session validate (token refresh) + couple-membership register + shared AI adopt
  // NOTE: setAuth yahan NAHI karna — getSession() har baar naya object deta hai → infinite loop → Chrome hang.
  useEffect(() => {
    if (!auth) return;
    let alive = true;
    (async () => {
      try {
        await getValidToken();
        await registerMembership(auth.email);
        if (membershipStatus() === "created" || membershipStatus() === "repaired") { location.reload(); return; }
        // partner ne AI setup share kiya ho to apne device me adopt karo (local key wins)
        const shared = await getSharedAI();
        if (alive && shared) storeShared(shared);
      } catch {}
    })();
    return () => { alive = false; };
  }, [auth?.uid]); // eslint-disable-line

  // debounced autosave
  const persist = useCallback((next) => {
    setDay(next);
    setSyncState("saving");
    clearTimeout(saveTimer.current);
    saveTimer.current = setTimeout(async () => {
      try {
        const saved = await saveDay(next);
        setExists(true);
        setSyncState("saved");
        setTimeout(() => setSyncState("idle"), 1500);
        // weight history turant reflect karo
        if (tab === "history") reloadRecent();
        void saved;
      } catch (e) {
        setSyncState("idle");
        setLoadErr(`Sync fail: ${e.message}`);
      }
    }, 500);
  }, [reloadRecent, tab]);

  if (lockHash && !unlocked) return <LockScreen hash={lockHash} onOk={() => setUnlocked(true)} />;
  if (!auth && !skipAuth) return <AuthScreen onAuth={async (email) => { setAuth(getSession()); try { await registerMembership(email); } catch {} }} onSkip={() => { localStorage.setItem("hw_skip_auth", "1"); setSkipAuth(true); }} />;
  if (!profile) return <ProfileGate onPick={(p) => { localStorage.setItem("hw_profile", p); setProfile(p); }} installEvt={installEvt} />;

  const set = (k, v) => persist({ ...day, [k]: v });
  const partner = PROFILE_PRESETS.includes(profile) ? PROFILE_PRESETS.find((p) => p !== profile) : null;
  const taskToggle = (idx) => {
    const tasks = day.tasks.map((t, i) => (i === idx ? { ...t, done: !t.done } : t));
    persist({ ...day, tasks });
  };
  const doneCount = (day?.tasks || []).filter((t) => t.done).length;

  const nav = (
    <nav className="fixed bottom-0 inset-x-0 z-40 bg-[#15151d]/95 backdrop-blur border-t border-white/10">
      <div className="max-w-lg mx-auto grid grid-cols-3">
        {[
          ["today", "Today", "☀️",],
          ["history", "History", "📅"],
          ["settings", "Settings", "⚙️"],
        ].map(([k, label, icon]) => (
          <button key={k} onClick={() => { setTab(k); if (k === "today") setDateKey(todayKey()); }}
            className={`py-3 text-xs flex flex-col items-center gap-0.5 ${tab === k ? "text-lime-400" : "text-neutral-400"}`}>
            <span className="text-lg">{icon}</span>{label}
          </button>
        ))}
      </div>
    </nav>
  );

  return (
    <div className="min-h-full pb-24">
      <div className="max-w-lg mx-auto px-4 pt-6">
        <Header profile={profile} switchProfile={() => { localStorage.removeItem("hw_profile"); setProfile(""); }}
          dateKey={dateKey} onPrev={() => setDateKey(shiftDateKey(dateKey, -1))}
          onNext={() => dateKey !== todayKey() && setDateKey(shiftDateKey(dateKey, 1))}
          onPickDate={(dk) => setDateKey(dk)}
          syncState={syncState} onHome={() => setDateKey(todayKey())} />
        {loadErr && (
          <div className="mt-3 text-sm text-red-300 bg-red-500/10 border border-red-500/20 rounded-xl px-3 py-2.5">
            {loadErr}
            {(membershipStatus() === "exists-locked" || membershipStatus() === "update-denied") && (
              <p className="mt-1.5 text-[11px] text-amber-200/90">
                💡 Couple doc purane account ka hai. Firebase Console → Firestore → couples → LOVE1 → ⋮ → Delete document → app dobara kholo — app khud naya bana lega (days history wapas aa jayegi).
              </p>
            )}
            <button onClick={() => window.location.reload()} className="block mt-2 text-xs bg-white/10 border border-white/15 rounded-lg px-3 py-1.5 hover:bg-white/15">🔄 Dobara koshish karo</button>
          </div>
        )}
        {!day ? (
          <div className="py-16 text-center text-neutral-400">Loading…</div>
        ) : tab === "today" ? (
          <TodayBoard day={day} dateKey={dateKey} set={set} taskToggle={taskToggle} doneCount={doneCount}
            target={target} aiOn={isConfigured()} persist={persist} partner={partner} profile={profile} reloadRecent={reloadRecent} recent={recent} />
        ) : tab === "history" ? (
          <HistoryBoard onOpen={(dk) => { setDateKey(dk); setTab("today"); }} target={target} profile={profile} partner={partner}
            fetchRecent={fetchRecentAll} />
        ) : (
          <SettingsBoard profile={profile} target={target} setTarget={(t) => { setTarget(t); localStorage.setItem("hw_kcal_target", String(t)); }}
            onSwitchProfile={() => { localStorage.removeItem("hw_profile"); setProfile(""); }}
            lockHash={lockHash} setLock={(h) => { setLockHash(h); if (!h) localStorage.removeItem("hw_lock_hash"); }}
            installEvt={installEvt} recent={recent}
            auth={auth} onLogout={() => { authLogout(); setAuth(null); }} />
        )}
      </div>
      {nav}
    </div>
  );
}

// ---------- Lock + Profile gate ----------
function LockScreen({ hash, onOk }) {
  const [pin, setPin] = useState("");
  const [err, setErr] = useState(false);
  const submit = async () => {
    if ((await sha256(pin)) === hash) onOk();
    else { setErr(true); setPin(""); setTimeout(() => setErr(false), 1200); }
  };
  return (
    <div className="min-h-full flex flex-col items-center justify-center px-6">
      <div className="text-5xl mb-4">🔒</div>
      <h1 className="text-lg font-semibold mb-1">HealthWish Locked</h1>
      <p className="text-xs text-neutral-400 mb-6">PIN daalo</p>
      <input value={pin} onChange={(e) => setPin(e.target.value.replace(/\D/g, "").slice(0, 6))}
        type="password" inputMode="numeric" placeholder="••••" autoFocus
        onKeyDown={(e) => e.key === "Enter" && pin && submit()}
        className={`w-40 text-center text-2xl tracking-[0.4em] bg-black/30 border rounded-2xl px-3 py-3 outline-none ${err ? "border-red-500 shake" : "border-white/10 focus:border-lime-400/60"}`} />
      {err && <p className="text-red-400 text-xs mt-3">Galat PIN</p>}
      <Btn kind="ghost" className="mt-6" onClick={submit} disabled={!pin}>Unlock</Btn>
    </div>
  );
}

function ProfileGate({ onPick, installEvt }) {
  const [custom, setCustom] = useState("");
  return (
    <div className="min-h-full flex flex-col items-center justify-center px-6 text-center">
      {installEvt && (
        <button onClick={() => installEvt.prompt()}
          className="mb-8 px-5 py-2.5 rounded-full text-sm bg-gradient-to-r from-lime-400 to-emerald-400 text-neutral-900 font-bold shadow-lg shadow-lime-500/20">
          📲 Install HealthWish app
        </button>
      )}
      <div className="text-6xl mb-4">💚</div>
      <h1 className="text-3xl font-extrabold hw-gradient-text">HealthWish</h1>
      <p className="text-sm text-neutral-400 mt-2 mb-8 max-w-xs">Diet • Calories • Water • Sleep • Weight<br />Tum dono ke liye, same data.</p>
      <div className="flex gap-3 mb-6">
        {PROFILE_PRESETS.map((p) => (
          <button key={p} onClick={() => onPick(p)}
            className="px-8 py-6 rounded-2xl bg-white/5 border border-white/10 text-xl font-bold hover:border-lime-400/60 hover:bg-white/10 transition">
            {p}
          </button>
        ))}
      </div>
      <div className="flex gap-2 w-full max-w-xs">
        <input value={custom} onChange={(e) => setCustom(e.target.value)} placeholder="Naam likho…"
          className="flex-1 bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60" />
        <Btn onClick={() => custom.trim() && onPick(custom.trim())}>Go</Btn>
      </div>
    </div>
  );
}

// ---------- Auth gate ----------
function AuthScreen({ onAuth, onSkip }) {
  const [email, setEmail] = useState("");
  const [pass, setPass] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const go = async () => {
    if (!email.trim() || pass.length < 6) { setErr("Email + password (6+ chars) do"); return; }
    setBusy(true); setErr("");
    try {
      await emailAuth(email, pass.trim(), false);
      await onAuth(email.trim().toLowerCase());
    } catch (e) {
      setErr(/INVALID_LOGIN/i.test(e.message) ? "Email/password galat hai" : e.message);
    }
    setBusy(false);
  };
  return (
    <div className="min-h-full flex flex-col items-center justify-center px-6 text-center">
      <div className="text-6xl mb-4">💚</div>
      <h1 className="text-3xl font-extrabold hw-gradient-text">HealthWish</h1>
      <p className="text-sm text-neutral-400 mt-2 mb-8">Apne account se login karo</p>
      <div className="w-full max-w-xs space-y-3 text-left">
        <Field label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="tum@example.com" autoFocus />
        <Field label="Password" type="password" value={pass} onChange={(e) => setPass(e.target.value)}
          placeholder="••••••"
          onKeyDown={(e) => e.key === "Enter" && go()} />
        {err && <p className="text-xs text-red-300">{err}</p>}
        <Btn className="w-full" onClick={go} disabled={busy}>{busy ? "…" : "Login →"}</Btn>
        <button onClick={onSkip} className="w-full text-[11px] text-neutral-500 py-1">Abhi login karna nahi — manual mode me jao →</button>
      </div>
    </div>
  );
}

// ---------- Couple security card ----------
function CoupleCard({ email }) {
  const [info, setInfo] = useState(null);
  const [newEmail, setNewEmail] = useState("");
  const [msg, setMsg] = useState("");
  const load = useCallback(async () => setInfo(await getCoupleInfo()), []);
  useEffect(() => { load(); }, [load]);
  const add = async () => {
    const e = newEmail.trim().toLowerCase();
    if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(e)) { setMsg("Email sahi likho"); return; }
    const emails = [...new Set([...(info?.partnerEmails || []), e])];
    try {
      await setPartnerEmails(emails);
      setInfo({ ...info, partnerEmails: emails });
      setNewEmail(""); setMsg("Partner email added ✓");
    } catch (err) { setMsg("Fail: " + err.message); }
  };
  const others = (info?.partnerEmails || []).filter((e) => e !== email);
  return (
    <Card>
      <p className="font-semibold text-sm mb-1">🔐 Couple Access</p>
      <p className="text-[11px] text-neutral-500 mb-3">Sirf ye emails login karke data dekh sakte hain. Partner ko add karo, phir wo apne phone se isi email se Sign Up karega.</p>
      <div className="space-y-1 mb-2">
        <div className="text-xs px-3 py-2 rounded-lg bg-lime-400/10 border border-lime-400/20 flex items-center gap-2">
          <span>👤</span><span className="flex-1">{email}</span><span className="text-lime-300 text-[10px]">tum</span>
        </div>
        {others.map((e) => (
          <div key={e} className="text-xs px-3 py-2 rounded-lg bg-white/5 border border-white/10 flex items-center gap-2">
            <span>💞</span><span className="flex-1">{e}</span><span className="text-neutral-500 text-[10px]">partner</span>
          </div>
        ))}
      </div>
      <div className="flex gap-2">
        <input value={newEmail} onChange={(e) => setNewEmail(e.target.value)} placeholder="partner@example.com"
          onKeyDown={(e) => e.key === "Enter" && add()}
          className="flex-1 bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60" />
        <Btn onClick={add}>Add</Btn>
      </div>
      {msg && <p className="text-xs text-lime-300 mt-2">{msg}</p>}
    </Card>
  );
}

// ---------- Header ----------
function Header({ profile, switchProfile, dateKey, onPrev, onNext, syncState, onHome, onPickDate }) {
  const dateRef = useRef(null);
  const openPicker = () => {
    if (!dateRef.current) return;
    try { dateRef.current.showPicker && dateRef.current.showPicker(); }
    catch { dateRef.current.click && dateRef.current.click(); }
  };
  return (
    <div className="flex items-center justify-between mb-4">
      <button onClick={switchProfile} title="Profile switch"
        className="flex items-center gap-2.5 bg-white/5 border border-white/10 pl-1.5 pr-4 py-1.5 rounded-full hover:bg-white/10 transition">
        <span className="w-8 h-8 rounded-full bg-gradient-to-br from-lime-400 to-emerald-500 flex items-center justify-center text-neutral-900 font-bold text-sm">
          {profile.slice(0, 1).toUpperCase()}
        </span>
        <span className="font-semibold">{profile}</span>
        <span className="text-neutral-500 text-xs">⇅</span>
      </button>
      <div className="flex items-center gap-1">
        <button onClick={onPrev} className="w-10 h-10 rounded-full bg-white/5 border border-white/10 hover:bg-white/10 active:scale-90 transition">←</button>
        <button onClick={openPicker} title="Date picker"
          className="px-4 py-2.5 text-sm font-extrabold min-w-28 rounded-2xl bg-white/5 border border-white/10 hover:bg-white/10 active:scale-95 transition">
          {prettyDate(dateKey)}
          <input ref={dateRef} type="date" value={dateKey} max={todayKey()} tabIndex={-1}
            onChange={(e) => e.target.value && onPickDate && onPickDate(e.target.value)}
            className="absolute opacity-0 pointer-events-none w-0 h-0" aria-hidden="true" />
        </button>
        <button onClick={onNext} disabled={dateKey === todayKey()}
          className="w-10 h-10 rounded-full bg-white/5 border border-white/10 disabled:opacity-25 hover:bg-white/10 active:scale-90 transition">→</button>
      </div>
      <span className={`text-xs ${syncState === "saving" ? "text-amber-300" : syncState === "saved" ? "text-lime-400" : "text-neutral-600"}`}>
        {syncState === "saving" ? "☁ Syncing…" : syncState === "saved" ? "✓ Synced" : "☁"}
      </span>
    </div>
  );
}

// ---------- Today ----------
function TodayBoard({ day, dateKey, set, taskToggle, doneCount, target, aiOn, persist, partner, profile }) {
  const [mealEdit, setMealEdit] = useState(null); // "breakfast" ...
  const [aiOpen, setAiOpen] = useState(false);
  const total = dayTotal(day);
  const pct = target ? Math.min(140, Math.round((total / target) * 100)) : 0;
  const mTot = dayMacros(day);
  const mt = macroTargets();
  const wakeMissing = !day.wakeupTime;

  return (
    <div className="space-y-4 hw-fade-in">
      {partner && <PartnerMini partner={partner} dateKey={todayKey()} target={target} />}

      {/* Score top pe */}
      <ScoreCard day={day} dateKey={dateKey} target={target} profile={profile} aiOn={aiOn} partnerName={partner} />

      {/* Sleep — score ke turant niche */}
      <SleepCard day={day} dateKey={dateKey} set={set} persist={persist} profile={profile} />

      {/* Habits */}
      <HabitsCard day={day} taskToggle={taskToggle} persist={persist} />

      {/* Calories */}
      <Card>
        <button onClick={() => setAiOpen(!aiOpen)} className="w-full">
          <div className="flex items-center justify-between mb-1">
            <p className="font-semibold">🔥 Calories</p>
            {aiOn
              ? <span className="text-[11px] px-2 py-0.5 rounded-full bg-lime-400/10 border border-lime-400/30 text-lime-300">AI on</span>
              : <span className="text-[11px] px-2 py-0.5 rounded-full bg-amber-400/10 border border-amber-400/30 text-amber-300">AI off — manual</span>}
          </div>
        </button>
        <div className="flex items-end justify-between text-sm text-neutral-400 mb-2">
          <div><span className="text-3xl font-extrabold text-white">{total}</span><span className="text-sm"> kcal</span>{target > 0 && <span className="text-sm"> / {target}</span>}</div>
          {target > 0 && <span className={`text-xs font-semibold ${pct > 110 ? "text-red-300" : pct > 85 ? "text-amber-300" : "text-lime-300"}`}>{pct}%</span>}
        </div>
        {target > 0 && (
          <div className="h-2.5 rounded-full bg-white/10 overflow-hidden mb-4">
            <div className={`h-full rounded-full transition-all ${pct > 110 ? "bg-red-400" : pct > 85 ? "bg-amber-400" : "bg-gradient-to-r from-lime-400 to-emerald-400"}`} style={{ width: `${Math.min(100, pct)}%` }} />
          </div>
        )}
        {/* Macros (g) — protein/carbs/fat daily bars */}
        <div className="pt-3 border-t border-white/10 space-y-2.5 mb-4">
          <div className="flex items-center justify-between text-[11px] text-neutral-400">
            <span className="font-semibold text-neutral-300">Macros (g)</span>
            <span className="tabular-nums">🥩 {mTot.protein} · 🍞 {mTot.carbs} · 🧈 {mTot.fat} · 🌾 {mTot.fiber}g</span>
          </div>
          {[["🥩 Protein", mTot.protein, mt.p, "from-lime-400 to-emerald-400"], ["🍞 Carbs", mTot.carbs, mt.c, "from-amber-400 to-yellow-400"], ["🧈 Fat", mTot.fat, mt.f, "from-orange-400 to-red-400"]].map(([label, val, tgt, grad]) => (
            <div key={label} className="flex items-center gap-2.5">
              <span className="text-[11px] text-neutral-400 w-18 shrink-0">{label}</span>
              <div className="flex-1 h-2 rounded-full bg-white/10 overflow-hidden">
                <div className={`h-full rounded-full hw-barfill bg-gradient-to-r ${grad}`} style={{ width: `${Math.min(100, tgt ? Math.round((val / tgt) * 100) : val)}%` }} />
              </div>
              <span className="text-[10px] text-neutral-500 w-16 text-right tabular-nums">{val}<span className="text-neutral-600">/{tgt}</span></span>
            </div>
          ))}
        </div>
        {aiOpen && <AiEntryBox day={day} dateKey={dateKey} persist={persist} setAiOpen={setAiOpen} />}
        <div className="grid grid-cols-2 gap-2">
          {MEALS.map(([k, label, icon]) => (
            <button key={k} onClick={() => setMealEdit(k)}
              className={`text-left px-3.5 py-3 rounded-xl border bg-black/20 border-white/5 hover:border-lime-400/40 hover:bg-white/5 transition`}>
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium">{icon} {label}</span>
                <span className={`text-xs font-bold ${(day[k + "Kcal"] || 0) > 0 ? "text-lime-300" : "text-neutral-600"}`}>{day[k] || day[k + "Kcal"] ? `🔥 ${day[k + "Kcal"] || 0}` : "+"}</span>
              </div>
              <p className="text-xs text-neutral-400 mt-1.5 whitespace-pre-wrap break-words leading-snug">{day[k] || "kuch nahi"}</p>
              {(day[k + "Kcal"] > 0 || (day[k + "Protein"] || 0) > 0) && (
                <p className="flex gap-2 flex-wrap mt-1.5 text-[10px] text-neutral-500 tabular-nums">
                  {day[k + "Kcal"] > 0 && <span className="text-lime-300/90 font-semibold">🔥 {day[k + "Kcal"]}</span>}
                  {(day[k + "Protein"] || 0) > 0 && <span>🥩 {day[k + "Protein"]}g</span>}
                  {(day[k + "Carbs"] || 0) > 0 && <span>🍞 {day[k + "Carbs"]}g</span>}
                  {(day[k + "Fat"] || 0) > 0 && <span>🧈 {day[k + "Fat"]}g</span>}
                  {(day[k + "Fiber"] || 0) > 0 && <span>🌾 {day[k + "Fiber"]}g</span>}
                </p>
              )}
            </button>
          ))}
        </div>
      </Card>

      {/* Water */}
      <WaterCard water={day.waterGlasses} onChange={(v) => set("waterGlasses", Math.max(0, Math.min(20, v)))} />

      {/* Screen time */}
      <Card>
        <p className="font-semibold mb-2">📱 Mobile screen-on time</p>
        <div className="flex items-end gap-2">
          <input type="number" step="0.5" min="0" max="24" inputMode="decimal"
            defaultValue={screenHours(day.screenOnTime) ? String(screenHours(day.screenOnTime)) : ""}
            onBlur={(e) => {
              const h = Number(e.target.value);
              if (!h || h <= 0) { set("screenOnTime", ""); return; }
              const hh = Math.floor(h); const mm = Math.round((h - hh) * 60);
              set("screenOnTime", mm ? `${hh}h ${mm}m` : `${hh}h`);
            }}
            placeholder="hours (e.g. 5.5)"
            className="flex-1 bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60" />
          <span className="text-xs text-neutral-500 self-center pb-3">score me count hota hai (≤6h best)</span>
        </div>
      </Card>

      {/* Weight */}
      <Card>
        <p className="font-semibold mb-2">⚖️ Weight</p>
        {day.weightKg > 0 && <p className="text-xs text-neutral-400 mb-2">Aaj: <b className="text-white">{day.weightKg} kg</b></p>}
        <div className="flex gap-2">
          <input type="number" step="0.1" inputMode="decimal" placeholder="kg" defaultValue={day.weightKg > 0 ? day.weightKg : ""}
            onBlur={(e) => { const v = Math.round(Number(e.target.value) * 10) / 10; set("weightKg", v || 0); }}
            className="flex-1 bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60" />
          <span className="text-xs text-neutral-500 self-center">15 din me ek baar bhi chalega 🙂</span>
        </div>
      </Card>

      {/* Note */}
      <Card>
        <p className="font-semibold mb-2">📝 Note</p>
        <textarea value={day.dayNote} onChange={(e) => set("dayNote", e.target.value.slice(0, 300))}
          rows={2} placeholder="Din ke baare me kuch…"
          className="w-full bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60 resize-none" />
      </Card>

      {mealEdit && <MealSheet meal={mealEdit} day={day} persist={persist} onClose={() => setMealEdit(null)} aiOn={aiOn} />}

      {/* NoFap — sabse last, sirf Shumu */}
      {profile === "Shumu" && <NoFapCard key={profile} dateKey={dateKey} profile={profile} />}
    </div>
  );
}

// ---------- Sleep card (self-contained, score ke turant niche) ----------
function SleepCard({ day, dateKey, set, persist, profile }) {
  const prevKey = shiftDateKey(dateKey, -1);
  const [prevSleep, setPrevSleep] = useState("");
  useEffect(() => {
    let alive = true;
    getDay(prevKey, profile).then((d) => alive && setPrevSleep(d.sleepTime || "")).catch(() => alive && setPrevSleep(""));
    return () => { alive = false; };
  }, [dateKey, profile]);
  // "Slept" = KAL raat ka time → KAL ke doc me save (Android jaisa), tabhi per-day sleep banta hai
  const setSleptOfPrev = async (v) => {
    setPrevSleep(v);
    try {
      const prev = await getDay(prevKey, profile);
      persist({ ...prev, sleepTime: v });
    } catch {
      const base = emptyDay(prevKey, profile);
      persist({ ...base, sleepTime: v });
    }
  };
  const nightHours = sleepHours({ sleepTime: prevSleep }, day);
  return (
    <Card>
      <p className="font-semibold mb-3">😴 Sleep</p>
      <div className="grid grid-cols-2 gap-3 mb-3">
        <Field label="Woke up" type="time" value={day.wakeupTime} onChange={(e) => set("wakeupTime", e.target.value)} />
        <Field label="Slept (kal raat)" type="time" value={prevSleep} onChange={(e) => setSleptOfPrev(e.target.value)} />
      </div>
      {!day.wakeupTime ? (
        <p className="text-xs text-amber-300/90">Aaj ka wake-up time dalo ⏳</p>
      ) : !prevSleep ? (
        <p className="text-xs text-neutral-500">Kal raat ka sleep time dalo ⏳</p>
      ) : (
        <p className="text-xs text-lime-300">😴 Sleep total: <b>{nightHours > 0 ? `${nightHours} hours` : `${prevSleep} → ${day.wakeupTime}`}</b> (kal wali raat · History me bhi dikhega)</p>
      )}
    </Card>
  );
}

// ---------- Habits (custom add + time/duration + edit) ----------
const TASK_EMOJIS = ["✨", "🚶", "💪", "🧘", "💧", "🏃", "🥗", "📚", "😴", "🎸", "🧠", "☀️", "💊", "💉", "🏥", "🌿"];
const QUICK_HABITS = [["💊", "Medicine"], ["💉", "Vitamins"], ["🚶", "Walk"], ["🧘", "Yoga"], ["📚", "Reading"], ["💧", "Paani"], ["😴", "Soni"], ["🧠", "No phone"]];
const WDAYS = ["S", "M", "T", "W", "T", "F", "S"];

function TaskEditSheet({ task, onSave, onClose, onDelete }) {
  const [title, setTitle] = useState(task.title || "");
  const [emoji, setEmoji] = useState(task.emoji || "");
  const [time, setTime] = useState(task.time || "");
  const [dur, setDur] = useState(String(task.durMin || ""));
  return (
    <div className="fixed inset-0 z-50 flex items-end bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="w-full max-w-lg mx-auto hw-card !rounded-b-none p-5 pb-8 space-y-3" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <p className="font-bold text-lg">✏️ Habit edit</p>
          <button onClick={onClose} className="w-9 h-9 rounded-full bg-white/5 border border-white/10">✕</button>
        </div>
        <label className="block">
          <span className="text-xs text-neutral-400 mb-1 block">Emoji</span>
          <div className="flex gap-1.5 flex-wrap">
            {TASK_EMOJIS.map((e) => (
              <button key={e} onClick={() => setEmoji(e)}
                className={`w-9 h-9 rounded-xl text-lg border transition ${emoji === e ? "bg-lime-400/20 border-lime-400/60" : "bg-black/30 border-white/10"}`}>{e}</button>
            ))}
          </div>
        </label>
        <Field label="Habit" value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. Evening walk" />
        <div className="grid grid-cols-2 gap-3">
          <Field label="Kab (time)" value={time} onChange={(e) => setTime(e.target.value)} placeholder="e.g. 7:00 PM" />
          <Field label="Kitni der (min)" type="number" inputMode="numeric" value={dur} onChange={(e) => setDur(e.target.value.replace(/\D/g, "").slice(0, 4))} placeholder="e.g. 30" />
        </div>
        <Btn className="w-full" disabled={!title.trim()} onClick={() => onSave({
          ...task,
          title: title.trim().slice(0, 40),
          emoji: (emoji.trim() || task.emoji || "✨").slice(0, 3),
          time: time.trim(),
          durMin: Math.round(Number(dur) || 0),
        })}>Save</Btn>
        <button onClick={onDelete} className="w-full text-xs text-red-300/80 hover:text-red-300 py-1">Habit delete karo</button>
      </div>
    </div>
  );
}

function habitsSort(tasks) {
  // time wale pehle ascending, phir bina time wale, custom tag baad me nahi — as-is order default me bhi chalega
  const withTime = tasks.filter((t) => t.time).sort((a, b) => (a.time > b.time ? 1 : -1));
  const rest = tasks.filter((t) => !t.time);
  return [...withTime, ...rest];
}

function HabitsCard({ day, taskToggle, persist }) {
  const [adding, setAdding] = useState(false);
  const [title, setTitle] = useState("");
  const [emoji, setEmoji] = useState("");
  const [time, setTime] = useState("");
  const [dur, setDur] = useState("");
  const [rep, setRep] = useState("daily"); // daily | temp | weekly
  const [wdays, setWdays] = useState([]);
  const [editIdx, setEditIdx] = useState(null);
  const tasks = habitsSort((day.tasks || []).filter((t) => taskScheduled(t, day.dateKey)));
  const resetAdd = () => { setTitle(""); setEmoji(""); setTime(""); setDur(""); setRep("daily"); setWdays([]); };
  const add = () => {
    const t = title.trim();
    if (!t) return;
    if (rep === "weekly" && !wdays.length) return;
    const task = {
      id: `c_${Date.now().toString(36)}`,
      title: t.slice(0, 40),
      emoji: (emoji.trim() || "✨").slice(0, 3),
      done: false,
      isDefault: false,
      time: time.trim() || "",
      durMin: Math.round(Number(dur) || 0),
      type: rep, // daily | temp | weekly
      wdays: rep === "weekly" ? [...wdays] : [],
      createdOn: rep === "temp" ? day.dateKey : "",
    };
    persist({ ...day, tasks: [...(day.tasks || []), task] });
    resetAdd(); setAdding(false);
  };
  const saveEdited = (nt) => {
    persist({ ...day, tasks: day.tasks.map((t) => (t.id === nt.id ? nt : t)) });
    setEditIdx(null);
  };
  const removeTask = (t) => {
    persist({ ...day, tasks: day.tasks.filter((x) => x.id !== t.id) });
    setEditIdx(null);
  };
  return (
    <Card>
      <div className="flex items-center justify-between mb-3">
        <p className="font-semibold">Daily Habits</p>
        <button onClick={() => setAdding(!adding)}
          className={`w-8 h-8 rounded-full border flex items-center justify-center text-lg transition ${adding ? "bg-lime-400 border-lime-400 text-neutral-900 rotate-45" : "bg-white/5 border-white/15 text-lime-300 hover:bg-white/10"}`}
          title="Custom habit add karo">＋</button>
      </div>
      {adding && (
        <div className="mb-3 p-3 rounded-xl bg-black/30 border border-white/10 space-y-2.5">
          <div>
            <span className="text-xs text-neutral-400 mb-1.5 block">Quick add (tap karo)</span>
            <div className="flex gap-1.5 flex-wrap">
              {QUICK_HABITS.map(([e, nm]) => (
                <button key={nm} onClick={() => { setEmoji(e); setTitle(nm); }}
                  className={`px-2.5 py-1.5 rounded-xl text-[11px] border transition ${emoji === e && title === nm ? "bg-lime-400/20 border-lime-400/60 text-lime-200" : "bg-black/30 border-white/10 text-neutral-300"}`}>{e} {nm}</button>
              ))}
            </div>
          </div>
          <div>
            <span className="text-xs text-neutral-400 mb-1.5 block">Emoji</span>
            <div className="flex gap-1.5 flex-wrap">
              {TASK_EMOJIS.slice(0, 12).map((e) => (
                <button key={e} onClick={() => setEmoji(e === emoji ? "" : e)}
                  className={`w-9 h-9 rounded-xl text-lg border transition ${emoji === e ? "bg-lime-400/20 border-lime-400/60" : "bg-black/30 border-white/10"}`}>{e}</button>
              ))}
            </div>
          </div>
          <Field label="Habit" value={title} onChange={(e) => setTitle(e.target.value)} autoFocus
            placeholder="e.g. 10 min reading / Medicine 8AM" onKeyDown={(e) => e.key === "Enter" && add()} />
          <div className="grid grid-cols-2 gap-3">
            <Field label="Kab (time)" value={time} onChange={(e) => setTime(e.target.value)} placeholder="e.g. 7:00 PM" />
            <Field label="Kitni der (min)" type="number" inputMode="numeric" value={dur} onChange={(e) => setDur(e.target.value.replace(/\D/g, "").slice(0, 4))} placeholder="e.g. 30" />
          </div>
          <div>
            <span className="text-xs text-neutral-400 mb-1.5 block">Repeat kaise karega?</span>
            <div className="flex gap-1.5 flex-wrap">
              <Chip on={rep === "daily"} onClick={() => setRep("daily")}>🔁 Roz</Chip>
              <Chip on={rep === "temp"} onClick={() => setRep("temp")}>✨ Sirf aaj</Chip>
              <Chip on={rep === "weekly"} onClick={() => setRep("weekly")}>📅 Weekly</Chip>
            </div>
            {rep === "weekly" && (
              <div className="flex gap-1.5 mt-2">
                {WDAYS.map((w, idx) => (
                  <button key={idx} onClick={() => setWdays(wdays.includes(idx) ? wdays.filter((x) => x !== idx) : [...wdays, idx])}
                    className={`w-9 h-9 rounded-xl text-xs font-bold border transition ${wdays.includes(idx) ? "bg-lime-400/20 border-lime-400/60 text-lime-200" : "bg-black/30 border-white/10 text-neutral-400"}`}>{w}</button>
                ))}
              </div>
            )}
          </div>
          <Btn className="w-full" onClick={add} disabled={!title.trim() || (rep === "weekly" && !wdays.length)}>Add habit ✓</Btn>
        </div>
      )}
      <div className="space-y-1.5">
        {tasks.map((t) => {
          const i = day.tasks.findIndex((x) => x.id === t.id);
          return (
            <div key={t.id} className={`flex items-stretch rounded-xl overflow-visible`}>
              <button onClick={() => i >= 0 && taskToggle(i)}
                className={`flex-1 flex items-center gap-3 px-3 py-3 rounded-xl border transition text-left hw-rowfade ${t.done ? "bg-lime-400/10 border-lime-400/40 hw-glow" : "bg-black/20 border-white/10 hover:bg-white/5"}`}>
                <span key={t.done ? "d" : "u"} className={`w-6 h-6 rounded-lg border flex items-center justify-center text-sm shrink-0 hw-pop ${t.done ? "bg-lime-400 border-lime-400 text-neutral-900" : "border-white/20"}`}>
                  {t.done ? "✓" : ""}
                </span>
                <span className="text-lg">{t.emoji}</span>
                <span className="flex-1 min-w-0">
                  <span className={`block text-sm truncate ${t.done ? "line-through text-neutral-500" : ""}`}>{t.title}</span>
                  {(t.time || t.durMin) && (
                    <span className="block text-[10px] text-neutral-500 mt-0.5">
                      {t.time && <>⏰ {t.time}</>}
                      {t.time && t.durMin ? " · " : ""}
                      {t.durMin ? `${t.durMin} min` : ""}
                      {t.type === "weekly" && Array.isArray(t.wdays) && t.wdays.length > 0 && (
                        <> · 📅 {t.wdays.map((i) => WDAYS[i]).join(" ")}</>
                      )}
                    </span>
                  )}
                </span>
                <span className="text-[10px] text-neutral-600 shrink-0">
                  {t.type === "temp" ? "✨ aaj" : t.type === "weekly" ? "📅 weekly" : t.isDefault ? "" : "🔁"}
                </span>
              </button>
              <button onClick={() => setEditIdx(t.id || String(i))} title="Edit"
                className="w-9 flex items-center justify-center text-neutral-500 hover:text-lime-300 text-sm">✎</button>
              {editIdx === (t.id || String(i)) && (
                <TaskEditSheet task={t} onSave={saveEdited} onClose={() => setEditIdx(null)}
                  onDelete={() => removeTask(t)} />
              )}
            </div>
          );
        })}
      </div>
    </Card>
  );
}

// ---------- Water card (splash animation) ----------
function WaterCard({ water, onChange }) {
  const [splash, setSplash] = useState([]);
  const bump = (dir) => {
    onChange(water + dir);
    if (dir > 0) {
      const id = Date.now();
      setSplash((s) => [...s.slice(-4), { id }]);
      setTimeout(() => setSplash((s) => s.filter((x) => x.id !== id)), 950);
    }
  };
  return (
    <div className="hw-card p-4 !bg-gradient-to-br !from-cyan-500/10 !to-blue-500/5 !border-cyan-400/20 relative overflow-hidden">
      <div className="flex items-center justify-between relative">
        <div>
          <p className="font-semibold">💧 Water</p>
          <p className="text-xs text-cyan-300/80 mt-0.5">{water} glasses (~{(water * 0.25).toFixed(2)}L){water >= 8 ? " — goal done ✓" : `/8`}</p>
        </div>
        <div className="flex items-center gap-3 relative">
          <button onClick={() => bump(-1)} disabled={water === 0}
            className="w-11 h-11 rounded-full bg-white/10 border border-white/10 text-xl font-bold active:scale-90 transition disabled:opacity-30">−</button>
          <span key={water} className="w-10 text-center text-2xl font-extrabold hw-pop inline-block">{water}</span>
          <button onClick={() => bump(1)}
            className="w-11 h-11 rounded-full bg-gradient-to-r from-lime-400 to-emerald-400 text-neutral-900 text-xl font-bold active:scale-90 transition">+</button>
        </div>
      </div>
      {splash.map((s, i) => (
        <span key={s.id} className="hw-rise absolute pointer-events-none text-xl"
          style={{ right: `${68 + i * 10}px`, bottom: "34px" }}>💧</span>
      ))}
      <div className="mt-3 h-1.5 rounded-full bg-white/10 overflow-hidden">
        <div className="h-full rounded-full bg-gradient-to-r from-cyan-400 to-blue-400 hw-barfill" style={{ width: `${Math.min(100, (water / 8) * 100)}%` }} />
      </div>
    </div>
  );
}

// ---------- Score (daily / weekly / monthly) ----------
function ScoreCard({ day, dateKey, target, profile, aiOn, partnerName }) {
  const [range, setRange] = useState("daily"); // daily | weekly | monthly
  const [docs, setDocs] = useState(null); // listRecent cache
  useEffect(() => {
    if (range === "daily") return;
    let alive = true;
    (async () => {
      if (docs) return;
      try {
        const list = (await listRecent(profile, 40)).map((d) => ({ ...d, tasks: cleanTasks(d.tasks) }));
        if (alive) setDocs(list);
      } catch { if (alive) setDocs([]); }
    })();
    return () => { alive = false; };
  }, [range, profile]); // eslint-disable-line

  const dScore = dailyScore(day, target);
  let res = { score: dScore.score, parts: dScore.parts, count: 1 };
  if (range !== "daily" && docs) {
    const r = range === "weekly" ? weeklyScore(docs, target, dateKey) : monthlyScore(docs, target, dateKey);
    res = { score: r.score, parts: [], count: r.count };
  }

  // AI Coach — IST time-slot engine: har slot pe naya message (cache per slot)
  const istRef = useRef(istNow());
  const slot = istSlot(istRef.current.hour);
  const coachKey = `hw_coach_${profile}_${dateKey}_${slot}`;
  const sendChat = async () => {
    const t = chatText.trim();
    if (!t || chatBusy) return;
    const hk = [...chatMsgs, { role: "user", content: t }].slice(-8);
    setChatMsgs(hk); setChatText(""); setChatBusy(true);
    const tt = istRef.current;
    const ctx = buildCoachContext({ day, dateKey, profile, target, docs, hours: tt.hour, min: tt.min, partnerDoc: null, partnerName, couple: false });
    ctx.healthProfile = healthLocal(profile);
    const rep = await coachChat(hk, ctx);
    setChatMsgs((m) => [...m, { role: "assistant", content: rep }]);
    setChatBusy(false);
  };
  const dmTot = dayMacros(day);
  const [coach, setCoach] = useState("");
  const [coachBusy, setCoachBusy] = useState(false);
  const [chatMsgs, setChatMsgs] = useState([]);
  const [chatText, setChatText] = useState("");
  const [chatBusy, setChatBusy] = useState(false);

  const fetchCoach = useCallback(async (force) => {
    const cached = localStorage.getItem(coachKey);
    if (!force && cached) { setCoach(cached); return; }
    setCoachBusy(true);
    let partnerDoc = null;
    if (coupleCompareOn() && partnerName) {
      try { partnerDoc = await getDay(todayKey(), partnerName); } catch {}
    }
    let docsNow = docs;
    if (!docsNow) {
      try {
        docsNow = (await listRecent(profile, 40)).map((d) => ({ ...d, tasks: cleanTasks(d.tasks) }));
        setDocs(docsNow);
      } catch { docsNow = null; }
    }
    const t = istRef.current;
    const ctx = buildCoachContext({
      day, dateKey, profile, target, docs: docsNow,
      hours: t.hour, min: t.min,
      partnerDoc, partnerName, couple: coupleCompareOn() && !!partnerName,
    });
    ctx.healthProfile = healthLocal(profile); // apna hi — partner ka medical data yahan nahi aata
    ctx.prevCoachMsg = localStorage.getItem(`hw_coach_${profile}_${dateKey}_${prevSlotOf(slot)}`) || null;
    const msg = await coachMessage(ctx, getTone(), slot);
    if (!msg.startsWith("Coach offline")) localStorage.setItem(coachKey, msg);
    setCoachBusy(false);
    setCoach(msg);
  }, [coachKey, aiOn, day, target, partnerName, profile, docs]); // eslint-disable-line

  useEffect(() => {
    setCoach(localStorage.getItem(coachKey) || "");
    if (aiOn) fetchCoach(false);
  }, [coachKey, aiOn]); // eslint-disable-line

  return (
    <Card>
      <div className="flex items-center justify-between mb-3">
        <p className="font-semibold">📊 Health Score</p>
        <div className="flex gap-1.5">
          {["daily", "weekly", "monthly"].map((r) => <Chip key={r} on={range === r} onClick={() => setRange(r)}>{r === "daily" ? "Aaj" : r === "weekly" ? "7 din" : "Mahine"}</Chip>)}
        </div>
      </div>
      <div className="flex items-center gap-4">
        <ScoreRing score={res.score} />
        <div className="flex-1 space-y-2.5">
          {res.parts.length
            ? res.parts.map(([nm, val, max]) => val == null ? null : (
              <div key={nm} className="flex items-center gap-3">
                <span className="text-xs text-neutral-300 w-16 shrink-0 font-medium">{nm}</span>
                <div className="flex-1 h-2.5 rounded-full bg-white/10 overflow-hidden">
                  <div className={`h-full rounded-full hw-barfill ${nm === "Water" ? "bg-gradient-to-r from-cyan-400 to-blue-400" : nm === "Screen" ? "bg-gradient-to-r from-amber-400 to-yellow-400" : nm === "Meals" ? "bg-gradient-to-r from-pink-400 to-fuchsia-400" : "bg-gradient-to-r from-lime-400 to-emerald-400"}`} style={{ width: `${(val / max) * 100}%` }} />
                </div>
                <span className="text-[11px] text-neutral-400 w-14 text-right tabular-nums">{val}<span className="text-neutral-600">/{max}</span></span>
              </div>
            ))
            : <p className="text-[11px] text-neutral-400">{range === "weekly" ? "Last 7 din" : "Is mahine"} ka average — {res.count} din ka data</p>}
        </div>
      </div>
      {aiOn && range === "daily" && (
        coach || coachBusy ? (
          <div className="mt-4 rounded-2xl bg-gradient-to-br from-lime-400/10 to-emerald-500/5 border border-lime-400/25 p-3.5">
            <div className="flex items-center gap-2.5 mb-1">
              <div className="w-9 h-9 rounded-full bg-gradient-to-br from-lime-400 to-emerald-500 flex items-center justify-center text-lg shadow-lg shadow-lime-500/20 shrink-0">🤖</div>
              <div className="flex-1 min-w-0">
                <p className="text-[13px] font-bold text-lime-200 leading-tight">AI Coach <span className="text-[10px] font-medium text-lime-400/70">{SLOT_ICON[slot]} {SLOT_NAME[slot]}</span></p>
                <p className="text-[10px] text-neutral-400 leading-tight">{TONE_LABEL[getTone()]} · IST</p>
              </div>
              <button onClick={() => { localStorage.removeItem(coachKey); fetchCoach(true); }} disabled={coachBusy}
                className="w-8 h-8 rounded-full bg-white/5 hover:bg-white/10 border border-white/10 flex items-center justify-center text-neutral-300 hover:text-lime-300 disabled:opacity-40 shrink-0">
                <RefreshIcon className={`w-4 h-4 ${coachBusy ? "hw-spin" : ""}`} />
              </button>
            </div>
            {coach ? (
              <>
                <p className="text-[14px] leading-relaxed text-neutral-100 whitespace-pre-wrap pl-11 pr-1">{coach}</p>
                {chatMsgs.length > 0 && (
                  <div className="mt-2.5 space-y-1.5 pl-1 max-h-48 overflow-y-auto">
                    {chatMsgs.map((m, i) => (
                      <div key={i} className={`text-[13px] px-3 py-2 rounded-xl max-w-[88%] whitespace-pre-wrap ${m.role === "user" ? "ml-auto bg-lime-400/15 border border-lime-400/25 text-lime-100" : "bg-white/5 border border-white/10 text-neutral-200"}`}>{m.content}</div>
                    ))}
                    {chatBusy && <div className="text-[13px] text-neutral-400 bg-white/5 border border-white/10 rounded-xl px-3 py-2 max-w-full w-fit flex items-center gap-2"><span className="hw-spin inline-block">🤖</span> Coach soch raha…</div>}
                  </div>
                )}
                <div className="mt-2.5 flex gap-2">
                  <input value={chatText} onChange={(e) => setChatText(e.target.value)} onKeyDown={(e) => e.key === "Enter" && sendChat()}
                    placeholder="Coach se seedha puchho…" disabled={chatBusy}
                    className="flex-1 bg-black/40 border border-white/10 rounded-xl px-3 py-2 text-[13px] outline-none focus:border-lime-400/60 min-w-0" />
                  <button onClick={sendChat} disabled={chatBusy || !chatText.trim()}
                    className="w-9 h-9 rounded-xl bg-gradient-to-br from-lime-400 to-emerald-500 text-black font-bold disabled:opacity-40 flex items-center justify-center shrink-0">➤</button>
                </div>
              </>
            ) : (
              <div className="flex items-center gap-2 text-[13px] text-neutral-400 pl-11"><span className="hw-spin inline-block">🤖</span> Din ka data dekh ke plan bana raha hai…</div>
            )}
          </div>
        ) : (
          <div className="mt-4 rounded-2xl border border-dashed border-lime-400/30 p-3.5 flex items-center gap-3 bg-lime-400/5">
            <div className="w-9 h-9 rounded-full bg-gradient-to-br from-lime-400 to-emerald-500 flex items-center justify-center text-lg shrink-0">🤖</div>
            <div className="flex-1">
              <p className="text-[13px] font-bold text-lime-200">AI Coach</p>
              <p className="text-[11px] text-neutral-400">Aaj ka din, 7-din trend, sab kuch dekh ke guide karega</p>
            </div>
            <Btn onClick={() => fetchCoach(true)}>Poochho</Btn>
          </div>
        )
      )}
      {range !== "daily" && !docs && <p className="text-xs text-neutral-500 mt-2">Score nikal raha hoon…</p>}
    </Card>
  );
}

const SLOT_ICON = { subah: "🌤", lunch: "☀️", snack: "🍪", dinner: "🌙", raat: "🌆", nay: "🌚" };
const SLOT_NAME = { subah: "Subah plan", lunch: "Lunch time", snack: "Shaam snack", dinner: "Dinner shaam", raat: "Raat wrap-up", nay: "Late night" };
const TONE_LABEL = { supportive: "🌱 Supportive", strict: "🔥 Strict", chill: "😎 Chill" };

const HP_FIELDS = [
  ["age", "🎂 Age", "25"], ["gender", "🚻 Gender", "Male / Female"], ["height", "📏 Height", "172"],
  ["blood", "🩸 Blood group", "O+"], ["diet", "🥗 Diet type", "Veg / Non-veg / Eggetarian"],
  ["goal", "🎯 Health goal", "Weight loss / Muscle gain / Maintain"], ["allergies", "⚠️ Allergies", "Kuch nahi / Peanuts etc"],
];
const HP_TEXTS = [
  ["conditions", "🏥 Bimari / Medical history", "Diabetes, BP, thyroid, asthma... kuch nahi to khali chhod do"],
  ["meds", "💊 Chal rahi medications", "Tablet ka naam + dose + kab leta hai (1 line per med)"],
];

function RefreshIcon({ className }) {
  return (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className || "w-4 h-4"}>
      <path d="M21 12a9 9 0 1 1-2.64-6.36" /><path d="M21 3v6h-6" />
    </svg>
  );
}

const SLOT_ORDER = ["subah", "lunch", "snack", "dinner", "raat", "nay"];
const prevSlotOf = (slot) => SLOT_ORDER[(SLOT_ORDER.indexOf(slot) + SLOT_ORDER.length - 1) % SLOT_ORDER.length];

// Coach ko FULL health data: meals+macros, habits detail, sleep (kal raat se), weight + 7-din ka day-wise summary
function buildCoachContext({ day, dateKey, profile, target, docs, hours, min, partnerDoc, partnerName, couple }) {
  const dm = dayMacros(day);
  const list = docs || [];
  const i = list.findIndex((x) => x.id === day.id);
  const prevDoc = i >= 0 && i + 1 < list.length ? list[i + 1] : null;
  const sleepH = prevDoc ? sleepHours(prevDoc, day) : 0;
  const week = list.slice(0, 7).map((d, idx) => {
    const dPrev = idx + 1 < list.length ? list[idx + 1] : null;
    return {
      date: d.dateKey,
      kcal: dayTotal(d),
      waterGlasses: d.waterGlasses,
      waterMl: (d.waterGlasses || 0) * 250,
      habits: `${(d.tasks || []).filter((x) => x.done).length}/${(d.tasks || []).length}`,
      protein: dayMacros(d).protein,
      sleepH: dPrev ? sleepHours(dPrev, d) : null,
      screenH: screenHours(d.screenOnTime) || null,
      weight: d.weightKg > 0 ? d.weightKg : null,
      score: dailyScore(d, target).score,
      meals: {
        breakfast: String(d.breakfast || "").slice(0, 60),
        lunch: String(d.lunch || "").slice(0, 60),
        dinner: String(d.dinner || "").slice(0, 60),
        snacks: String(d.snacks || "").slice(0, 60),
      },
      note: (d.dayNote || "").slice(0, 60),
    };
  });
  const scores = week.map((w) => w.score);
  const wcum = scores.length ? Math.round(scores.reduce((s, x) => s + x, 0) / scores.length) : 0;
  const streak = (() => { let n = 0; for (const x of scores) { if (x >= 60) n += 1; else break; } return n; })();
  const best = week.length && scores.length ? week[scores.indexOf(Math.max(...scores))] : null;
  const worst = week.length && scores.length ? week[scores.indexOf(Math.min(...scores))] : null;
  const weights = (list.filter((d) => d.weightKg > 0) || []).slice(0, 4).map((d) => ({ date: d.dateKey.slice(5), kg: d.weightKg }));
  const cap = (t) => String(t || "").slice(0, 90);
  const ctx = {
    time: `${hours}:${String(min).padStart(2, "0")}`,
    userName: profile,
    kcalTarget: target || null,
    today: {
      date: dateKey,
      kcal: dayTotal(day),
      macros: { protein: dm.protein, carbs: dm.carbs, fat: dm.fat, fiber: dm.fiber, sugar: dm.sugar },
      meals: {
        breakfast: cap(day.breakfast), lunch: cap(day.lunch), dinner: cap(day.dinner), snacks: cap(day.snacks),
      },
      waterGlasses: day.waterGlasses,
      waterMl: (day.waterGlasses || 0) * 250,
      habits: (day.tasks || []).slice(0, 12).map((t) => ({ emoji: t.emoji, title: t.title, done: t.done, time: t.time, durMin: t.durMin || 0 })),
      habitsDone: `${(day.tasks || []).filter((x) => x.done).length}/${(day.tasks || []).length}`,
      wakeup: day.wakeupTime,
      sleepHours: sleepH || null,
      sleepMood: day.sleepMood || null,
      dayMood: day.dayMood || null,
      screenHours: screenHours(day.screenOnTime) || null,
      weight: day.weightKg > 0 ? day.weightKg : null,
      note: cap(day.dayNote),
    },
    last7: week,
    last7Avg: wcum,
    streak,
    bestDay: best ? { date: best.date, score: best.score } : null,
    worstDay: worst ? { date: worst.date, score: worst.score } : null,
    recentWeights: weights,
    partner: couple && partnerDoc ? {
      name: partnerName,
      score: dailyScore(partnerDoc, target).score,
      kcal: dayTotal(partnerDoc),
      protein: dayMacros(partnerDoc).protein,
    } : null,
  };
  return ctx;
}

function ScoreRing({ score }) {
  const c = 2 * Math.PI * 26;
  const filled = (score / 100) * c;
  return (
    <svg viewBox="0 0 64 64" className="w-20 h-20 shrink-0">
      <circle cx="32" cy="32" r="26" fill="none" stroke="rgba(255,255,255,.08)" strokeWidth="7" />
      <circle cx="32" cy="32" r="26" fill="none" stroke={score >= 80 ? "#a3e635" : score >= 60 ? "#34d399" : score >= 40 ? "#fbbf24" : "#f87171"}
        strokeWidth="7" strokeLinecap="round" strokeDasharray={`${filled} ${c - filled}`} transform="rotate(-90 32 32)"
        style={{ transition: "stroke-dasharray .7s cubic-bezier(.4,0,.2,1), stroke .4s ease" }} />
      <text x="32" y="36" textAnchor="middle" className="font-bold" fill="#fff" fontSize="17">{score}</text>
    </svg>
  );
}

// ---------- Partner today mini ----------
// ---------- NoFap tracker (sirf Shumu, home ke sabse last card) ----------
function NoFapCard({ dateKey, profile }) {
  const [events, setEvents] = useState(() => { try { return JSON.parse(localStorage.getItem(`hw_nofap_${profile}`) || "[]") || []; } catch { return []; } });
  const [open, setOpen] = useState(false);
  const [ld, setLd] = useState(dateKey);
  const [lt, setLt] = useState("");
  useEffect(() => {
    const n = istNow();
    setLt(`${n.hour}:${String(n.min).padStart(2, "0")}`);
  }, [open]);
  useEffect(() => {
    let alive = true;
    getNoFap(profile).then((ev) => {
      if (!alive || !Array.isArray(ev)) return;
      if (ev.length > events.length) {
        setEvents(ev);
        localStorage.setItem(`hw_nofap_${profile}`, JSON.stringify(ev));
      }
    });
    return () => { alive = false; };
    // eslint-disable-next-line
  }, [profile]);
  const save = (evs) => {
    evs = (evs || []).slice(-50).sort((a, b) => ((a.d + (a.t || "")) < (b.d + (b.t || "")) ? -1 : 1));
    setEvents(evs);
    localStorage.setItem(`hw_nofap_${profile}`, JSON.stringify(evs));
    setNoFap(profile, evs).catch(() => {});
  };
  const logEvent = () => {
    if (!ld || !lt) return;
    setOpen(false);
    save([...events, { d: ld, t: lt, ts: Date.now() }]);
  };
  const del = (i) => save(events.filter((_, j) => j !== i));
  const dayDiff = (k1, k2) => Math.round((new Date(k2 + "T00:00:00") - new Date(k1 + "T00:00:00")) / 86400000);
  const sorted = events.slice().sort((a, b) => ((a.d + (a.t || "")) < (b.d + (b.t || "")) ? -1 : 1));
  const last = sorted[sorted.length - 1];
  const cleanDays = last ? Math.max(0, dayDiff(last.d, dateKey)) : null;
  const best = sorted.reduce((acc, e, i) => {
    if (i === 0) { acc.prev = e.d; return acc; }
    const gap = Math.max(0, dayDiff(acc.prev, e.d));
    acc.gap = Math.max(acc.gap, gap > 0 ? gap : 0);
    acc.prev = e.d;
    return acc;
  }, { prev: null, gap: 0 });
  const bestRun = last ? Math.max(best.gap, cleanDays) : 0;
  const monthCount = sorted.filter((e) => e.d.slice(0, 7) === dateKey.slice(0, 7)).length;
  return (
    <Card>
      <div className="text-center">
        <p className="text-[11px] text-neutral-500 mb-1">🔒 {profile} ka private mission</p>
        <p className="text-sm font-bold">🚫 Masturbation quit kro (NoFap Mission)</p>
        {last ? (
          <>
            <p className="text-4xl font-extrabold hw-gradient-text mt-2">{cleanDays === 0 ? "0" : cleanDays}</p>
            <p className="text-xs text-neutral-400 mt-0.5">din ho gaye {cleanDays === 0 ? "aaj kiya — ab naya count shuru 💪" : "clean hue"}</p>
          </>
        ) : (
          <p className="text-xs text-neutral-400 mt-3">Pahla record daalo — fir din count honge 🌱</p>
        )}
      </div>
      <div className="flex gap-2 mt-3">
        <div className="flex-1 bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-center">
          <p className="text-[10px] text-neutral-500">Record</p>
          <p className="text-sm font-bold">{bestRun} din 🏆</p>
        </div>
        <div className="flex-1 bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-center">
          <p className="text-[10px] text-neutral-500">Is mahine</p>
          <p className="text-sm font-bold">{monthCount} baar</p>
        </div>
        <div className="flex-1 bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-center">
          <p className="text-[10px] text-neutral-500">Total logs</p>
          <p className="text-sm font-bold">{events.length}</p>
        </div>
      </div>
      <div className="mt-3"><Btn className="w-full" onClick={() => setOpen(true)}>📝 Aaj/Parso ka log karo (date + time)</Btn></div>
      {sorted.length > 0 && (
        <div className="mt-3 space-y-1.5">
          {sorted.slice(-5).reverse().map((e, i) => {
            const ri = sorted.length - 1 - i;
            return (
              <div key={i} className="flex items-center gap-2 text-[12px] bg-white/5 border border-white/10 rounded-xl px-3 py-1.5">
                <span className="text-neutral-400">📅 {e.d} · 🕒 {e.t || "--:--"}</span>
                <button onClick={() => del(ri)} className="ml-auto text-red-300/70 hover:text-red-300 text-xs">🗑</button>
              </div>
            );
          })}
        </div>
      )}
      {open && (
        <div className="mt-3 p-3 rounded-xl bg-black/40 border border-white/10 space-y-2">
          <div className="grid grid-cols-2 gap-2">
            <label>
              <span className="text-[10px] text-neutral-400">Date</span>
              <input type="date" value={ld} onChange={(e) => setLd(e.target.value)}
                className="w-full mt-0.5 bg-black/40 border border-white/10 rounded-xl px-2.5 py-2 text-[13px] outline-none focus:border-lime-400/60" />
            </label>
            <label>
              <span className="text-[10px] text-neutral-400">Time</span>
              <input type="time" value={lt} onChange={(e) => setLt(e.target.value)}
                className="w-full mt-0.5 bg-black/40 border border-white/10 rounded-xl px-2.5 py-2 text-[13px] outline-none focus:border-lime-400/60" />
            </label>
          </div>
          <div className="flex gap-2">
            <Btn onClick={logEvent} disabled={!ld || !lt} className="flex-1">💾 Save</Btn>
            <Btn kind="ghost" onClick={() => setOpen(false)} className="flex-1">Cancel</Btn>
          </div>
        </div>
      )}
    </Card>
  );
}

function PartnerMini({ partner, dateKey, target }) {
  const [pd, setPd] = useState(null);
  useEffect(() => {
    let alive = true;
    getDay(dateKey, partner).then((d) => alive && setPd(d)).catch(() => alive && setPd("none"));
    return () => { alive = false; };
  }, [dateKey, partner]);
  if (pd === "none" || !pd) return null;
  const t = dayTotal(pd);
  const done = (pd.tasks || []).filter((x) => x.done).length;
  return (
    <button onClick={() => { localStorage.setItem("hw_profile", partner); location.reload(); }}
      className="w-full text-left px-4 py-3 rounded-2xl bg-white/5 border border-white/10 hover:border-lime-400/40 transition flex items-center gap-3">
      <span className="w-9 h-9 rounded-full bg-pink-400/20 border border-pink-400/30 flex items-center justify-center text-base">💞</span>
      <div className="flex-1 min-w-0">
        <p className="text-sm font-semibold">{partner} ka aaj</p>
        <p className="text-[11px] text-neutral-400 mt-0.5 flex gap-2 flex-wrap">
          <span>🔥 {t}{target ? `/${target}` : ""} kcal</span>
          <span>💧 {pd.waterGlasses}</span>
          <span>✅ {done}/{(pd.tasks || []).length}</span>
          {pd.wakeupTime && <span>⏰ {fmtTime12(pd.wakeupTime)}</span>}
          {pd.weightKg > 0 && <span>⚖️ {pd.weightKg}kg</span>}
        </p>
      </div>
      <span className="text-neutral-600 text-xs">dekho ›</span>
    </button>
  );
}

function MealSheet({ meal, day, persist, onClose, aiOn }) {
  const [, label, icon] = MEALS.find(([k]) => k === meal);
  const [text, setText] = useState(day[meal]);
  const [kcal, setKcal] = useState(day[meal + "Kcal"] || "");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [preview, setPreview] = useState(null); // {protein,...} estimate preview
  const save = async () => {
    setBusy(true); setErr("");
    let nut = preview;
    // kcal khali ya macros missing + AI on → save se pehle full macro count
    if (aiOn && (text || "").trim() && (!Number(kcal) || !nut)) {
      const res = await estimateMealNutrition(text, () => {});
      if (res && !res.error) nut = res;
      else if (!Number(kcal)) {
        setBusy(false);
        setErr("AI count nahi kar paya — kcal manually daalo ya phir Save dabao");
        return;
      }
    }
    const patch = {
      [meal]: text.trim(),
      [meal + "Kcal"]: Math.max(0, Number(kcal) || (nut && nut.kcal) || 0),
    };
    for (const [F, K] of MACRO_FIELDS) patch[meal + F] = nut ? (nut[K] || 0) : (day[meal + F] || 0);
    persist({ ...day, ...patch });
    setBusy(false);
    onClose();
  };
  const aiEstimate = async () => {
    if (!(text || "").trim()) { setErr("Pehle khaana likho 🙂"); return; }
    setBusy(true); setErr("");
    const res = await estimateMealNutrition(text, (m) => setErr(m));
    if (res && !res.error) { setKcal(res.kcal || ""); setPreview(res); }
    else { setErr((res && res.error) || "estimate fail"); }
    setBusy(false);
  };
  return (
    <div className="fixed inset-0 z-50 flex items-end bg-black/60 backdrop-blur-sm" onClick={onClose}>
      <div className="w-full max-w-lg mx-auto hw-card !rounded-b-none p-5 pb-8 space-y-4" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between">
          <p className="font-bold text-lg">{icon} {label}</p>
          <button onClick={onClose} className="w-9 h-9 rounded-full bg-white/5 border border-white/10">✕</button>
        </div>
        <textarea value={text} onChange={(e) => setText(e.target.value)} rows={3} autoFocus
          placeholder="kya khaya? (2 roti + dal + sabzi)"
          className="w-full bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60 resize-none" />
        {preview && (
          <div className="flex items-center gap-2 flex-wrap text-[11px] px-3 py-2 rounded-xl bg-white/5 border border-white/10 text-neutral-300">
            <span className="font-bold text-lime-300">AI estimate:</span>
            <span>🔥 {preview.kcal}</span>
            <span>🥩 {preview.protein}g</span>
            <span>🍞 {preview.carbs}g</span>
            <span>🧈 {preview.fat}g</span>
            <span>🌾 {preview.fiber}g</span>
            <span>🍬 {preview.sugar}g</span>
          </div>
        )}
        {err && <p className="text-xs text-red-300">{err}</p>}
        <div className="flex items-end gap-2">
          <Field label={`Calories (kcal)${aiOn ? " — khali chhodo to AI khud count karega" : ""}`} type="number" inputMode="numeric" value={kcal} onChange={(e) => setKcal(e.target.value.replace(/\D/g, "").slice(0, 5))} placeholder="0" />
          <Btn className="mb-0.5 flex-1" onClick={save} disabled={busy}>{busy ? "🤖 counting…" : "Save"}</Btn>
        </div>
      </div>
    </div>
  );
}

function AiEntryBox({ day, persist, setAiOpen }) {
  const [text, setText] = useState("");
  const [meal, setMeal] = useState("breakfast");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const [preview, setPreview] = useState(null);
  const guess = async () => {
    if (!text.trim()) { setErr("khaana likho 🙂"); return; }
    setBusy(true); setErr("");
    try {
      setPreview(await parseFoodInput(text));
    } catch (e) { setErr(e.message); }
    setBusy(false);
  };
  const save = () => {
    const nextKcal = day[meal + "Kcal"] + preview.total;
    const nextText = day[meal] ? `${day[meal]} + ${text.trim()}` : text.trim();
    const patch = { [meal]: nextText, [meal + "Kcal"]: nextKcal };
    if (preview.macros) {
      for (const [F, K] of MACRO_FIELDS) patch[meal + F] = (day[meal + F] || 0) + (preview.macros[K] || 0);
    }
    persist({ ...day, ...patch });
    setText(""); setPreview(null); setAiOpen(false);
  };
  return (
    <div className="mb-3 p-3 rounded-xl bg-black/30 border border-white/10 space-y-2.5">
      <div className="flex gap-1.5 flex-wrap">
        {MEALS.map(([k, l, ic]) => (
          <Chip key={k} on={meal === k} onClick={() => setMeal(k)}>{ic} {l}</Chip>
        ))}
      </div>
      <div className="flex gap-2">
        <input value={text} onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && guess()}
          placeholder="e.g. 2 roti, dal, chawal" className="flex-1 bg-black/40 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60" />
        <Btn onClick={guess} disabled={busy}>{busy ? "…" : "AI"}</Btn>
      </div>
      {err && <p className="text-xs text-red-300">{err}</p>}
      {preview && (
        <div className="space-y-2">
          <div className="text-xs text-neutral-300 space-y-1">
            {preview.items.map((it, i) => (
              <div key={i} className="flex justify-between"><span>• {it.label}</span><span className="text-lime-300 font-semibold">{it.kcal}</span></div>
            ))}
            <div className="flex justify-between font-bold text-white pt-1 border-t border-white/10"><span>Total</span><span className="text-lime-400">{preview.total} kcal</span></div>
            {preview.macros && (preview.macros.protein || preview.macros.carbs || preview.macros.fat) > 0 && (
              <div className="flex gap-2 flex-wrap pt-1 text-[10px] text-neutral-400 tabular-nums">
                <span>🥩 {preview.macros.protein}g</span>
                <span>🍞 {preview.macros.carbs}g</span>
                <span>🧈 {preview.macros.fat}g</span>
                {(preview.macros.fiber || 0) > 0 && <span>🌾 {preview.macros.fiber}g</span>}
                {(preview.macros.sugar || 0) > 0 && <span>🍬 {preview.macros.sugar}g</span>}
              </div>
            )}
          </div>
          <div className="flex gap-2">
            <Btn className="flex-1" onClick={save}>Save ✓</Btn>
            <Btn kind="ghost" onClick={() => setPreview(null)}>Discard</Btn>
          </div>
        </div>
      )}
    </div>
  );
}

// ---------- History ----------
function HistoryBoard({ onOpen, target, profile, partner, fetchRecent }) {
  const [view, setView] = useState("me"); // me | partner
  const [range, setRange] = useState("7"); // 7 | 30 | all
  const [docs, setDocs] = useState(null); // full list for current who
  const who = view === "partner" ? partner : profile;

  useEffect(() => {
    let alive = true;
    setDocs(null);
    fetchRecent(who).then((d) => alive && setDocs(d));
    return () => { alive = false; };
  }, [who, fetchRecent, onOpen]);

  const todayK = todayKey();
  const filtered = useMemo(() => {
    if (!docs) return [];
    if (range === "all") return docs;
    const days = range === "7" ? 7 : 30;
    const from = shiftDateKey(todayK, -(days - 1));
    return docs.filter((d) => d.dateKey >= from && d.dateKey <= todayK);
  }, [docs, range, todayK]);

  const avg = filtered.length ? Math.round(filtered.reduce((s, d) => s + dailyScore(d, target).score, 0) / filtered.length) : 0;

  return (
    <div className="space-y-4 hw-fade-in">
      <div className="flex items-center justify-between flex-wrap gap-2">
        <div className="flex gap-1.5">
          <Chip on={view === "me"} onClick={() => setView("me")}>👤 {profile}</Chip>
          {partner && <Chip on={view === "partner"} onClick={() => setView("partner")}>💞 {partner}</Chip>}
        </div>
        <div className="flex gap-1.5">
          {[["7", "7 din"], ["30", "Mahine"], ["all", "Sab"]].map(([r, label]) => <Chip key={r} on={range === r} onClick={() => setRange(r)}>{label}</Chip>)}
        </div>
      </div>
      {docs === null && <div className="py-12 text-center text-neutral-400">Data nikal raha hoon…</div>}
      {docs !== null && !filtered.length && (
        <div className="py-16 text-center text-neutral-400">
          {range === "all" ? (view === "partner" ? `${partner} ka data abhi nahi hai 🌱` : "Abhi koi data nahi — Today se shuru karo 💪") : `Is range me data nahi — "Sab" tab try karo`}
        </div>
      )}
      {docs !== null && filtered.length > 0 && (
        <>
          <Card className="!bg-gradient-to-r !from-lime-500/10 !to-emerald-500/5 !border-lime-400/20">
            <div className="flex items-center gap-4">
              <ScoreRing score={avg} />
              <div>
                <p className="font-semibold text-sm">{range === "7" ? "Last 7 din" : range === "30" ? "Mahine" : "Poori history"} — {who}</p>
                <p className="text-xs text-neutral-400 mt-0.5">{filtered.length} din · avg score {avg}/100</p>
                <p className="text-[11px] text-neutral-500 mt-1">Har row pe us din ka score dikhta hai — tap karke us din ki puri detail kholo</p>
              </div>
            </div>
          </Card>
          {weightListOf(docs).length > 1 && <WeightChart points={weightListOf(docs)} />}
          <Card>
            <div className="space-y-2">
              {filtered.map((d, i) => {
                // sleep: list DESC hai — agla item (older) iska kal-raat doc hota hai
                const prevDoc = filtered[i + 1] || docs[docs.findIndex((x) => x.id === d.id) + 1];
                const sh = prevDoc ? sleepHours(prevDoc, d) : 0;
                const s = dailyScore(d, target).score;
                const sc = screenHours(d.screenOnTime);
                return (
                  <button key={d.id} onClick={() => onOpen(d.dateKey)}
                    className="w-full flex items-center gap-3 px-3 py-3 rounded-xl bg-black/20 border border-white/5 hover:border-lime-400/40 hover:bg-white/5 transition text-left hw-rowfade">
                    <div className="w-14 shrink-0">
                      <p className="text-sm font-semibold">{prettyDate(d.dateKey)}</p>
                      <p className="text-[10px] text-neutral-500">{d.dateKey.slice(5)}</p>
                    </div>
                    <div className="flex-1 flex items-center justify-between text-[11px] text-neutral-400 flex-wrap gap-x-3">
                      <span>🔥 <b className="text-white">{dayTotal(d)}</b>{target > 0 ? <span className={dayTotal(d) > target ? "text-red-300" : "text-lime-300"}>/{target}</span> : null}</span>
                      {dayMacros(d).protein > 0 && <span>🥩 <b className="text-white">{dayMacros(d).protein}g</b></span>}
                      <span>💧 <b className="text-white">{d.waterGlasses}</b></span>
                      <span>✅ <b className="text-white">{(d.tasks || []).filter((x) => x.done).length}</b>/{(d.tasks || []).length}</span>
                      {sh > 0 && <span>😴 <b className="text-white">{sh}h</b></span>}
                      {sc > 0 && <span>📱 <b className="text-white">{sc}h</b></span>}
                      {d.weightKg > 0 && <span>⚖️ <b className="text-white">{d.weightKg}</b></span>}
                    </div>
                    <span className={`text-xs font-bold px-2 py-1 rounded-full hw-pop ${s >= 70 ? "text-lime-300 bg-lime-400/10" : s >= 45 ? "text-amber-300 bg-amber-400/10" : "text-red-300 bg-red-400/10"}`}>{s}</span>
                  </button>
                );
              })}
            </div>
          </Card>
        </>
      )}
    </div>
  );
}

function weightListOf(list) {
  return (list || []).filter((d) => d.weightKg > 0).slice(0, 15).reverse();
}

function WeightChart({ points }) {
  const w = 320, h = 110, pad = 18;
  const vals = points.map((p) => p.weightKg);
  const min = Math.min(...vals) - 0.8, max = Math.max(...vals) + 0.8;
  const x = (i) => pad + (i * (w - pad * 2)) / Math.max(1, points.length - 1);
  const y = (v) => h - pad - ((v - min) / Math.max(0.1, max - min)) * (h - pad * 2);
  const line = points.map((p, i) => `${i ? "L" : "M"}${x(i)},${y(p.weightKg)}`).join(" ");
  return (
    <Card>
      <p className="font-semibold mb-1 text-sm">⚖️ Weight trend</p>
      <svg viewBox={`0 0 ${w} ${h}`} className="w-full">
        <defs>
          <linearGradient id="wg" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="#a3e635" stopOpacity="0.35" />
            <stop offset="100%" stopColor="#a3e635" stopOpacity="0" />
          </linearGradient>
        </defs>
        <path d={`${line} L${x(points.length - 1)},${h - pad} L${x(0)},${h - pad} Z`} fill="url(#wg)" />
        <path d={line} fill="none" stroke="#a3e635" strokeWidth="2.5" strokeLinecap="round" />
        {points.map((p, i) => (
          <circle key={i} cx={x(i)} cy={y(p.weightKg)} r="3" fill="#101018" stroke="#a3e635" strokeWidth="2" />
        ))}
      </svg>
      <div className="flex justify-between text-[10px] text-neutral-500 px-1">
        <span>{points[0].dateKey.slice(5)}</span>
        <span>{points[points.length - 1].dateKey.slice(5)} · {vals[vals.length - 1]} kg</span>
      </div>
    </Card>
  );
}

// ---------- Settings ----------
// ---------- Backup / Restore (hack-proof insurance) ----------
// ---------- Connection self-test (bar-bar "data nahi dikh raha" ka doctor) ----------
function SelfTestCard({ profile }) {
  const [out, setOut] = useState(null);
  const [busy, setBusy] = useState(false);
  const run = async () => {
    setBusy(true); setOut(null);
    const lines = [];
    const t0 = Date.now();
    try {
      try {
        await getDay(todayKey(), profile);
        lines.push("✅ Today doc read OK");
      } catch (e) {
        const m = String(e.message || "");
        lines.push(/404/i.test(m) ? "✅ Today read OK (naya din khali hai)" : `❌ Today read FAIL: ${m.slice(0, 120)}`);
      }
      try {
        const docs = await listRecent(profile, 40);
        lines.push(`✅ History fetch OK — ${docs.length} din mile`);
      } catch (e) {
        lines.push(`❌ History fetch FAIL: ${String(e.message).slice(0, 120)}`);
      }
      try {
        await getSharedAI();
        lines.push(`🔎 Couple doc read: ${await rawCoupleProbe()}`);
        try {
          await registerMembership(profile && getSession() ? getSession().email : "");
          if (membershipStatus()) lines.push(`🧩 Membership: ${membershipStatus()}`);
        } catch {}
      } catch (e) {
        lines.push(`❌ Couple read FAIL: ${String(e.message).slice(0, 120)}`);
      }
      try {
        const sess = getSession();
        lines.push(`👤 Login: ${sess ? `${sess.email || sess.uid} (uid ${String(sess.uid).slice(0, 6)}…)` : "NAHI — login pe jao"}`);
      } catch {}
    } catch {}
    const ss = syncStatus();
    if (ss.lastMsg && ss.lastAt > t0 - 60000) lines.push(`🔎 Last server error: HTTP ${ss.lastCode} — ${ss.lastMsg}`);
    if (!ss.failCount && ss.okAt) lines.push(`🟢 Last success: ${new Date(ss.okAt).toLocaleTimeString("en-IN")}`);
    setOut(lines);
    setBusy(false);
  };
  return (
    <Card>
      <p className="font-semibold text-sm">🩺 Server connection check</p>
      <p className="text-[11px] text-neutral-500 mt-1">Data nahi dikhe to pehle YE chalao — exact problem hi dikh jayegi.</p>
      <Btn className="mt-2 w-full" onClick={run} disabled={busy}>{busy ? "Test chal raha…" : "🔍 Check karo"}</Btn>
      {out && (
        <div className="mt-2.5 space-y-1">
          {out.map((l, i) => <p key={i} className="text-[11px] text-neutral-300 bg-black/30 border border-white/10 rounded-lg px-2.5 py-1.5 break-words">{l}</p>)}
        </div>
      )}
    </Card>
  );
}

function BackupCard({ profile }) {
  const [busy, setBusy] = useState("");
  const fileRef = useRef(null);
  const exportAll = async () => {
    setBusy("export");
    try {
      const days = await listRecent(profile, 400);
      const nofap = (() => { try { return JSON.parse(localStorage.getItem(`hw_nofap_${profile}`) || "[]"); } catch { return []; } })();
      const data = {
        version: 2,
        exportedAt: new Date().toISOString(),
        profile,
        days: days.map((d) => ({ ...d, tasks: cleanTasks(d.tasks) })),
        health: healthLocal(profile),
        nofap,
      };
      const blob = new Blob([JSON.stringify(data)], { type: "application/json" });
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = `healthwish-backup-${profile}-${todayKey()}.json`;
      a.click(); URL.revokeObjectURL(a.href);
    } catch (e) { alert("Export fail: " + e.message); }
    setBusy("");
  };
  const doRestore = async () => {
    const f = fileRef.current && fileRef.current.files && fileRef.current.files[0];
    if (!f) return;
    setBusy("restore");
    try {
      const j = JSON.parse(await f.text());
      const days = Array.isArray(j.days) ? j.days.filter((x) => x && x.dateKey) : [];
      if (!days.length) { alert("Backup me days data nahi mila"); setBusy(""); return; }
      if (!confirm(`${days.length} din ka data ${profile} ke naam se wapas write karega? (Aaj ke data pe merge hoga)`)) { setBusy(""); return; }
      let ok = 0;
      for (const d of days) {
        try { await saveDay({ ...d, ownerName: profile }); ok += 1; } catch {}
      }
      alert(`Restore done: ${ok}/${days.length} din wapas aagaye ✓`);
    } catch (e) { alert("Restore fail: " + e.message); }
    setBusy("");
    if (fileRef.current) fileRef.current.value = "";
  };
  return (
    <Card>
      <p className="font-semibold text-sm">💾 Backup & Restore</p>
      <p className="text-[11px] text-neutral-500 mt-1">Hack-proof insurance — ek saf backup file apne phone/Google Drive me rakho. Delete atak se nahi qaid, ye file se kuch bhi dubara ban sakta hai.</p>
      <div className="flex gap-2 mt-3">
        <Btn className="flex-1" onClick={exportAll} disabled={busy !== ""}>{busy === "export" ? "Ban raha…" : `⬇ Export (${profile})`}</Btn>
        <Btn kind="ghost" className="flex-1" onClick={() => fileRef.current && fileRef.current.click()} disabled={busy !== ""}>{busy === "restore" ? "Restore…" : "⬆ Restore"}</Btn>
      </div>
      <input ref={fileRef} type="file" accept="application/json,.json" className="hidden" onChange={doRestore} />
    </Card>
  );
}

function SettingsBoard({ profile, target, setTarget, onSwitchProfile, lockHash, setLock, installEvt, recent, auth, onLogout }) {
  const ai = useMemo(getAI, []);
  const [preset, setPreset] = useState(Object.keys(PRESETS).find((k) => PRESETS[k][0] === localStorage.getItem("hw_ai_base")) || "");
  const [base, setBase] = useState(ai.baseUrl);
  const [key, setKey] = useState(ai.apiKey ? "••••••••••" : "");
  const [model, setModel] = useState(ai.model || Object.values(PRESETS)[0]?.[1] || "");
  const [testing, setTesting] = useState(false);
  const [status, setStatus] = useState(isConfigured() ? "Saved setup active ✓" : "AI setup nahi — neeche key dalo 🔑");
  const [prompt, setPrompt] = useState(ai.prompt || DEFAULT_RULES);
  const [promptOpen, setPromptOpen] = useState(false);
  const [pinText, setPinText] = useState("");
  const [pinMsg, setPinMsg] = useState("");
  const [csvMsg, setCsvMsg] = useState("");
  const [shareMsg, setShareMsg] = useState("");
  const [mTargets, setMTargets] = useState(() => macroTargets());
  const [tone, setToneState] = useState(getTone());
  const [hpWho, setHpWho] = useState(profile);
  const [hp, setHp] = useState(() => healthLocal(profile));
  const [hpSaved, setHpSaved] = useState(false);
  useEffect(() => {
    if (!hpWho) return;
    const t = setTimeout(() => {
      saveHealthLocal(hpWho, hp);
      setHealthRemote(hpWho, hp).then(() => setHpSaved(true)).catch(() => {});
    }, 700);
    return () => clearTimeout(t);
  }, [hp, hpWho]);
  // profile switch pe partner ka cached data load + remote se fresh
  useEffect(() => {
    setHp(healthLocal(hpWho)); setHpSaved(false);
    getHealthRemote(hpWho).then((r) => { if (r) { setHp(r); saveHealthLocal(hpWho, r); } });
  }, [hpWho]);
  const [cmp, setCmpS] = useState(coupleCompareOn());

  const applyPreset = (name) => {
    setPreset(name);
    if (PRESETS[name]) { setBase(PRESETS[name][0]); setModel(PRESETS[name][1]); }
  };
  const saveConfig = async () => {
    const k = key.trim().startsWith("•") ? getAI().apiKey : key.trim();
    if (!/^http/.test(base.trim()) || !model.trim()) { setStatus("Base URL + Model bharo 🙂"); return; }
    if (!k && preset !== "Custom") { setStatus("API Key bhi dalo 🙂"); return; }
    saveAI({ baseUrl: base, apiKey: k || "no-key", model, prompt });
    setStatus("Saved — Test 🔌 dabao");
    setKey("••••••••••");
  };
  const runTest = async () => {
    setTesting(true); setStatus("Testing…");
    try { setStatus(`AI connected ✓ (${await testAI().catch((e) => "FAIL: " + e.message)})`); }
    catch (e) { setStatus("Failed: " + e.message); }
    setTesting(false);
  };
  const setPin = async () => {
    if (pinText.length < 4) { setPinMsg("4+ digit PIN"); return; }
    localStorage.setItem("hw_lock_hash", await sha256(pinText));
    setLock(await sha256(pinText)); setPinText(""); setPinMsg("Lock on ✓");
  };
  const exportCsv = () => {
    if (!recent.length) { setCsvMsg("Pehle kuch log karo"); return; }
    const cols = ["date", "breakfast", "breakfast_kcal", "lunch", "lunch_kcal", "dinner", "dinner_kcal", "snacks", "snacks_kcal", "total_kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g", "water_glasses", "wakeup", "sleep", "weight_kg", "note"];
    const q = (v) => `"${String(v ?? "").replace(/"/g, '""')}"`;
    const rows = recent.map((d) => {
      const dm = dayMacros(d);
      return [d.dateKey, d.breakfast, d.breakfastKcal, d.lunch, d.lunchKcal, d.dinner, d.dinnerKcal, d.snacks, d.snacksKcal, dayTotal(d), dm.protein, dm.carbs, dm.fat, dm.fiber, dm.sugar, d.waterGlasses, d.wakeupTime, d.sleepTime, d.weightKg, d.dayNote].map(q).join(",");
    });
    const blob = new Blob([`\uFEFF${[cols.join(","), ...rows].join("\n")}`], { type: "text/csv" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `healthwish_${profile}_${todayKey()}.csv`;
    a.click();
    setCsvMsg(`Exported ${recent.length} days ✓`);
    setTimeout(() => setCsvMsg(""), 3000);
  };

  return (
    <div className="space-y-4 hw-fade-in">
      {installEvt && (
        <Card className="!border-lime-400/30">
          <div className="flex items-center justify-between">
            <div><p className="font-semibold text-sm">📲 Install karo</p><p className="text-xs text-neutral-400">Home screen pe app ke jaisa chalao</p></div>
            <Btn onClick={() => installEvt.prompt()}>Install</Btn>
          </div>
        </Card>
      )}

      {/* AI setup */}
      <Card>
        <p className="font-bold mb-1">AI Assistant ✨</p>
        <p className={`text-xs mb-3 font-semibold ${status.startsWith("AI connected") || status.startsWith("Saved") ? "text-lime-400" : "text-amber-300"}`}>{status}</p>
        {usingShared() && <p className="text-xs text-lime-300 bg-lime-400/10 border border-lime-400/20 rounded-xl px-3 py-2 mb-3">💞 Partner ka AI setup share ho raha hai — duplicate setup ki zaroorat nahi.</p>}
        <div className="flex gap-1.5 flex-wrap mb-3">
          {Object.keys(PRESETS).map((k) => <Chip key={k} on={preset === k} onClick={() => applyPreset(k)}>{k.split(" ")[0]}</Chip>)}
          <Chip on={preset === "Custom"} onClick={() => { setPreset("Custom"); setKey(""); }}>⚙️ Custom</Chip>
        </div>
        <div className="space-y-3">
          <Field label="Base URL" value={base} onChange={(e) => setBase(e.target.value)} placeholder={preset === "Custom" ? "http://192.168.1.5:1234/v1" : "https://api.groq.com/openai/v1"} />
          {preset === "Custom" && (
            <p className="text-[11px] text-neutral-400 -mt-1.5">Koi bhi OpenAI-compatible chat API chalega — LM Studio, Ollama (<code className="text-lime-300">Ollama base + /v1</code>, same device pe localhost), OpenWebUI, LiteLLM, vLLM, Together, Mistral… Endpoint: <b>baseUrl + /chat/completions</b></p>
          )}
          <Field label="API Key" value={key} onChange={(e) => setKey(e.target.value)} placeholder={preset === "Custom" ? "khali bhi chalega (local server)" : "gsk_…"} />
          <Field label="Model" value={model} onChange={(e) => setModel(e.target.value)} placeholder={preset === "Custom" ? "e.g. qwen2.5-7b-instruct" : "openai/gpt-oss-120b"} />
        </div>
        <div className="flex gap-2 mt-3">
          <Btn className="flex-1" onClick={saveConfig}>Save</Btn>
          <Btn kind="ghost" className="flex-1" onClick={runTest} disabled={testing}>{testing ? "…" : "Test 🔌"}</Btn>
        </div>
        <div className="mt-2">
          <button onClick={async () => {
            const cfg = getAI();
            if (!/^http/.test(cfg.baseUrl) || !cfg.apiKey || !cfg.model) { setShareMsg("Pehle apna AI setup Save karo 💡"); return; }
            try {
              await setSharedAI({ baseUrl: cfg.baseUrl, apiKey: cfg.apiKey, model: cfg.model, prompt: cfg.prompt });
              setShareMsg("Partner ke saath share ✓ — uske device pe apne aap chalega");
            } catch (e) { setShareMsg("Fail: " + e.message); }
          }}
            className="text-xs text-pink-300/90 hover:text-pink-300">💞 Ye setup partner ke saath share karo</button>
          {shareMsg && <p className="text-xs text-lime-300 mt-1">{shareMsg}</p>}
        </div>
        <button onClick={() => { clearAI(); setKey(""); setBase(""); setModel(""); setStatus("Removed — manual mode"); setPromptOpen(false); setPrompt(DEFAULT_RULES); }}
          className="mt-2 text-xs text-red-300/80 hover:text-red-300">Clear Saved Setup</button>
        <button onClick={() => setPromptOpen(!promptOpen)} className="mt-3 text-xs text-lime-300/90 flex items-center gap-1">
          {promptOpen ? "▾" : "▸"} AI Prompt {"✏️"} {prompt !== DEFAULT_RULES ? "(custom)" : ""}
        </button>
        {promptOpen && (
          <div className="mt-2 space-y-2">
            <textarea value={prompt} onChange={(e) => setPrompt(e.target.value)} rows={10}
              className="w-full bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-xs outline-none focus:border-lime-400/60 resize-y font-mono" />
            <div className="flex gap-2">
              <Btn className="flex-1" onClick={() => { saveAI({ prompt }); setStatus("Prompt saved ✓"); }}>Save Prompt</Btn>
              <Btn kind="ghost" onClick={() => { localStorage.removeItem("hw_ai_prompt"); setPrompt(DEFAULT_RULES); }}>Reset Default</Btn>
            </div>
          </div>
        )}
      </Card>

      {/* Health profile (per person) */}
      <Card>
        <div className="flex items-start justify-between gap-2">
          <div>
            <p className="font-semibold text-sm">🩺 Aapke baare mein</p>
            <p className="text-[11px] text-neutral-500">Age, bimari, medicines — Coach aapko dhyan se guide karega</p>
          </div>
          {hpSaved && <span className="text-[10px] text-lime-300 shrink-0 mt-1">✓ saved</span>}
        </div>
        <div className="flex gap-1.5 mt-2.5 mb-3">
          {PROFILE_PRESETS.map((p) => (
            <Chip key={p} on={hpWho === p} onClick={() => setHpWho(p)}>{p === "Tkku" ? "🧍‍♀️ Tkku" : "🧍‍♂️ Shumu"}</Chip>
          ))}
        </div>
        <div className="grid grid-cols-2 gap-2">
          {HP_FIELDS.map(([k, label, ph]) => (
            <label key={k} className="block">
              <span className="text-[10px] text-neutral-400">{label}</span>
              <input value={hp[k] || ""} onChange={(e) => setHp({ ...hp, [k]: e.target.value })}
                placeholder={ph}
                className="w-full mt-0.5 bg-black/40 border border-white/10 rounded-xl px-2.5 py-2 text-[13px] outline-none focus:border-lime-400/60" />
            </label>
          ))}
        </div>
        {HP_TEXTS.map(([k, label, ph]) => (
          <label key={k} className="block mt-2.5">
            <span className="text-[10px] text-neutral-400">{label}</span>
            <textarea value={hp[k] || ""} onChange={(e) => setHp({ ...hp, [k]: e.target.value })}
              placeholder={ph} rows={2}
              className="w-full mt-0.5 bg-black/40 border border-white/10 rounded-xl px-2.5 py-2 text-[13px] outline-none focus:border-lime-400/60 resize-none" />
          </label>
        ))}
        <p className="text-[10px] text-neutral-500 mt-2.5">Khud hi ko bharo — {hpWho === "Tkku" ? "Shumu" : "Tkku"} ka data alag card me hai, dono ka data mix nahi hoga. Coach ko sirf apna dikhta hai, partner ka kabhi nahi.</p>
      </Card>

      {/* Coach settings */}
      <Card>
        <p className="font-semibold text-sm mb-1">🤖 AI Coach</p>
        <p className="text-[11px] text-neutral-500 mb-3">Coach Hinglish me baat karta hai — India time (IST) ke hisaab se subah/lunch/shaam/raat ke alag tips deta hai.</p>
        <p className="text-[10px] text-neutral-400 mb-1.5">Tone</p>
        <div className="flex gap-1.5 flex-wrap mb-3">
          {[["supportive", "🌱 Supportive"], ["strict", "🔥 Strict"], ["chill", "😎 Chill"]].map(([k, label]) => (
            <Chip key={k} on={tone === k} onClick={() => { setTone(k); setToneState(k); }}>{label}</Chip>
          ))}
        </div>
        <Chip on={cmp} onClick={() => { setCoupleCompare(!cmp); setCmpS(!cmp); }}>
          💞 Couple compare {cmp ? "ON" : "OFF"} — coach partner ke score se challenge dega
        </Chip>
      </Card>

      {/* Goal */}
      <Card>
        <p className="font-semibold text-sm mb-2">🎯 Daily calorie target</p>
        <div className="flex gap-2">
          <input type="number" value={target || ""} onChange={(e) => setTarget(Number(e.target.value.replace(/\D/g, "").slice(0, 5)))}
            placeholder="e.g. 2000" className="flex-1 bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60" />
          <Btn onClick={() => target > 0 && setStatus(`Target ${target} kcal ✓`)}>Save</Btn>
        </div>
        <p className="font-semibold text-sm mb-2 mt-4">🥩 Macro targets (g/day)</p>
        <div className="grid grid-cols-3 gap-2 mb-2">
          {[["p", "Protein"], ["c", "Carbs"], ["f", "Fat"]].map(([k, label]) => (
            <label key={k} className="block">
              <span className="text-[10px] text-neutral-400 mb-1 block">{label}</span>
              <input type="number" value={mTargets[k]} onChange={(e) => setMTargets({ ...mTargets, [k]: Number(e.target.value.replace(/\D/g, "").slice(0, 4)) })}
                className="w-full bg-black/30 border border-white/10 rounded-xl px-2.5 py-2 text-sm text-center outline-none focus:border-lime-400/60" />
            </label>
          ))}
        </div>
        <Btn kind="ghost" className="w-full" onClick={() => { localStorage.setItem("hw_macro_targets", JSON.stringify(mTargets)); setPinMsg(`Macro targets saved ✓`); }}>Save macro targets</Btn>
      </Card>

      {/* Security */}
      <Card>
        <p className="font-semibold text-sm mb-2">🔒 Lock {lockHash ? "— ON ✓" : "— off"}</p>
        {lockHash ? (
          <Btn kind="danger" onClick={() => { localStorage.removeItem("hw_lock_hash"); setLock(""); setPinMsg("Lock off"); }}>Turn off PIN lock</Btn>
        ) : (
          <div className="flex gap-2">
            <input value={pinText} onChange={(e) => setPinText(e.target.value.replace(/\D/g, "").slice(0, 6))} type="password" inputMode="numeric" placeholder="PIN (4+)"
              className="flex-1 bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60" />
            <Btn onClick={setPin}>Set</Btn>
          </div>
        )}
        {pinMsg && <p className="text-xs text-lime-300 mt-2">{pinMsg}</p>}
      </Card>

      {/* Data */}
      <Card>
        <p className="font-semibold text-sm mb-2">💾 Data</p>
        <Btn kind="ghost" className="w-full" onClick={exportCsv}>⬇ Export CSV ({recent.length} days)</Btn>
        {csvMsg && <p className="text-xs text-lime-300 mt-2">{csvMsg}</p>}
        <p className="text-[11px] text-neutral-500 mt-3">Data Firestore (healthwish-6ca22) me sync hota hai — Android app wahi DB use karti hai, dono me same dikhega.</p>
      </Card>

      {/* Self-test */}
      <SelfTestCard profile={profile} />

      {/* Backup */}
      <BackupCard profile={profile} />

      {/* Account */}
      <Card>
        <p className="font-semibold text-sm mb-2">👤 Account</p>
        {auth && <p className="text-xs text-neutral-400 mb-2">{auth.email}</p>}
        <Btn kind="danger" onClick={onLogout}>Logout</Btn>
        <p className="text-[11px] text-neutral-500 mt-2">Logout ke baad data device se gaya nahi — sirf account band hota hai.</p>
      </Card>

      {/* Couple security */}
      {auth && <CoupleCard email={auth.email} />}

      <Btn kind="ghost" className="w-full" onClick={onSwitchProfile}>⇅ Switch Profile ({profile})</Btn>
      <p className="text-center text-[11px] text-neutral-600 pb-4">HealthWish Web 💚 · couple health diary</p>
    </div>
  );
}
