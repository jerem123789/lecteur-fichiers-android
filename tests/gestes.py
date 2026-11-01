#!/usr/bin/env python3
"""Vérifie le mode plein écran et le zoom à deux doigts sur le fichier affiché. Usage : tests/gestes.py <fichier>"""
import json, sys, time
import banc

ws = banc.connecter()
banc.tester(ws, f"{banc.ICI}/fichiers/{sys.argv[1]}")
time.sleep(1)
print(ws.js("""JSON.stringify({visuel:document.body.className,
  apercu:Math.round(document.querySelector('#apercu').getBoundingClientRect().height),
  ecran:innerHeight, haut:getComputedStyle(document.querySelector('.haut')).display})"""))
transforme = "(document.querySelector('#apercu img,#apercu canvas')||{style:{}}).style.transform"
print("avant :", ws.js(transforme))
x, y, ecart = 200, 450, 40
pts = lambda e: [{"x": x - e, "y": y, "id": 0}, {"x": x + e, "y": y, "id": 1}]
ws.cdp("Input.dispatchTouchEvent", type="touchStart", touchPoints=pts(ecart))
for pas in range(1, 9):
    ws.cdp("Input.dispatchTouchEvent", type="touchMove", touchPoints=pts(ecart + pas * 15))
    time.sleep(0.03)
ws.cdp("Input.dispatchTouchEvent", type="touchEnd", touchPoints=[])
time.sleep(0.4)
print("après :", ws.js(transforme))
