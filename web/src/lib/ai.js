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
    return c && c.key && c.key.length >= 8 && c.baseUrl ? c : null;
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
  return a.baseUrl.startsWith("http") && a.apiKey.length >= 8 && !!a.model;
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
    '\n\nReply strictly as JSON: {"items":[{"label":"food","kcal":int}],"total":int}. ' +
    "label in the user's language, short. total = sum of items.";
  const out = await aiCall(cfg, system, input);
  const j = extractJsonObject(out);
  if (!j || !Array.isArray(j.items)) throw new Error("AI samajh nahi paya — thoda clear likho 🙂");
  const items = j.items
    .map((it) => ({ label: String(it.label || "food").slice(0, 60), kcal: Math.round(Number(it.kcal) || 0) }))
    .filter((it) => it.kcal >= 0 && it.label);
  let total = Math.round(Number(j.total) || 0);
  if (!total) total = items.reduce((s, it) => s + it.kcal, 0);
  if (!items.length) throw new Error("koi item nahi mila");
  return { items, total };
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
