aa"""Grab & Drop for Windows, v2: receives from the phone AND sends to the phone.
Install:  pip install zeroconf mediapipe opencv-python requests pywin32
Run:      python receiver.py

Phone -> laptop: grab on the phone (open palm -> fist), then fist -> open palm at this webcam.
Laptop -> phone: copy files in Explorer (Ctrl+C), then open palm -> fist at this webcam,
                 then on the phone fist -> open palm. The phone must be approved by a pop-up here.
Typed commands (then ENTER):  (empty) = receive now | send = carry clipboard files | cancel | <ip:port/token>
"""
import os, re, json, time, uuid, socket, ctypes, mimetypes, threading, urllib.request, winsound
from math import hypot
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs
import cv2, mediapipe as mp, requests
from mediapipe.tasks import python as mpt
from mediapipe.tasks.python import vision
from zeroconf import Zeroconf, ServiceBrowser, ServiceInfo

HERE = os.path.dirname(os.path.abspath(__file__))
MODEL = os.path.join(HERE, "hand_landmarker.task")
MODEL_URL = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
SAVE = os.path.join(os.path.expanduser("~"), "Downloads", "GrabDrop")
os.makedirs(SAVE, exist_ok=True)
OPEN_MIN, FIST_MAX = 1.55, 1.30
SVC_PHONE, SVC_PC = "_grabdrop._tcp.local.", "_grabdrop-pc._tcp.local."
CARRY_SECS = 60
HOST = socket.gethostname()

carrying = {}   # phone-announced files we can fetch
out = dict(files=[], token="", served=set(), allowed=set(), denied=set(), until=0, srv=None, info=None)
ask_lock = threading.Lock()

# Persistent identity + trusted phones (so you are not asked every time)
ID_FILE = os.path.join(HERE, "device_id.txt")
TRUST_FILE = os.path.join(HERE, "trusted.json")
if not os.path.exists(ID_FILE): open(ID_FILE, "w").write(uuid.uuid4().hex)
MY_ID = open(ID_FILE).read().strip()
try: trusted = json.load(open(TRUST_FILE))
except Exception: trusted = {}
def save_trusted(): json.dump(trusted, open(TRUST_FILE, "w"))

if not os.path.exists(MODEL):
    print("Downloading hand model (one time)…")
    urllib.request.urlretrieve(MODEL_URL, MODEL)

zc = Zeroconf()

# ---------------- receive (phone -> laptop) ----------------
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
    if not carrying: print("Nothing to fetch (phone is not carrying anything)."); return
    for name, (ip, port, tok, label) in list(carrying.items()):
        base = f"http://{ip}:{port}"
        try:
            print("Asking the phone… tap Allow in its notification.")
            r = requests.get(base + "/list", params={"t": tok, "d": HOST, "id": MY_ID}, timeout=40)
            if r.status_code == 403: print("The phone denied the transfer (or timed out)."); continue
            names = r.text.split("\n")
            for i, fn in enumerate(names):
                r = requests.get(base + "/file", params={"i": i, "t": tok}, timeout=120)
                if not r.ok: continue
                stem, ext = os.path.splitext(fn); path = os.path.join(SAVE, fn); n = 1
                while os.path.exists(path): path = os.path.join(SAVE, f"{stem}_{n}{ext}"); n += 1
                open(path, "wb").write(r.content); print("✅ Saved", path)
            carrying.pop(name, None); winsound.Beep(1000, 200); os.startfile(SAVE)
        except Exception as e:
            print("Transfer failed:", e)

# ---------------- send (laptop -> phone) ----------------
def clipboard_files():
    try:
        import win32clipboard as wc, win32con
        wc.OpenClipboard()
        try:
            if wc.IsClipboardFormatAvailable(win32con.CF_HDROP):
                return [f for f in wc.GetClipboardData(win32con.CF_HDROP) if os.path.isfile(f)]
        finally:
            wc.CloseClipboard()
    except Exception:
        pass
    return []

def local_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try: s.connect(("10.255.255.255", 1)); return s.getsockname()[0]
    finally: s.close()

def ask_user(dev, ip, n):
    # MB_YESNOCANCEL | MB_ICONQUESTION | MB_SETFOREGROUND | MB_TOPMOST ; 6 = Yes, 7 = No, 2 = Cancel
    r = ctypes.windll.user32.MessageBoxW(0,
        f"'{dev}' ({ip}) wants to download {n} file(s).\n\nYes = Always allow this device\nNo = Allow this time only\nCancel = Deny",
        "Grab & Drop", 0x3 | 0x20 | 0x10000 | 0x40000)
    return "always" if r == 6 else "once" if r == 7 else None

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        u = urlparse(self.path); q = parse_qs(u.query); ip = self.client_address[0]
        files = out["files"]
        if not files or q.get("t", [""])[0] != out["token"]: return self.send_error(403)
        if ip not in out["allowed"]:
            with ask_lock:
                if ip in out["denied"]: return self.send_error(403)
                if ip not in out["allowed"]:
                    pid, dev = q.get("id", [""])[0], q.get("d", [ip])[0]
                    if pid and pid in trusted:
                        out["allowed"].add(ip); print(f"Auto-allowed trusted device: {dev}")
                    else:
                        res = ask_user(dev, ip, len(files))
                        if res == "always" and pid: trusted[pid] = dev; save_trusted()
                        (out["allowed"] if res else out["denied"]).add(ip)
            if ip not in out["allowed"]: return self.send_error(403)
        if u.path == "/list":
            body = "\n".join(os.path.basename(f) for f in files).encode("utf-8")
            self.send_response(200); self.send_header("Content-Length", str(len(body))); self.end_headers()
            self.wfile.write(body); return
        i = int(q.get("i", ["0"])[0])
        if i >= len(files): return self.send_error(404)
        path = files[i]
        self.send_response(200)
        self.send_header("Content-Type", mimetypes.guess_type(path)[0] or "application/octet-stream")
        self.send_header("Content-Length", str(os.path.getsize(path))); self.end_headers()
        with open(path, "rb") as f:
            while chunk := f.read(65536): self.wfile.write(chunk)
        out["served"].add(i)
        if len(out["served"]) >= len(files):
            winsound.Beep(1000, 200)
            threading.Thread(target=stop_carry, args=("✅ Sent to phone",)).start()

def start_carry(quiet=False):
    if out["srv"]:
        if not quiet: print("Already carrying.")
        return
    files = clipboard_files()
    if not files:
        if not quiet: print("Clipboard has no files. Select files in Explorer and press Ctrl+C first.")
        return
    out.update(files=files, token=uuid.uuid4().hex[:8], served=set(), allowed=set(), denied=set(), until=time.time() + CARRY_SECS)
    srv = ThreadingHTTPServer(("0.0.0.0", 0), Handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    label = (os.path.basename(files[0]) + (f" +{len(files) - 1}" if len(files) > 1 else ""))[:60]
    info = ServiceInfo(SVC_PC, f"GrabDrop-{HOST}.{SVC_PC}", addresses=[socket.inet_aton(local_ip())],
                       port=srv.server_port, properties={"t": out["token"], "n": label})
    zc.register_service(info); out.update(srv=srv, info=info)
    winsound.Beep(800, 150)
    print(f"✊ Carrying {label}. On the phone: fist -> open palm (or tap Receive).")

def stop_carry(msg="Stopped"):
    srv, info = out["srv"], out["info"]
    out.update(srv=None, info=None, files=[])
    if info: zc.unregister_service(info)
    if srv: srv.shutdown(); srv.server_close()
    print(msg)

# ---------------- keyboard + gestures ----------------
def keyboard():
    while True:
        s = input().strip()
        if s == "send": start_carry(); continue
        if s == "cancel": stop_carry("Cancelled"); continue
        if s == "forget": trusted.clear(); save_trusted(); print("Forgot all trusted devices."); continue
        m = re.match(r"^(\d+\.\d+\.\d+\.\d+):(\d+)/(\w+)$", s)
        if m: carrying["manual"] = (m.group(1), int(m.group(2)), m.group(3), "manual")
        elif s: print("Unknown. Use: (empty), send, cancel, or a code like 192.168.1.7:43021/ab12cd34"); continue
        print("Fetching…"); fetch()
threading.Thread(target=keyboard, daemon=True).start()

def classify(lm, w, h):
    d = lambda a, b: hypot((lm[a].x - lm[b].x) * w, (lm[a].y - lm[b].y) * h)
    palm = max(d(0, 9), 1e-6)
    s = sum(d(t, 0) / palm for t in (8, 12, 16, 20)) / 4
    return "open" if s > OPEN_MIN else "fist" if s < FIST_MAX else None

ServiceBrowser(zc, SVC_PHONE, Listener())
landmarker = vision.HandLandmarker.create_from_options(vision.HandLandmarkerOptions(
    base_options=mpt.BaseOptions(model_asset_path=MODEL),
    running_mode=vision.RunningMode.VIDEO, num_hands=1, min_hand_detection_confidence=0.4))
cam = cv2.VideoCapture(0)
cand = conf = None; since = last_fist = last_open = 0
print("👀 Watching webcam.")
print("   Receive: fist -> open palm.  Send: Ctrl+C files in Explorer, then open palm -> fist.")
print("   Or type: send / cancel / (ENTER to receive) / a phone code. Ctrl+C here to quit.")
try:
    while True:
        ok, frame = cam.read()
        if not ok: time.sleep(0.2); continue
        h, w = frame.shape[:2]
        img = mp.Image(image_format=mp.ImageFormat.SRGB, data=cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
        res = landmarker.detect_for_video(img, int(time.monotonic() * 1000))
        g = classify(res.hand_landmarks[0], w, h) if res.hand_landmarks else None
        now = time.time()
        if g != cand: cand, since = g, now
        if cand and now - since >= 0.5 and cand != conf:
            conf = cand; print("Hand:", conf)
            if conf == "open" and now - last_fist < 5 and carrying: threading.Thread(target=fetch).start()
            if conf == "fist" and now - last_open < 5: start_carry(quiet=True)
        if conf == "fist": last_fist = now
        if conf == "open": last_open = now
        if not cand and now - since > 1.5: conf = None
        if out["srv"] and now > out["until"]: stop_carry("Time ran out")
        time.sleep(0.03)
except KeyboardInterrupt:
    if out["srv"]: stop_carry("Stopped")
