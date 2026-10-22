// Browser-side AI client — OpenAI-compatible providers (Gemini/OpenAI/Groq/OpenRouter/DeepSeek/Custom).
// Key localStorage me rehti hai; request seedha provider ko jati hai (koi beech ka server nahi).

const PREFS = {
  base: "hw_ai_base",
  key: "hw_ai_key",
  model: "hw_ai_model",
  prompt: "hw_ai_prompt",
};

export const PRESETS = {
  "Gemini (Google AI Studio)": ["https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"],
  OpenAI: ["https://api.openai.com/v1", "gpt-4o-mini"],
  Groq: ["https://api.groq.com/openai/v1", "openai/gpt-oss-120b"],
  OpenRouter: ["https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct"],
  DeepSeek: ["https://api.deepseek.com/v1", "deepseek-chat"],
};

// Default nutrition rules (Android app jaisa — IFCT-grounded)
export const DEFAULT_RULES =
  "You are an Indian Food Calorie Calculator. Estimate calories as accurately as possible " +
  "using Indian food data, preferably ICMR-NIN Indian Food Composition Tables (IFCT 2017). " +
  "Understand Hindi, Hinglish and English food entries. Always use realistic Indian portions, " +
  "never Western serving sizes. Prefer grams/ml when provided. For roti, rice, dal, sabzi, biryani etc., " +
  "calculate from realistic cooked/raw weight. Include cooking oil, ghee, butter, sugar and gravy when relevant. " +
  "For mixed dishes break into ingredients/components, calculate separately, then total. " +
  "For '1 katori', '1 plate', '1 bowl' estimate a realistic Indian portion. " +
  "Never use random generic values when food can be calculated from ingredients/portion. " +
  "Never claim false precision — give your best single integer estimate. " +
  "Don't ask questions unless missing info changes the result a lot. Keep a balanced, non-restrictive tone. " +
  "CRITICAL OUTPUT RULE: your entire reply must be ONLY the single JSON object asked for in the system message — " +
  "no headings, no emojis, no ranges, no extra text. The app parses it automatically and maintains daily totals itself.";

const SHARED_CACHE = "hw_shared_ai";

export function storeShared(cfg) {
  try {
    localStorage.setItem(SHARED_CACHE, JSON.stringify(cfg || null));
  } catch {}
}

export function clearShared() {
  localStorage.removeItem(SHARED_CACHE);
}

function sharedCfg() {
  try {
    const c = JSON.parse(localStorage.getItem(SHARED_CACHE) || "null");
    return c && c.key && c.key.length >= 4 && c.baseUrl ? c : null;
  } catch {
    return null;
  }
}

export function getAI() {
  const sh = sharedCfg();
  return {
    baseUrl: localStorage.getItem(PREFS.base) || (sh && sh.baseUrl) || "",
    apiKey: localStorage.getItem(PREFS.key) || (sh && sh.key) || "",
    model: localStorage.getItem(PREFS.model) || (sh && sh.model) || "",
    prompt: localStorage.getItem(PREFS.prompt) || (sh && sh.prompt) || DEFAULT_RULES,
  };
}

export function usingShared() {
  return !!!localStorage.getItem(PREFS.key) && !!sharedCfg();
}

export function saveAI({ baseUrl, apiKey, model, prompt }) {
  if (baseUrl != null) localStorage.setItem(PREFS.base, baseUrl.trim().replace(/\/+$/, ""));
  if (apiKey != null) localStorage.setItem(PREFS.key, apiKey.trim());
  if (model != null) localStorage.setItem(PREFS.model, model.trim());
  if (prompt != null) localStorage.setItem(PREFS.prompt, prompt);
}

export function clearAI() {
  for (const k of [PREFS.base, PREFS.key, PREFS.model, PREFS.prompt]) localStorage.removeItem(k);
}

export function isConfigured() {
  const a = getAI();
  // custom/local providers ke liye key "no-key" bhi valid (6 chars) — isliye >= 4
  return a.baseUrl.startsWith("http") && a.apiKey.length >= 4 && !!a.model;
}

function extractJsonObject(text) {
  const m = text.match(/\{[\s\S]*\}/);
  if (m) {
    try {
      return JSON.parse(m[0]);
    } catch {}
  }
  try {
    return JSON.parse(text.trim());
  } catch {
    return null;
  }
}

async function postJson(cfg, body) {
  const res = await fetch(cfg.baseUrl.replace(/\/+$/, "") + "/chat/completions", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${cfg.apiKey}`,
      "Content-Type": "application/json",
      "User-Agent": "Mozilla/5.0",
    },
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try {
      const j = await res.json();
      msg = (j.error && (j.error.message || j.error)) || msg;
    } catch {}
    throw new Error(String(msg).slice(0, 160));
  }
  const j = await res.json();
  const choice = j.choices && j.choices[0];
  const text = (choice && choice.message && choice.message.content) || "";
  if (!text.trim()) throw new Error("Empty reply — try again");
  return text;
}

async function aiCall(cfg, system, user) {
  const body = {
    model: cfg.model,
    messages: [
      { role: "system", content: system },
      { role: "user", content: user },
    ],
    temperature: 0.2,
  };
  try {
    return await postJson(cfg, { ...body, response_format: { type: "json_object" } });
  } catch (e) {
    if (String(e.message).includes("Empty reply") || String(e.message).startsWith("HTTP 5")) {
      return await postJson(cfg, body); // json mode ke bina retry
    }
    throw e;
  }
}

// ---- public helpers ----

// Meal text -> full macro estimate {kcal, protein, carbs, fat, fiber, sugar}
export async function estimateMealNutrition(text, onErr) {
  const cfg = getAI();
  const system = cfg.prompt +
    "\n\nReply strictly as JSON: {\"kcal\": <single integer total>, \"protein\": <grams int>, " +
    "\"carbs\": <grams int>, \"fat\": <grams int>, \"fiber\": <grams int>, \"sugar\": <grams int>} — " +
    "macros for the described food only, realistic Indian portions, best single integer estimates.";
  try {
    const out = await aiCall(cfg, system, text);
    const j = extractJsonObject(out);
    if (j && typeof j.kcal !== "undefined") {
      const num = (v) => Math.max(0, Math.round(Number(v) || 0));
      return {
        kcal: num(j.kcal),
        protein: num(j.protein),
        carbs: num(j.carbs),
        fat: num(j.fat),
        fiber: num(j.fiber),
        sugar: num(j.sugar),
      };
    }
    const m = out.match(/\d{1,5}/);
    if (m) return { kcal: parseInt(m[0], 10), protein: 0, carbs: 0, fat: 0, fiber: 0, sugar: 0 };
    throw new Error("macro samajh nahi aaya");
  } catch (e) {
    onErr && onErr(e.message);
    return { error: e.message };
  }
}

// Meal text -> total kcal (single integer JSON {kcal})
export async function estimateKcal(text, onErr) {
  const cfg = getAI();
  const system = cfg.prompt + "\n\nReply strictly as JSON: {\"kcal\": <single integer total>}";
  try {
    const out = await aiCall(cfg, system, text);
    const j = extractJsonObject(out);
    if (j && typeof j.kcal !== "undefined") return Math.max(0, Math.round(Number(j.kcal) || 0));
    const m = out.match(/\d{1,5}/);
    if (m) return parseInt(m[0], 10);
    throw new Error("kcal samajh nahi aaya");
  } catch (e) {
    onErr && onErr(e.message);
    return { error: e.message };
  }
}

// Free text -> structured meal result (Android AiMealResult jaisa)
// { items:[{label,kcal}], total }
export async function parseFoodInput(input) {
  const cfg = getAI();
  const system =
    cfg.prompt +
    '\n\nReply strictly as JSON: {"items":[{"label":"food","kcal":int}],"total":int,' +
    '"protein":int,"carbs":int,"fat":int,"fiber":int,"sugar":int}. ' +
    "label in the user's language, short. total = sum of items kcal; macro top-level keys = grams for the whole meal.";
  const out = await aiCall(cfg, system, input);
  const j = extractJsonObject(out);
  if (!j || !Array.isArray(j.items)) throw new Error("AI samajh nahi paya — thoda clear likho 🙂");
  const items = j.items
    .map((it) => ({ label: String(it.label || "food").slice(0, 60), kcal: Math.round(Number(it.kcal) || 0) }))
    .filter((it) => it.kcal >= 0 && it.label);
  let total = Math.round(Number(j.total) || 0);
  if (!total) total = items.reduce((s, it) => s + it.kcal, 0);
  if (!items.length) throw new Error("koi item nahi mila");
  const num = (v) => Math.max(0, Math.round(Number(v) || 0));
  return {
    items,
    total,
    macros: {
      protein: num(j.protein), carbs: num(j.carbs), fat: num(j.fat),
      fiber: num(j.fiber), sugar: num(j.sugar),
    },
  };
}

export async function testAI() {
  const cfg = getAI();
  const out = await aiCall(
    cfg,
    'Reply strictly as JSON: {"ok":true}',
    "test"
  );
  const j = extractJsonObject(out);
  return j && j.ok ? "JSON OK" : out.slice(0, 60);
}

// ---------- AI Coach (IST time-slot engine) ----------
// IST hard-coded (UTC+5:30) — device TZ kuch bhi ho, India ka time hi chalta hai
export function istNow() {
  const now = new Date();
  const ist = new Date(now.getTime() + now.getTimezoneOffset() * 60000 + 5.5 * 3600000);
  return { hour: ist.getHours(), min: ist.getMinutes(), dateKey: ist.toDateString() };
}

export function istSlot(hour) {
  if (hour >= 23 || hour < 5) return "nay";
  if (hour < 11) return "subah";
  if (hour < 14) return "lunch";
  if (hour < 17) return "snack";
  if (hour < 21) return "dinner";
  return "raat";
}

const SLOT_HINT = {
  subah: "Subah time hai — kal ki raat ka data (sleep/late-eating) + aaj ka chhota plan do.",
  lunch: "Lunch window hai — lunch ka gap ya light-khaana pe nudge, paani reminder.",
  snack: "Shaam ka snack time — chai/mithai ke hidden kcal + fiber/fresh option pe focus.",
  dinner: "Dinner window — aaj ka protein gap, walk/steps nudge.",
  raat: "Raat — sleep-friendly advice + kal ke liye ek chhota goal.",
  nay: "Late-night — ko low-key wrap karo aur jaldi so jane ki baat karo.",
};

export function getTone() {
  return localStorage.getItem("hw_coach_tone") || "supportive";
}
export function setTone(t) {
  localStorage.setItem("hw_coach_tone", t);
}
export function coupleCompareOn() {
  return localStorage.getItem("hw_coach_couple") === "1";
}
export function setCoupleCompare(on) {
  on ? localStorage.setItem("hw_coach_couple", "1") : localStorage.removeItem("hw_coach_couple");
}

const HINGLISH_RULE =
  "You are 'Wish' — an Indian AI health coach inside a couple health app. " +
  "STRICT LANGUAGE RULE: reply ONLY in natural Hinglish — Hindi + English mix in Latin script (like friends chat on WhatsApp). " +
  "Never write full pure-English sentences (fitness words like protein, kcal, carbs are fine). " +
  "Be a suspensebrat-free warm coach: specific numbers from the context, 1-2 short lines, ends with ONE small actionable nudge. " +
  "No lectures, no generic advice — always refer to the user's actual numbers.";

export async function coachMessage(ctx, tone, slot) {
  const cfg = getAI();
  const toneLine = {
    supportive: "Tone: warm, encouraging, cheerleader vibes.",
    strict: "Tone: strict but caring drill sergeant — seedhi baat, koi bahana nahi.",
    chill: "Tone: chill homie, funny one-liners, relaxed advice.",
  }[tone] || "Tone: warm, encouraging.";
  const prev = ctx.prevCoachMsg
    ? ` Pichhle slot me tum ye bolte hi thi: "${String(ctx.prevCoachMsg).slice(0, 160)}" — uska repeat karo nahi hai, aage se keh rahe hai.`
    : "";
  const sys =
    HINGLISH_RULE + " " +
    `Right now in India it is ${ctx.time} (${slot} time). ${SLOT_HINT[slot]} ` +
    "You get FULL health data: today's meals (kcal+macros), water IN GLASSES (1 glass ~250ml, 8 glasses = 2L goal — NEVER treat glass count as litres), habit list with times, wakeup, sleep hours, " +
    "screen-on hours, weight, last-7-day day-wise summaries (with meals), recent weights, streak, best/worst day, " +
    "AND healthProfile (age, gender, height, blood group, diet type, goal, allergies, medical history, current medications). " +
    "IMPORTANT: if healthProfile has medical conditions or medications, ALWAYS tailor advice to them — " +
    "sedap: diabetes meal me sugar/sweet ho to clearly warn karo, BP/chol to high-sodium/fried food flag karo, " +
    "thyroid to meal timing, asthma to vigorous exercise caution, koi med hai to interplay mention karo. Goal aur diet type respect karo. " +
    "Use the TREND (last7) when relevant — patterns jaise '3 din se protein 45g pe atka hai' chamakte hain to bolo. " +
    toneLine +
    ' Reply strictly as JSON: {"msg":"..."} — msg 1-3 short lines, max 1 emoji.' +
    prev;
  const user = JSON.stringify(ctx);
  try {
    const out = await aiCall(cfg, sys, user);
    const j = extractJsonObject(out);
    return (j && j.msg ? String(j.msg) : out).slice(0, 320);
  } catch (e) {
    return "Coach offline: " + e.message;
  }
}

// multi-turn chat — raw text reply (JSON mode nahi), context system me embedded
export async function coachChat(messages, ctx) {
  const cfg = getAI();
  const sys =
    HINGLISH_RULE + " " +
    `Abhi India (IST) me ${ctx.time} baje hain. User ka FULL health data: ` +
    JSON.stringify(ctx) +
    " NOTE: ctx waterGlasses GLASSES me hota (1 glass ~250ml, 8 = 2L), litre mat samajhna. " +
    " Chat me short Hinglish replies (2-4 lines max), specific numbers use karo, concrete suggestion ke saath khatam karo. " +
    "last7 me purane din ka pattern dikhe to zaroor mention karo (jaise '3 din se sleep 6h hi hai'). " +
    "healthProfile me medical conditions/meds/allergies hain to advice USI HISAB se do (safe alternatives suggest karo); " +
    "serious concern ho to doctor se milne ki baat clearly bolo, ghabrao mat.";
  try {
    const text = await chatCall(cfg, [{ role: "system", content: sys }, ...messages]);
    return text.slice(0, 800);
  } catch (e) {
    return "Coach offline: " + e.message;
  }
}

export async function chatCall(cfg, msgs) {
  const res = await fetch(cfg.baseUrl.replace(/\/+$/, "") + "/chat/completions", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${cfg.apiKey}`,
      "Content-Type": "application/json",
      "User-Agent": "Mozilla/5.0",
    },
    body: JSON.stringify({ model: cfg.model, messages: msgs, temperature: 0.55 }),
  });
  if (!res.ok) {
    let msg = `HTTP ${res.status}`;
    try {
      const j = await res.json();
      msg = (j.error && (j.error.message || j.error)) || msg;
    } catch {}
    throw new Error(String(msg).slice(0, 160));
  }
  const j = await res.json();
  const text = (j.choices && j.choices[0] && j.choices[0].message && j.choices[0].message.content) || "";
  if (!text.trim()) throw new Error("Empty reply — try again");
  return text;
}

export async function reviewScore(day, scoreRes, target) {
  const cfg = getAI();
  const sys =
    "You are a supportive health coach in a couple health app. Reply strictly as JSON: " +
    '{"review":"..."} with 1-2 short friendly sentences mixing Hindi and English like the user writes. ' +
    "Be specific about the numbers given. Max one emoji.";
  const h = String(day.screenOnTime || "").match(/\d+(\.\d+)?/);
  const user = JSON.stringify({
    kcalTarget: target,
    totalKcal: scoreRes.total,
    macros: scoreRes.macros || null,
    waterGlasses: day.waterGlasses,
    tasksDone: ((day.tasks || []).filter((t) => t.done).length) + "/" + (day.tasks || []).length,
    screenHours: h ? parseFloat(h[0]) : null,
    wakeupTime: day.wakeupTime,
    sleepMood: day.sleepMood,
    dayMood: day.dayMood,
    dayNote: (day.dayNote || "").slice(0, 200),
    meals: [day.breakfast, day.lunch, day.dinner, day.snacks].filter(Boolean).join("; ").slice(0, 300),
    dailyScore: scoreRes.score,
    breakdown: scoreRes.parts.map((p) => `${p[0]} ${p[1]}/${p[2]}`).join(", "),
  });
  try {
    const out = await aiCall(cfg, sys, user);
    const j = extractJsonObject(out);
    return (j && j.review ? String(j.review) : out).slice(0, 240);
  } catch (e) {
    return "AI review offline: " + e.message;
  }
}
