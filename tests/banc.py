#!/usr/bin/env python3
"""Banc de test : pilote l'app de debug sur l'émulateur par le protocole DevTools de la WebView.

Usage : python3 tests/banc.py [nom de fichier…]   (sans argument : tout tests/fichiers/)
"""
import base64, json, os, re, socket, struct, subprocess, sys, time, urllib.request

ADB = os.path.expanduser("~/dev-tools/android-sdk/platform-tools/adb")
PAQUET = "fr.jeremyai.lecteur"
PORT = 9333
ICI = os.path.dirname(os.path.abspath(__file__))


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True).stdout


class Ws:
    def __init__(self, url):
        hote, chemin = re.match(r"ws://([^/]+)(/.*)", url).groups()
        h, p = hote.split(":")
        self.s = socket.create_connection((h, int(p)), timeout=180)
        self.s.sendall((f"GET {chemin} HTTP/1.1\r\nHost: {hote}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                        "Sec-WebSocket-Key: dGVzdGJhbmNsZWN0ZXVyMA==\r\nSec-WebSocket-Version: 13\r\n\r\n").encode())
        b = b""
        while b"\r\n\r\n" not in b:
            b += self.s.recv(4096)
        self.n = 0

    def _lire(self, n):
        b = b""
        while len(b) < n:
            b += self.s.recv(n - len(b))
        return b

    def envoyer(self, texte):
        d = texte.encode()
        t = len(d)
        e = b"\x81" + (bytes([0x80 | t]) if t < 126 else bytes([0x80 | 126]) + struct.pack(">H", t) if t < 65536
                       else bytes([0x80 | 127]) + struct.pack(">Q", t))
        self.s.sendall(e + b"\0\0\0\0" + d)

    def recevoir(self):
        donnees = b""
        while True:
            t = self._lire(2)
            n = t[1] & 127
            if n == 126:
                n = struct.unpack(">H", self._lire(2))[0]
            elif n == 127:
                n = struct.unpack(">Q", self._lire(8))[0]
            donnees += self._lire(n)
            if t[0] & 0x80:
                return donnees.decode()

    def cdp(self, methode, **p):
        self.n += 1
        self.envoyer(json.dumps({"id": self.n, "method": methode, "params": p}))
        while True:
            m = json.loads(self.recevoir())
            if m.get("id") == self.n:
                return m

    def js(self, code):
        r = self.cdp("Runtime.evaluate", expression=code, awaitPromise=True, returnByValue=True)
        return r.get("result", {}).get("result", {}).get("value", r.get("result", {}).get("exceptionDetails"))


def connecter():
    pid = adb("shell", "pidof", PAQUET).strip()
    adb("forward", f"tcp:{PORT}", f"localabstract:webview_devtools_remote_{pid}")
    cibles = json.load(urllib.request.urlopen(f"http://127.0.0.1:{PORT}/json"))
    page = next(c for c in cibles if c["type"] == "page")
    return Ws(page["webSocketDebuggerUrl"])


OUVRIR = """(async()=>{{
  const b=window.__morceaux.map(m=>Uint8Array.from(atob(m),c=>c.charCodeAt(0)));
  const dt=new DataTransfer(); dt.items.add(new File(b,{nom})); window.__morceaux=[];
  const c=document.querySelector('#choix'); c.files=dt.files; c.dispatchEvent(new Event('change')); return 1}})()"""

ETAT = """(()=>{const a=document.querySelector('#apercu'),q=s=>document.querySelector(s);
 const c=[...a.querySelectorAll('canvas')];
 return JSON.stringify({entete:q('#entete')?.innerText||'',etat:q('#etat').innerText,
  charge:!!a.querySelector('.chargement'),texte:a.innerText.length,debut:a.innerText.slice(0,90),
  canvas:c.length,canvasW:c[0]?.width||0,img:a.querySelectorAll('img,svg,video,audio,iframe').length,
  boutons:[...document.querySelectorAll('#actions button')].map(b=>b.textContent)})})()"""


def tester(ws, chemin):
    nom = os.path.basename(chemin)
    ws.js("document.querySelector('#apercu').innerHTML='';document.querySelector('#entete').innerHTML='';"
          "window.__morceaux=[]")
    with open(chemin, "rb") as f:
        while morceau := f.read(600_000):
            ws.js(f"window.__morceaux.push('{base64.b64encode(morceau).decode()}');0")
    ws.js(OUVRIR.format(nom=json.dumps(nom)))
    fin = time.time() + 90
    e = {}
    while time.time() < fin:
        time.sleep(1)
        e = json.loads(ws.js(ETAT))
        if e["entete"] and not e["charge"] and (e["boutons"] or e["texte"] > 0 or e["canvas"] or e["img"]):
            break
    minimal = "Lecture minimale" in e.get("entete", "") or "n'a pas pu ouvrir" in e.get("debut", "")
    verdict = "ÉCHEC" if not e.get("entete") or e.get("charge") else "MINIMAL" if minimal else "OK"
    print(f"{verdict:8}{nom:22}{e.get('entete','').splitlines()[-1:]}  canvas={e.get('canvas')}/{e.get('canvasW')} "
          f"médias={e.get('img')} texte={e.get('texte')} boutons={e.get('boutons')}", flush=True)
    return verdict


if __name__ == "__main__":
    ws = connecter()
    dossier = os.path.join(ICI, "fichiers")
    noms = sys.argv[1:] or sorted(os.listdir(dossier))
    bilan = [tester(ws, os.path.join(dossier, n)) for n in noms if not n.startswith(".")]
    print("Bilan :", {v: bilan.count(v) for v in set(bilan)})
    print("Fuite réseau (doit être bloquée) :", ws.js(
        "fetch('https://example.com/').then(r=>'SORTI '+r.status).catch(e=>'bloqué : '+e.message)"))
