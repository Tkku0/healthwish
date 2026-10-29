/* HealthWish PWA service worker — cache-first shell, network fallback */
const CACHE = "hw-shell-v32";
const ASSETS = ["./", "./index.html", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(
    caches.open(CACHE).then(async (c) => {
      await c.addAll(ASSETS).catch(() => {});
      self.skipWaiting();
    })
  );
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET") return;
  // cross-origin (Firestore / AI APIs): always network passthrough
  if (url.origin !== location.origin) return;
  const cached = caches.match(e.request, { ignoreSearch: true });
  e.respondWith(
    (async () => {
      // hashed asset files: cache-first
      if (url.pathname.includes("/assets/")) {
        const hit = await cached;
        if (hit) return hit;
      }
      try {
        const res = await fetch(e.request);
        // navigation: network-first, cache the html
        if (e.request.mode === "navigation") {
          const c = await caches.open(CACHE);
          c.put("./index.html", res.clone()).catch(() => {});
        } else if (res.ok) {
          const c = await caches.open(CACHE);
          c.put(e.request, res.clone()).catch(() => {});
        }
        return res;
      } catch {
        const hit = await cached;
        if (hit) return hit;
        if (e.request.mode === "navigation") {
          const shell = await caches.match("./index.html", { ignoreSearch: true });
          if (shell) return shell;
        }
        return new Response("offline", { status: 503 });
      }
    })()
  );
});
