const SHELL_CACHE = "fitsync-shell-v2";
const ASSET_CACHE = "fitsync-assets-v1";
const STATIC_CACHE = "fitsync-static-v1";
const MAX_ASSETS = 80;

self.addEventListener("install", () => {
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            // 只清理旧应用壳；hashed 资源缓存跨版本保留，避免旧 chunk 404。
            .filter(
              (key) =>
                key.startsWith("fitsync-shell-") && key !== SHELL_CACHE,
            )
            .map((key) => caches.delete(key)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

async function trimCache(cacheName, maxEntries) {
  const cache = await caches.open(cacheName);
  const keys = await cache.keys();
  if (keys.length <= maxEntries) return;
  await Promise.all(
    keys.slice(0, keys.length - maxEntries).map((key) => cache.delete(key)),
  );
}

async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);
  if (cached) return cached;
  const response = await fetch(request);
  if (response && response.ok && response.type === "basic") {
    await cache.put(request, response.clone());
    if (cacheName === ASSET_CACHE) await trimCache(cacheName, MAX_ASSETS);
  }
  return response;
}

async function networkFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  try {
    const response = await fetch(request);
    if (response && response.ok && response.type === "basic") {
      await cache.put(request, response.clone());
    }
    return response;
  } catch {
    const cached = await cache.match(request);
    if (cached) return cached;
    if (request.mode === "navigate") {
      const shell = await cache.match("/");
      if (shell) return shell;
    }
    return Response.error();
  }
}

async function staleWhileRevalidate(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);
  const network = fetch(request)
    .then((response) => {
      if (response && response.ok && response.type === "basic") {
        void cache.put(request, response.clone());
      }
      return response;
    })
    .catch(() => null);
  if (cached) {
    void network;
    return cached;
  }
  const response = await network;
  return response || Response.error();
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith("/api/") || url.pathname === "/sw.js") return;

  if (url.pathname.startsWith("/assets/")) {
    event.respondWith(cacheFirst(request, ASSET_CACHE));
    return;
  }
  if (request.mode === "navigate") {
    event.respondWith(networkFirst(request, SHELL_CACHE));
    return;
  }
  event.respondWith(staleWhileRevalidate(request, STATIC_CACHE));
});
