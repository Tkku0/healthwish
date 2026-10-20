// Firebase Identity Toolkit REST auth — koi SDK nahi, chhota rakhne ke liye.
// Token ke saath Firestore REST calls ko Authorization: Bearer ... me laga diya jayega.

const KEY = "AIzaSyCoi_9edMNxWd__lBHb0Kj2sPHOXDaHOiI";
const SIGNUP = `https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=${KEY}`;
const SIGNIN = `https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${KEY}`;
const REFRESH = `https://securetoken.googleapis.com/v1/token?key=${KEY}`;

const K_UID = "hw_auth_uid";
const K_REFRESH = "hw_auth_refresh";
const K_EMAIL = "hw_auth_email";
const K_TOKEN = "hw_auth_token"; // short-lived, session me cache
const K_EXP = "hw_auth_exp";

function jerr(data, fallback) {
  const e = data && data.error && data.error.message ? data.error.message : fallback || "Network error";
  return String(e).replace(/_/g, " ").slice(0, 140);
}

async function idslot(endpoint, body) {
  const res = await fetch(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const d = await res.json();
  if (!res.ok || !d.idToken) throw new Error(jerr(d));
  return d;
}

export function getSession() {
  const uid = localStorage.getItem(K_UID);
  const email = localStorage.getItem(K_EMAIL);
  if (!uid) return null;
  return { uid, email: email || "", };
}

export async function getValidToken() {
  if (!getSession()) return null;
  const exp = Number(localStorage.getItem(K_EXP) || 0);
  if (exp && Date.now() / 1000 < exp - 120) {
    const t = localStorage.getItem(K_TOKEN);
    if (t) return t;
  }
  return refresh();
}

export async function refresh() {
  const rt = localStorage.getItem(K_REFRESH);
  if (!rt) return null;
  const res = await fetch(REFRESH, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ grant_type: "refresh_token", refresh_token: rt }),
  });
  const d = await res.json();
  if (!res.ok) {
    if ((d.error && d.error.message === "INVALID_REFRESH_TOKEN") || /TOKEN_EXPIRED|USER_DISABLED|INVALID/i.test(String(d.error && d.error.message))) {
      logout(); // session khatam — dobara login
    }
    throw new Error(jerr(d, "Session expired — dobara login karo"));
  }
  localStorage.setItem(K_TOKEN, d.id_token);
  localStorage.setItem(K_EXP, String(Math.floor(Date.now() / 1000) + Number(d.expires_in || 3600)));
  localStorage.setItem(K_REFRESH, d.refresh_token);
  localStorage.setItem(K_UID, d.user_id);
  return d.id_token;
}

export async function emailAuth(email, password, isSignup = false) {
  const d = await idslot(isSignup ? SIGNUP : SIGNIN, { email, password, returnSecureToken: true });
  localStorage.setItem(K_TOKEN, d.idToken);
  localStorage.setItem(K_EXP, String(Math.floor(Date.now() / 1000) + Number(d.expiresIn || 3600)));
  localStorage.setItem(K_REFRESH, d.refreshToken);
  localStorage.setItem(K_UID, d.localId);
  localStorage.setItem(K_EMAIL, email.trim().toLowerCase());
  return getSession();
}

export function logout() {
  for (const k of [K_UID, K_REFRESH, K_EMAIL, K_TOKEN, K_EXP]) localStorage.removeItem(k);
}
