// Scoring engine — deterministic (AI review wrapper alag)
export function totalKcal(d) {
  return (d.breakfastKcal || 0) + (d.lunchKcal || 0) + (d.dinnerKcal || 0) + (d.snacksKcal || 0);
}

// kal raat ka sleepTime + aaj ka wakeupTime = aaj ki neend (hours)
export function sleepHours(prevDay, day) {
  if (!prevDay?.sleepTime || !day?.wakeupTime) return 0;
  const [sh, sm] = prevDay.sleepTime.split(":").map(Number);
  const [wh, wm] = day.wakeupTime.split(":").map(Number);
  let mins = wh * 60 + wm - (sh * 60 + sm);
  if (mins <= 0) mins += 24 * 60;
  return Math.round((mins / 60) * 10) / 10;
}

export function screenHours(str) {
  const m = String(str || "").match(/\d+(\.\d+)?/);
  return m ? parseFloat(m[0]) : 0;
}

// habit aaj schedule me hai? (temporary: sirf created day pe, weekly: us weekday pe, baaki roz)
export function taskScheduled(t, dateKey) {
  if (!t) return false;
  if (t.type === "temp") return !t.createdOn || t.createdOn === dateKey;
  if (t.type === "weekly") {
    if (!Array.isArray(t.wdays) || !t.wdays.length) return true;
    const wd = new Date(`${dateKey}T00:00:00`).getDay();
    return t.wdays.includes(wd);
  }
  return true;
}

// score 0-100: habits 30, water 20, kcal 25, meals 10, screen 15
export function dailyScore(day, target) {
  const dateKey = day.dateKey || "";
  const tasks = (day.tasks || []).filter((t) => taskScheduled(t, dateKey));
  const tScore = tasks.length ? Math.round((tasks.filter((t) => t.done).length / tasks.length) * 30) : 0;
  const wScore = Math.min(20, Math.round(((day.waterGlasses || 0) / 8) * 20));
  const total = totalKcal(day);
  let kScore = 0;
  if (total > 0) {
    const dev = Math.abs(total - (target || 2000)) / (target || 2000);
    kScore = dev <= 0.15 ? 25 : dev <= 0.3 ? 18 : dev <= 0.5 ? 10 : 5;
  }
  const meals = ["breakfast", "lunch", "dinner"].filter((m) => (day[m] || "").trim()).length;
  const mScore = Math.round((meals / 3) * 10);
  const h = screenHours(day.screenOnTime);
  const sScore = h > 0 ? (h <= 6 ? 15 : h <= 8 ? 9 : 4) : 0;
  const parts = [
    ["Habits", tScore, 30],
    ["Calories", kScore, 25],
    ["Water", wScore, 20],
    ["Screen", sScore, 15],
    ["Meals", mScore, 10],
  ];
  const score = parts.reduce((s, p) => s + p[1], 0);
  return { score, parts };
}

export function rangeScore(docs, target) {
  const withScore = docs.map((d) => ({ d, s: dailyScore(d, target).score }));
  if (!withScore.length) return { score: 0, count: 0 };
  const avg = Math.round(withScore.reduce((sum, x) => sum + x.s, 0) / withScore.length);
  return { score: avg, count: withScore.length };
}

export function weeklyScore(recent, target, todayK) {
  const last7 = recent.filter((d) => d.dateKey <= todayK && d.dateKey > shift(todayK, -7));
  return rangeScore(last7, target);
}

export function monthlyScore(recent, target, todayK) {
  const month = todayK.slice(0, 7);
  const inMonth = recent.filter((d) => d.dateKey.startsWith(month) && d.dateKey <= todayK);
  return rangeScore(inMonth, target);
}

function shift(key, days) {
  const [y, m, d] = key.split("-").map(Number);
  const dt = new Date(y, m - 1, d + days);
  const p = (n) => String(n).padStart(2, "0");
  return `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`;
}
