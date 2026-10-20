import React, { useEffect, useMemo, useRef, useState, useCallback } from "react";
import {
  todayKey, shiftDateKey, docIdFor, emptyDay, getDay, saveDay, listRecent, defaultTasks,
  PROFILE_PRESETS, cleanTasks, registerMembership,
} from "./lib/firestoreRest.js";
import {
  PRESETS, DEFAULT_RULES, getAI, saveAI, clearAI, isConfigured, testAI, parseFoodInput, estimateKcal,
} from "./lib/ai.js";
import { getSession, emailAuth, logout as authLogout, getValidToken } from "./lib/firebaseAuth.js";

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
      } catch {
        if (!alive) return;
        docLoadedFor.current = key;
        setDay(emptyDay(dateKey, profile)); setExists(false);
        if (!loadErr) setLoadErr("");
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

  // session validate (token refresh) + couple-membership register
  useEffect(() => {
    if (!auth) return;
    let alive = true;
    (async () => {
      try {
        await getValidToken();
        if (alive) setAuth(getSession());
        await registerMembership(auth.email);
      } catch {
        if (alive) setAuth(getSession()); // logout() ke baad null ho sakta hai
      }
    })();
    return () => { alive = false; };
  }, [auth]); // eslint-disable-line

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
  if (!auth) return <AuthScreen onAuth={async (email) => { setAuth(getSession()); try { await registerMembership(email); } catch {} }} />;
  if (!profile) return <ProfileGate onPick={(p) => { localStorage.setItem("hw_profile", p); setProfile(p); }} installEvt={installEvt} />;

  const set = (k, v) => persist({ ...day, [k]: v });
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
          syncState={syncState} onHome={() => setDateKey(todayKey())} />
        {loadErr && <div className="mt-3 text-sm text-red-300 bg-red-500/10 border border-red-500/20 rounded-xl px-3 py-2">{loadErr}</div>}
        {!day ? (
          <div className="py-16 text-center text-neutral-400">Loading…</div>
        ) : tab === "today" ? (
          <TodayBoard day={day} dateKey={dateKey} set={set} taskToggle={taskToggle} doneCount={doneCount}
            target={target} aiOn={isConfigured()} persist={persist} />
        ) : tab === "history" ? (
          <HistoryBoard recent={recent} onOpen={(dk) => { setDateKey(dk); setTab("today"); }} target={target} profile={profile} />
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
function AuthScreen({ onAuth }) {
  const [mode, setMode] = useState("login"); // login | signup
  const [email, setEmail] = useState("");
  const [pass, setPass] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const go = async () => {
    if (!email.trim() || pass.length < 6) { setErr(mode === "signup" ? "Password 6+ chars rakho" : "Email + password do"); return; }
    setBusy(true); setErr("");
    try {
      await emailAuth(email, pass.trim(), mode === "signup");
      await onAuth(email.trim().toLowerCase());
    } catch (e) {
      setErr(mode === "signup" && /EMAIL_EXISTS/i.test(e.message)
        ? "Ye email registered hai — Login karke try karo"
        : /INVALID_LOGIN/i.test(e.message) ? "Email/password galat hai" : e.message);
    }
    setBusy(false);
  };
  return (
    <div className="min-h-full flex flex-col items-center justify-center px-6 text-center">
      <div className="text-6xl mb-4">💚</div>
      <h1 className="text-3xl font-extrabold hw-gradient-text">HealthWish</h1>
      <p className="text-sm text-neutral-400 mt-2 mb-8">{mode === "signup" ? "Naya account banao (couple join ke liye)" : "Apne account se login karo"}</p>
      <div className="w-full max-w-xs space-y-3 text-left">
        <Field label="Email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="tum@example.com" autoFocus />
        <Field label="Password" type="password" value={pass} onChange={(e) => setPass(e.target.value)}
          placeholder={mode === "signup" ? "6+ characters" : "••••••"}
          onKeyDown={(e) => e.key === "Enter" && go()} />
        {err && <p className="text-xs text-red-300">{err}</p>}
        <Btn className="w-full" onClick={go} disabled={busy}>{busy ? "…" : mode === "signup" ? "Sign Up ✓" : "Login →"}</Btn>
        <button onClick={() => { setMode(mode === "signup" ? "login" : "signup"); setErr(""); }}
          className="w-full text-xs text-lime-300/90 py-1">
          {mode === "signup" ? "Already a member? Login" : "Pehli baar? Sign Up banao"}
        </button>
      </div>
      {mode === "signup" && <p className="text-[11px] text-neutral-500 mt-6 max-w-xs">Couple ke dono partner apne email se signup karte hain — pehla member couple cred karta hai, doosre ka email doosre device pe whitelist hota hai.</p>}
    </div>
  );
}

// ---------- Header ----------
function Header({ profile, switchProfile, dateKey, onPrev, onNext, syncState, onHome }) {
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
        <button onClick={onPrev} className="w-9 h-9 rounded-full bg-white/5 border border-white/10 hover:bg-white/10">←</button>
        <button onClick={onHome} className="px-3 py-2 text-sm font-semibold min-w-24">{prettyDate(dateKey)}</button>
        <button onClick={onNext} disabled={dateKey === todayKey()}
          className="w-9 h-9 rounded-full bg-white/5 border border-white/10 disabled:opacity-25 hover:bg-white/10">→</button>
      </div>
      <span className={`text-xs ${syncState === "saving" ? "text-amber-300" : syncState === "saved" ? "text-lime-400" : "text-neutral-600"}`}>
        {syncState === "saving" ? "☁ Syncing…" : syncState === "saved" ? "✓ Synced" : "☁"}
      </span>
    </div>
  );
}

// ---------- Today ----------
function TodayBoard({ day, dateKey, set, taskToggle, doneCount, target, aiOn, persist }) {
  const [mealEdit, setMealEdit] = useState(null); // "breakfast" ...
  const [aiOpen, setAiOpen] = useState(false);
  const total = dayTotal(day);
  const pct = target ? Math.min(140, Math.round((total / target) * 100)) : 0;
  const [st, grad] = statusFor(doneCount);
  const wakeMissing = !day.wakeupTime;

  return (
    <div className="space-y-4 hw-fade-in">
      {/* Tasks progress */}
      <Card className={`bg-gradient-to-r ${grad} !bg-none border-0 relative overflow-hidden`}
        style={{ background: undefined }}>
        <div className="flex items-center justify-between mb-2">
          <div>
            <p className="font-bold text-lg">{st}</p>
            <p className="text-xs text-black/60 dark:text-neutral-400">{doneCount}/{day.tasks.length} habits done</p>
          </div>
          <div className="w-14 h-14 rounded-full border-4 border-white/15 flex items-center justify-center text-xl font-bold">
            {Math.round((doneCount / Math.max(1, day.tasks.length)) * 100)}%
          </div>
        </div>
      </Card>

      {/* Habits */}
      <Card>
        <p className="font-semibold mb-3">Daily Habits</p>
        <div className="space-y-1.5">
          {day.tasks.map((t, i) => (
            <button key={t.id || i} onClick={() => taskToggle(i)}
              className={`w-full flex items-center gap-3 px-3 py-3 rounded-xl border transition text-left ${t.done ? "bg-lime-400/10 border-lime-400/30" : "bg-black/20 border-white/5 hover:bg-white/5"}`}>
              <span className={`w-6 h-6 rounded-lg border flex items-center justify-center text-sm shrink-0 ${t.done ? "bg-lime-400 border-lime-400 text-neutral-900" : "border-white/20"}`}>
                {t.done ? "✓" : ""}
              </span>
              <span className="text-lg">{t.emoji}</span>
              <span className={`flex-1 text-sm ${t.done ? "line-through text-neutral-500" : ""}`}>{t.title}</span>
              {t.time && <span className="text-xs text-neutral-500">{t.time}</span>}
            </button>
          ))}
        </div>
      </Card>

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
        {aiOpen && <AiEntryBox day={day} dateKey={dateKey} persist={persist} setAiOpen={setAiOpen} />}
        <div className="grid grid-cols-2 gap-2">
          {MEALS.map(([k, label, icon]) => (
            <button key={k} onClick={() => setMealEdit(k)}
              className={`text-left px-3.5 py-3 rounded-xl border bg-black/20 border-white/5 hover:border-lime-400/40 hover:bg-white/5 transition`}>
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium">{icon} {label}</span>
                <span className={`text-xs font-bold ${(day[k + "Kcal"] || 0) > 0 ? "text-lime-300" : "text-neutral-600"}`}>{day[k] || day[k + "Kcal"] ? `🔥 ${day[k + "Kcal"] || 0}` : "+"}</span>
              </div>
              <p className="text-xs text-neutral-400 mt-1 truncate">{day[k] || "kuch nahi"}</p>
            </button>
          ))}
        </div>
      </Card>

      {/* Water */}
      <Card className="!bg-gradient-to-br !from-cyan-500/10 !to-blue-500/5 !border-cyan-400/20">
        <div className="flex items-center justify-between">
          <div>
            <p className="font-semibold">💧 Water</p>
            <p className="text-xs text-cyan-300/80 mt-0.5">{day.waterGlasses} glasses (~{day.waterGlasses * 0.25}L)</p>
          </div>
          <div className="flex items-center gap-3">
            <button onClick={() => set("waterGlasses", Math.max(0, day.waterGlasses - 1))}
              className="w-11 h-11 rounded-full bg-white/10 border border-white/10 text-xl font-bold active:scale-90 transition">−</button>
            <span className="w-10 text-center text-2xl font-extrabold">{day.waterGlasses}</span>
            <button onClick={() => set("waterGlasses", Math.min(20, day.waterGlasses + 1))}
              className="w-11 h-11 rounded-full bg-gradient-to-r from-lime-400 to-emerald-400 text-neutral-900 text-xl font-bold active:scale-90 transition">+</button>
          </div>
        </div>
      </Card>

      {/* Sleep */}
      <Card>
        <p className="font-semibold mb-3">😴 Sleep</p>
        <div className="grid grid-cols-2 gap-3 mb-3">
          <Field label="Woke up" type="time" value={day.wakeupTime} onChange={(e) => set("wakeupTime", e.target.value)} />
          <Field label="Slept" type="time" value={day.sleepTime} onChange={(e) => set("sleepTime", e.target.value)} />
        </div>
        {wakeMissing ? (
          <p className="text-xs text-amber-300/90">Aaj ka wake-up time dalo ⏳</p>
        ) : !day.sleepTime ? (
          <p className="text-xs text-neutral-500">Kal raat ka sleep time log karo (kal ke card me) ⏳</p>
        ) : (
          <p className="text-xs text-lime-300 mb-2">😴 Sleep: {day.sleepTime} → {day.wakeupTime} (kal wali raat) — total History me</p>
        )}
        <div className="flex gap-2 mb-3">
          {SLEEP_MOODS.map((m) => (
            <button key={m} onClick={() => set("sleepMood", m)}
              className={`flex-1 py-2 rounded-xl text-xl border transition ${day.sleepMood === m ? "bg-lime-400/15 border-lime-400/50" : "bg-black/20 border-white/5"}`}>{m}</button>
          ))}
        </div>
        <div className="flex gap-2">
          {DAY_MOODS.map((m) => (
            <button key={m} onClick={() => set("dayMood", m)}
              className={`flex-1 py-2 rounded-xl text-xl border transition ${day.dayMood === m ? "bg-lime-400/15 border-lime-400/50" : "bg-black/20 border-white/5"}`}>{m}</button>
          ))}
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

      <p className="text-[11px] text-neutral-600 text-center">⏱ Screen time feature app (Android) me hi dikhta hai</p>

      {mealEdit && <MealSheet meal={mealEdit} day={day} persist={persist} onClose={() => setMealEdit(null)} aiOn={aiOn} />}
    </div>
  );
}

function MealSheet({ meal, day, persist, onClose, aiOn }) {
  const [, label, icon] = MEALS.find(([k]) => k === meal);
  const [text, setText] = useState(day[meal]);
  const [kcal, setKcal] = useState(day[meal + "Kcal"] || "");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState("");
  const save = () => persist({ ...day, [meal]: text.trim(), [meal + "Kcal"]: Math.max(0, Number(kcal) || 0) }) || onClose();
  const aiEstimate = async () => {
    if (!(text || "").trim()) { setErr("Pehle khaana likho 🙂"); return; }
    setBusy(true); setErr("");
    const res = await estimateKcal(text, (m) => setErr(m));
    if (res && typeof res === "object" && res.error) setErr(res.error);
    else setKcal(res);
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
        {aiOn && (
          <Btn kind="ghost" className="!bg-lime-400/10 !border-lime-400/30 !text-lime-300 w-full" onClick={aiEstimate} disabled={busy}>
            {busy ? "🤖 Soch raha hoon…" : "✨ AI se kcal nikaalo"}
          </Btn>
        )}
        {err && <p className="text-xs text-red-300">{err}</p>}
        <div className="flex items-end gap-2">
          <Field label="Calories (kcal)" type="number" inputMode="numeric" value={kcal} onChange={(e) => setKcal(e.target.value.replace(/\D/g, "").slice(0, 5))} placeholder="0" />
          <Btn className="mb-0.5 flex-1" onClick={save}>Save</Btn>
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
    persist({ ...day, [meal]: nextText, [meal + "Kcal"]: nextKcal });
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
function HistoryBoard({ recent, onOpen, target, profile }) {
  if (!recent.length) return <div className="py-16 text-center text-neutral-400">Abhi koi data nahi — Today se shuru karo 💪</div>;
  const weights = recent.filter((d) => d.weightKg > 0).slice(0, 15).reverse();
  return (
    <div className="space-y-4 hw-fade-in">
      {weights.length > 1 && <WeightChart points={weights} />}
      <Card>
        <p className="font-semibold mb-3">Last {recent.length} days — {profile}</p>
        <div className="space-y-2">
          {recent.map((d) => {
            const t = dayTotal(d);
            return (
              <button key={d.id} onClick={() => onOpen(d.dateKey)}
                className="w-full flex items-center gap-3 px-3 py-3 rounded-xl bg-black/20 border border-white/5 hover:border-lime-400/40 hover:bg-white/5 transition text-left">
                <div className="w-14 shrink-0">
                  <p className="text-sm font-semibold">{prettyDate(d.dateKey)}</p>
                  <p className="text-[10px] text-neutral-500">{d.dateKey.slice(5)}</p>
                </div>
                <div className="flex-1 grid grid-cols-3 gap-1 text-center text-[11px] text-neutral-400">
                  <div><span className="block text-sm font-bold text-white">🔥 {t}</span>kcal{target > 0 ? <span className={t > target ? "text-red-300" : "text-lime-300"}> /{target}</span> : null}</div>
                  <div><span className="block text-sm font-bold text-white">💧 {d.waterGlasses}</span>glasses</div>
                  <div><span className="block text-sm font-bold text-white">{d.weightKg > 0 ? `${d.weightKg}` : "—"}</span>kg</div>
                </div>
                <span className="text-neutral-600">›</span>
              </button>
            );
          })}
        </div>
      </Card>
    </div>
  );
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

  const applyPreset = (name) => {
    setPreset(name);
    if (PRESETS[name]) { setBase(PRESETS[name][0]); setModel(PRESETS[name][1]); }
  };
  const saveConfig = async () => {
    const k = key.trim().startsWith("•") ? getAI().apiKey : key.trim();
    if (!/^http/.test(base.trim()) || !k || !model.trim()) { setStatus("Teeno fields bharo 🙂"); return; }
    saveAI({ baseUrl: base, apiKey: k, model, prompt });
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
    const cols = ["date", "breakfast", "breakfast_kcal", "lunch", "lunch_kcal", "dinner", "dinner_kcal", "snacks", "snacks_kcal", "total_kcal", "water_glasses", "wakeup", "sleep", "weight_kg", "note"];
    const q = (v) => `"${String(v ?? "").replace(/"/g, '""')}"`;
    const rows = recent.map((d) => [d.dateKey, d.breakfast, d.breakfastKcal, d.lunch, d.lunchKcal, d.dinner, d.dinnerKcal, d.snacks, d.snacksKcal, dayTotal(d), d.waterGlasses, d.wakeupTime, d.sleepTime, d.weightKg, d.dayNote].map(q).join(","));
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
        <div className="flex gap-1.5 flex-wrap mb-3">
          {Object.keys(PRESETS).map((k) => <Chip key={k} on={preset === k} onClick={() => applyPreset(k)}>{k.split(" ")[0]}</Chip>)}
        </div>
        <div className="space-y-3">
          <Field label="Base URL" value={base} onChange={(e) => setBase(e.target.value)} placeholder="https://api.groq.com/openai/v1" />
          <Field label="API Key" value={key} onChange={(e) => setKey(e.target.value)} placeholder="gsk_…" />
          <Field label="Model" value={model} onChange={(e) => setModel(e.target.value)} placeholder="openai/gpt-oss-120b" />
        </div>
        <div className="flex gap-2 mt-3">
          <Btn className="flex-1" onClick={saveConfig}>Save</Btn>
          <Btn kind="ghost" className="flex-1" onClick={runTest} disabled={testing}>{testing ? "…" : "Test 🔌"}</Btn>
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

      {/* Goal */}
      <Card>
        <p className="font-semibold text-sm mb-2">🎯 Daily calorie target</p>
        <div className="flex gap-2">
          <input type="number" value={target || ""} onChange={(e) => setTarget(Number(e.target.value.replace(/\D/g, "").slice(0, 5)))}
            placeholder="e.g. 2000" className="flex-1 bg-black/30 border border-white/10 rounded-xl px-3 py-2.5 text-sm outline-none focus:border-lime-400/60" />
          <Btn onClick={() => target > 0 && setStatus(`Target ${target} kcal ✓`)}>Save</Btn>
        </div>
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

      {/* Account */}
      <Card>
        <p className="font-semibold text-sm mb-2">👤 Account</p>
        {auth && <p className="text-xs text-neutral-400 mb-2">{auth.email}</p>}
        <Btn kind="danger" onClick={onLogout}>Logout</Btn>
        <p className="text-[11px] text-neutral-500 mt-2">Logout ke baad data device se gaya nahi — sirf account band hota hai.</p>
      </Card>

      <Btn kind="ghost" className="w-full" onClick={onSwitchProfile}>⇅ Switch Profile ({profile})</Btn>
      <p className="text-center text-[11px] text-neutral-600 pb-4">HealthWish Web 💚 · couple health diary</p>
    </div>
  );
}
