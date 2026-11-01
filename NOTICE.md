# Bibliothèques embarquées

`site-source/` est une version compilée et minifiée. Cette liste a été établie à partir des traces trouvées dans ce code et des licences publiées par ces projets. Les versions exactes embarquées ne sont pas connues.

| Bibliothèque | Rôle | Licence | Source |
| --- | --- | --- | --- |
| LibreDWG (libredwg-web) | Lecture DWG | GPL-3.0 | https://github.com/LibreDWG/libredwg, https://github.com/mlightcad/libredwg-web |
| occt-import-js (OpenCascade) | Lecture STEP, IGES, BREP | LGPL-2.1 | https://github.com/kovacsv/occt-import-js |
| three.js | Affichage 3D | MIT | https://github.com/mrdoob/three.js |
| PDF.js | Lecture PDF | Apache-2.0 | https://github.com/mozilla/pdf.js |
| pdf-lib | Création PDF | MIT | https://github.com/Hopding/pdf-lib |
| SheetJS | Tableurs | Apache-2.0 | https://sheetjs.com |
| heic2any et libheif | Photos HEIC | MIT, LGPL-3.0 | https://github.com/alexcorvi/heic2any, https://github.com/strukturag/libheif |
| UTIF | Images TIFF | MIT | https://github.com/photopea/UTIF.js |
| JSZip | Archives ZIP | MIT ou GPL-3.0 | https://github.com/Stuk/jszip |
| msgreader | E-mails .msg | Apache-2.0 | https://github.com/HiraokaHyperTools/msgreader |
| iconv-lite | Encodages de texte | MIT | https://github.com/ashtuchkin/iconv-lite |
| pako | Compression | MIT et Zlib | https://github.com/nodeca/pako |

Le code compilé peut contenir d'autres bibliothèques non identifiées. Si vous en reconnaissez une, ouvrez une issue pour compléter cette liste.

# Retouches apportées à `site-source/`

- `index.html` et messages d'état : textes adaptés au téléphone.
- `js/android.js` et `js/polyfills.js` : ajoutés pour Android et pour les WebView anciennes.
- `js/pdf.worker.min.mjs` : `polyfills.js` recopié en tête.
- `js/lib-45FWJP6G.js` : correction de l'ouverture des e-mails `.msg`.
- `js/lib-JMNYUMEN.js` : moteur DWG remis à zéro après un plantage, texte d'aide tactile.
- `js/main.js` : message affiché après une conversion.
- `js/lib-5A73KNPL.js` : zoom à deux doigts sur les plans et les images.
- `js/lib-JMNYUMEN.js` : recours à l'image intégrée au DWG (`js/apercu-dwg.js`) si le plan est trop complexe.
- `index.html` et `style.css` : bouton Dossier et affichage plein écran.
