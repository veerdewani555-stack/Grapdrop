"""Grab & Drop receiver for Windows.
Run:  pip install zeroconf mediapipe opencv-python requests
      python receiver.py
Grab on the phone, then show a fist -> open palm to the laptop webcam to drop."""
import os, time, threading, winsound
import cv2, mediapipe as mp, requests
from math import hypot
from zeroconf import Zeroconf, ServiceBrowser

SAVE = os.path.join(os.path.expanduser("~"), "Downloads", "GrabDrop")
os.makedirs(SAVE, exist_ok=True)
carrying = {}

class Listener:
    def add_service(self, zc, t, name):
        i = zc.get_service_info(t, name)
        if i and i.parsed_addresses():
            p = i.properties
            carrying[name] = (i.parsed_addresses()[0], i.port, p.get(b"t", b"").decode(), p.get(b"n", b"file").decode())
            print("📱 Phone is carrying:", carrying[name][3])
    def update_service(self, zc, t, name): self.add_service(zc, t, name)
    def remove_service(self, zc, t, name): carrying.pop(name, None)

def fetch():
    for name, (ip, port, tok, fn) in list(carrying.items()):
        try:
            r = requests.get(f"http://{ip}:{port}/file", params={"t": tok}, timeout=60)
            if r.ok:
                base, ext = os.path.splitext(fn); path = os.path.join(SAVE, fn); n = 1
                while os.path.exists(path): path = os.path.join(SAVE, f"{base}_{n}{ext}"); n += 1
                open(path, "wb").write(r.content); carrying.pop(name, None)
                winsound.Beep(1000, 200); print("✅ Saved", path); os.startfile(path)
        except Exception as e:
            print("Transfer failed:", e)

def classify(lm):
    d = lambda a, b: hypot(lm[a].x - lm[b].x, lm[a].y - lm[b].y)
    ext = sum(d(t, 0) > d(p, 0) * 1.1 for t, p in [(8, 6), (12, 10), (16, 14), (20, 18)])
    return "open" if ext >= 4 else "fist" if ext <= 1 else None

zc = Zeroconf(); ServiceBrowser(zc, "_grabdrop._tcp.local.", Listener())
hands = mp.solutions.hands.Hands(max_num_hands=1)
cam = cv2.VideoCapture(0)
cand = conf = None; since = last_fist = 0
print("👀 Watching webcam. Grab on phone, then fist -> open palm here. Ctrl+C to quit.")
try:
    while True:
        ok, frame = cam.read()
        if not ok: time.sleep(0.2); continue
        res = hands.process(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        g = classify(res.multi_hand_landmarks[0].landmark) if res.multi_hand_landmarks else None
        now = time.time()
        if g != cand: cand, since = g, now
        if cand and now - since >= 0.5 and cand != conf:
            conf = cand
            if conf == "fist": last_fist = now
            elif conf == "open" and now - last_fist < 5 and carrying: threading.Thread(target=fetch).start()
        if conf == "fist": last_fist = now
        if not cand and now - since > 1.5: conf = None
        time.sleep(0.05)
except KeyboardInterrupt:
    pass
