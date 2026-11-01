// Pont entre le lecteur (écrit pour un navigateur) et Android.
// Une WebView n'enregistre ni les liens a[download] ni window.print() : on les confie à l'app.

(() => {
  const pont = window.LecteurAndroid;
  if (!pont) return;
  const TAILLE = 3 * 1024 * 1024; // multiple de 3 : chaque morceau se décode seul

  const enBase64 = (blob) => new Promise((ok, ko) => {
    const lecteur = new FileReader();
    lecteur.onload = () => ok(String(lecteur.result).split(",")[1] || "");
    lecteur.onerror = () => ko(lecteur.error);
    lecteur.readAsDataURL(blob);
  });

  async function transmettre(lien) {
    const blob = await (await fetch(lien.href)).blob();
    const id = pont.debut(lien.download || "fichier", blob.type || "");
    for (let i = 0; i < blob.size; i += TAILLE) pont.morceau(id, await enBase64(blob.slice(i, i + TAILLE)));
    pont.fin(id);
  }

  const estTelechargement = (el) => el instanceof HTMLAnchorElement && el.hasAttribute("download")
    && /^(blob|data):/.test(el.href);

  const clic = HTMLElement.prototype.click;
  HTMLElement.prototype.click = function () {
    if (estTelechargement(this)) transmettre(this).catch(console.error);
    else clic.call(this);
  };
  const envoi = EventTarget.prototype.dispatchEvent;
  EventTarget.prototype.dispatchEvent = function (ev) {
    if (ev.type === "click" && estTelechargement(this)) {
      transmettre(this).catch(console.error);
      return false;
    }
    return envoi.call(this, ev);
  };
  document.addEventListener("click", (ev) => {
    const lien = ev.target.closest?.("a[download]");
    if (lien && estTelechargement(lien)) {
      ev.preventDefault();
      transmettre(lien).catch(console.error);
    }
  }, true);

  // L'impression native est asynchrone : la classe « impression » doit rester posée jusqu'au bout.
  let enImpression = false;
  let cadreImprime = null;
  const classes = document.documentElement.classList.constructor.prototype;
  const retirer = classes.remove;
  classes.remove = function (...noms) {
    if (enImpression && noms.includes("impression")) return;
    retirer.apply(this, noms);
  };
  window.print = () => {
    enImpression = true;
    pont.imprimer(document.title);
  };
  window.__lecteurFinImpression = () => {
    enImpression = false;
    retirer.call(document.body.classList, "impression");
    if (cadreImprime) {
      cadreImprime.style.height = "";
      cadreImprime = null;
    }
  };
  // Pages HTML et e-mails sont affichés dans un cadre : on l'étire puis on imprime la page entière.
  document.addEventListener("load", (ev) => {
    const cadre = ev.target;
    if (!(cadre instanceof HTMLIFrameElement)) return;
    try {
      cadre.contentWindow.print = () => {
        cadreImprime = cadre;
        cadre.style.height = cadre.contentDocument.documentElement.scrollHeight + "px";
        document.body.classList.add("impression");
        window.print();
      };
    } catch (e) { console.error(e); }
  }, true);

  // L'image avant tout : la mise en page plein écran se pose avant que le lecteur mesure son espace.
  document.addEventListener("DOMContentLoaded", () => {
    const panneau = document.querySelector("#panneau");
    new MutationObserver(() => document.body.classList.toggle("visuel", !panneau.hidden))
      .observe(panneau, { attributes: true, attributeFilter: ["hidden"] });
    document.querySelector("#fermer").onclick = () => { window.__lecteurRetour(); };
    document.querySelector("#dossier").onclick = () => pont.choisirDossier();
  });
  window.__lecteurRetour = () => {
    const panneau = document.querySelector("#panneau");
    if (panneau.hidden) return false;
    panneau.hidden = true;
    return true;
  };

  // Fichiers reçus par « Ouvrir avec » ou « Partager ».
  window.__lecteurOuvrir = async (liste) => {
    const transfert = new DataTransfer();
    for (const { url, nom, type } of liste) {
      const reponse = await fetch(url);
      if (reponse.ok) transfert.items.add(new File([await reponse.blob()], nom, { type }));
      else alert(`Impossible de lire « ${nom} » : l'application d'origine n'en donne pas l'accès.`);
    }
    if (!transfert.files.length) return;
    const choix = document.querySelector("#choix");
    choix.files = transfert.files;
    choix.dispatchEvent(new Event("change"));
  };
})();
