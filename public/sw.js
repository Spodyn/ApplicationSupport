const STATIC_CACHE = "unified-support-static-v1"
const STATIC_ASSETS = [
  "/manifest.webmanifest",
  "/pwa/icon-192",
  "/pwa/icon-512",
  "/pwa/icon-maskable",
]

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(STATIC_CACHE).then((cache) => cache.addAll(STATIC_ASSETS)))
  self.skipWaiting()
})

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== STATIC_CACHE).map((key) => caches.delete(key)))),
  )
  self.clients.claim()
})

self.addEventListener("fetch", (event) => {
  const request = event.request
  const url = new URL(request.url)

  // Zapytania API, żądania uwierzytelnione i mutacje zawsze korzystają wyłącznie z sieci.
  if (
    request.method !== "GET" ||
    request.headers.has("authorization") ||
    url.pathname.startsWith("/api/")
  ) {
    event.respondWith(fetch(request))
    return
  }

  if (url.origin === self.location.origin && STATIC_ASSETS.includes(url.pathname)) {
    event.respondWith(caches.match(request).then((cached) => cached || fetch(request)))
  }
})
