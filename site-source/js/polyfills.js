// Fonctions récentes utilisées par le lecteur PDF, absentes des WebView qui ne sont pas à jour.
// Ce fichier est chargé par la page et recopié en tête de pdf.worker.min.mjs.
if (!Promise.try) Promise.try = (f, ...a) => new Promise((r) => r(f(...a)));
if (!Math.sumPrecise) Math.sumPrecise = (l) => { let t = 0; for (const n of l) t += n; return t; };
if (!URL.parse) URL.parse = (u, b) => { try { return new URL(u, b); } catch { return null; } };
for (const C of [Map, WeakMap]) {
  if (!C.prototype.getOrInsert) C.prototype.getOrInsert = function (k, v) { if (!this.has(k)) this.set(k, v); return this.get(k); };
  if (!C.prototype.getOrInsertComputed) C.prototype.getOrInsertComputed = function (k, f) { if (!this.has(k)) this.set(k, f(k)); return this.get(k); };
}
if (!Uint8Array.prototype.toHex) Uint8Array.prototype.toHex = function () { return Array.from(this, (o) => o.toString(16).padStart(2, "0")).join(""); };
if (!Uint8Array.prototype.toBase64) Uint8Array.prototype.toBase64 = function () { let s = ""; for (const o of this) s += String.fromCharCode(o); return btoa(s); };
if (!Uint8Array.fromBase64) Uint8Array.fromBase64 = (t) => Uint8Array.from(atob(t), (c) => c.charCodeAt(0));
