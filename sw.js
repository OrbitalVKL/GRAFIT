// Grafit Mobile 1.6.2 : fonctionne hors ligne.
// Page : réseau d'abord (3 s), copie en cache sinon -> une mise à jour s'affiche dès le relancement suivant.
// Icônes, manifeste : cache, rafraîchi en arrière-plan. Polices Google : cache. Flux (météo, TLE) : réseau seulement.
const SHELL = 'grafit-shell-1.6.2';
const FILES = ['./', 'index.html', 'manifest.webmanifest', 'icon-192.png', 'icon-512.png', 'icon-maskable-512.png', 'apple-touch-icon.png'];
self.addEventListener('install', e => { e.waitUntil(caches.open(SHELL).then(c => c.addAll(FILES)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== SHELL && k !== 'grafit-fonts').map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  const req = e.request, u = new URL(req.url);
  if (req.method !== 'GET') return;
  if (u.origin === location.origin) {
    const page = req.mode === 'navigate' || u.pathname.endsWith('/') || u.pathname.endsWith('.html');
    if (page) {
      e.respondWith((async () => {
        const c = await caches.open(SHELL);
        try {
          const r = await Promise.race([fetch(req, { cache: 'no-store' }), new Promise((_, no) => setTimeout(() => no(new Error('timeout')), 3000))]);
          if (r.ok) c.put('index.html', r.clone());
          return r;
        } catch (err) { return (await c.match('index.html')) || (await c.match(req, { ignoreSearch: true })) || Response.error(); }
      })());
      return;
    }
    e.respondWith(caches.open(SHELL).then(async c => {
      const hit = await c.match(req, { ignoreSearch: true });
      const net = fetch(req).then(r => { if (r.ok) c.put(req, r.clone()); return r; }).catch(() => hit);
      return hit || net;
    }));
  } else if (u.hostname === 'fonts.googleapis.com' || u.hostname === 'fonts.gstatic.com') {
    e.respondWith(caches.open('grafit-fonts').then(async c => (await c.match(req)) || fetch(req).then(r => { c.put(req, r.clone()); return r; })));
  }
});
