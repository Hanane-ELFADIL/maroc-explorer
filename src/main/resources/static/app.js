// ---- Thème : clair / sombre / système ----
const themeSel = document.getElementById('theme');
if (themeSel) {
  themeSel.value = localStorage.getItem('theme') || 'system';
  themeSel.onchange = () => { localStorage.setItem('theme', themeSel.value); location.reload(); };
}
// ---- Langue : conserve la page et les filtres ----
document.querySelectorAll('[data-lang]').forEach(a => a.onclick = e => {
  e.preventDefault(); const u = new URL(location); u.searchParams.set('lang', a.dataset.lang); location = u;
});
// ---- Menu mobile ----
const burger = document.getElementById('burger');
if (burger) burger.onclick = () => document.getElementById('menu').classList.toggle('open');
// ---- Extrait Wikipédia (texte) sur la fiche d'un lieu ; les photos sont gérées côté serveur (PhotoService) ----
async function wiki(q) {
  const lang = document.documentElement.lang || 'fr', k = 'w3:' + lang + ':' + q, c = localStorage.getItem(k);
  if (c) return JSON.parse(c);
  const u = 'https://' + lang + '.wikipedia.org/w/api.php?action=query&format=json&origin=*&generator=search&gsrlimit=1&prop=pageimages|extracts|info&inprop=url&piprop=thumbnail&pithumbsize=1000&exintro=1&explaintext=1&exsentences=4&gsrsearch=' + encodeURIComponent(q);
  try {
    const r = await (await fetch(u)).json(), p = Object.values((r.query || {}).pages || {})[0];
    const words = q.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').split(/[\s'-]+/).filter(w => w.length > 3);
    const t = p ? p.title.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '') : '';
    const ok = p && words.some(w => t.includes(w));
    const o = ok ? {img: p.thumbnail && p.thumbnail.source, text: p.extract, url: p.fullurl} : {};
    localStorage.setItem(k, JSON.stringify(o)); return o;
  } catch (e) { return {}; }
}
document.querySelectorAll('[data-wiki-text]').forEach(async el => {
  const o = await wiki(el.dataset.wikiText);
  if (o.text) { el.textContent = o.text + ' '; const a = document.createElement('a'); a.href = o.url; a.target = '_blank'; a.rel = 'noopener'; a.textContent = 'Wikipédia ↗'; el.append(a); }
});
// ---- Remplacement des images placeholder par les photos Wikipédia ----
document.querySelectorAll('img[data-wiki]').forEach(async img => {
  if (!img.dataset.wiki) return;
  // Only replace if image is still showing the generic fallback (external Wikimedia default URLs)
  const src = img.src || '';
  const isFallback = src.includes('Jemaa_el-Fnaa') || src.includes('La_Mamounia') || src.includes('Moroccan_tea') ||
                     src.includes('Merzouga_desert') || src.includes('Gnaoua') || src.includes('Train_Rabat') || src.includes('Hassan_II_Mosque');
  if (!isFallback && !src.includes('_defaults')) return;
  const o = await wiki(img.dataset.wiki);
  if (o.img) { img.src = o.img; }
});
// ---- Carte Leaflet ----
const mapEl = document.getElementById('map');
if (mapEl && window.L) {
  const map = L.map('map').setView([31.8, -7.0], 5), layer = L.layerGroup().addTo(map);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {attribution: '© OpenStreetMap'}).addTo(map);
  const esc = s => String(s).replace(/[&<>"]/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c]));
  const draw = ms => {
    layer.clearLayers();
    ms.forEach(m => L.marker([m.lat, m.lon]).bindPopup('<div class="pop"><img src="' + esc(m.img) + '" alt=""><a href="/place/' + esc(m.col) + '/' + esc(m.slug) + '"><b>' + esc(m.name) + '</b></a><small>' + esc(m.city) + '</small></div>').addTo(layer));
    if (ms.length) map.fitBounds(ms.map(m => [m.lat, m.lon]), {padding: [30, 30], maxZoom: 14});
  };
  draw(window.MARKERS || []);
  const near = document.getElementById('near');
  if (near) near.onclick = () => navigator.geolocation.getCurrentPosition(p =>
    fetch('/api/nearby?lat=' + p.coords.latitude + '&lon=' + p.coords.longitude + '&m=3000').then(r => r.json()).then(draw));
}
