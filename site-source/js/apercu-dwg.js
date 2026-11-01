// Image de secours des plans DWG : presque tous embarquent un petit aperçu enregistré par le logiciel de CAO.
// Il prend le relais quand le plan est trop complexe pour le moteur LibreDWG.
const SENTINELLE = [0x1f, 0x25, 0x6d, 0x07, 0xd4, 0x36, 0x28, 0x28, 0x9d, 0x57, 0xca, 0x3f, 0x9d, 0x44, 0x10, 0x2b];

function bmpDepuisDib(dib) {
  const vue = new DataView(dib.buffer, dib.byteOffset, dib.byteLength);
  const entete = vue.getUint32(0, true);
  const bits = vue.getUint16(14, true);
  const couleurs = vue.getUint32(32, true) || (bits <= 8 ? 2 ** bits : 0);
  const bmp = new Uint8Array(14 + dib.length);
  const sortie = new DataView(bmp.buffer);
  bmp.set([0x42, 0x4d]);
  sortie.setUint32(2, bmp.length, true);
  sortie.setUint32(10, 14 + entete + couleurs * 4, true);
  bmp.set(dib, 14);
  return new Blob([bmp], { type: "image/bmp" });
}

export async function apercuIntegre(fichier) {
  const octets = new Uint8Array(await fichier.arrayBuffer());
  const vue = new DataView(octets.buffer);
  if (octets.length < 0x20) return null;
  const debut = vue.getUint32(0x0d, true);
  if (debut + 21 > octets.length || SENTINELLE.some((o, i) => octets[debut + i] !== o)) return null;
  const nombre = octets[debut + 20];
  for (let i = 0, p = debut + 21; i < nombre; i++, p += 9) {
    const code = octets[p];
    const depart = vue.getUint32(p + 1, true);
    const longueur = vue.getUint32(p + 5, true);
    if (depart + longueur > octets.length) continue;
    const donnees = octets.subarray(depart, depart + longueur);
    if (code === 6) return new Blob([donnees], { type: "image/png" });
    if (code === 2) return bmpDepuisDib(donnees);
  }
  return null;
}

export async function afficher(fichier, apercu, ctx, activerZoom) {
  const image = await apercuIntegre(fichier);
  if (!image) return null;
  const img = new Image();
  img.src = URL.createObjectURL(image);
  await img.decode();
  apercu.innerHTML = "";
  apercu.classList.add("plan");
  img.alt = fichier.name;
  img.draggable = false;
  img.style.imageRendering = "pixelated";
  apercu.appendChild(img);
  activerZoom(apercu, img);
  ctx.statut("Plan trop complexe pour un rendu complet : voici l'aperçu enregistré dans le fichier.");
  const nom = fichier.name.replace(/\.[^.]+$/, "");
  return [{
    libelle: "Image PNG",
    principal: true,
    faire: async () => {
      const toile = document.createElement("canvas");
      toile.width = img.naturalWidth;
      toile.height = img.naturalHeight;
      toile.getContext("2d").drawImage(img, 0, 0);
      const blob = await new Promise((ok) => toile.toBlob(ok, "image/png"));
      ctx.telecharger(blob, nom + ".png");
    },
  }];
}
