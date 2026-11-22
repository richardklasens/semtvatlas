#!/usr/bin/env python3
"""
SEM-TV Atlas — Ubuntu-agent (zonder externe pakketten, alleen Python 3)

Doet op een Ubuntu-computer wat de Android-app op een paneel doet:
  * stuurt elke 15 s een heartbeat naar de server (komt in het portaal als scherm online)
  * voert opdrachten uit het portaal uit: herladen / herstart app (= browser herstarten),
    herstart computer, scherm uit/aan, cache wissen en "Update app" (werkt deze agent zelf bij)
  * geeft de kiosk-browser via 127.0.0.1:8765 door of het scherm 'uit' (zwart) moet en of hij moet herladen

Draait als systeemdienst (root) via install.sh. Instellingen: /etc/semtv/config.json
"""
import json, os, re, shutil, subprocess, sys, threading, time, urllib.request, urllib.error, platform
from http.server import BaseHTTPRequestHandler, HTTPServer

AGENT_VERSION = "1.0.0"
INTERVAL = 15
CONFIG_FILE = os.environ.get("SEMTV_CONFIG", "/etc/semtv/config.json")
DRYRUN = os.environ.get("SEMTV_DRYRUN") == "1"        # testen: opdrachten niet echt uitvoeren
LOCAL_PORT = int(os.environ.get("SEMTV_LOCAL_PORT", "8765"))
SELF_PATH = os.path.abspath(__file__)

state = {"off": False, "reload": 0, "lastResult": "", "hbFails": 0, "hbError": "", "update": "", "started": time.time()}
cfg = {}


def log(msg):
    print("[%s] %s" % (time.strftime("%Y-%m-%d %H:%M:%S"), msg), flush=True)


def load_config():
    global cfg
    with open(CONFIG_FILE) as f:
        cfg = json.load(f)
    cfg["server"] = cfg["server"].rstrip("/")


def run(cmd, user=None, env=None, timeout=20):
    """Voer een systeemopdracht uit (of log hem bij SEMTV_DRYRUN=1)."""
    if user and os.geteuid() == 0:
        cmd = ["runuser", "-u", user, "--"] + (["env"] + ["%s=%s" % kv for kv in env.items()] if env else []) + cmd
    if DRYRUN:
        log("DRYRUN: " + " ".join(cmd)); return 0, ""
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return p.returncode, (p.stdout + p.stderr).strip()
    except Exception as e:
        return 1, str(e)


def http(method, path, body=None, raw=False, timeout=15):
    req = urllib.request.Request(cfg["server"] + path, method=method)
    if cfg.get("deviceKey"):
        req.add_header("X-Device-Key", cfg["deviceKey"])
    data = None
    if body is not None:
        data = json.dumps(body).encode(); req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, data=data, timeout=timeout) as r:
        content = r.read()
    return content if raw else json.loads(content)


def reason(e):
    if isinstance(e, urllib.error.HTTPError):
        return {401: "HTTP 401: device key ontbreekt of klopt niet", 404: "HTTP 404: scherm-ID onbekend op de server"}.get(e.code, "HTTP %d van de server" % e.code)
    if isinstance(e, urllib.error.URLError):
        return "geen verbinding met de server: %s" % e.reason
    return "%s: %s" % (type(e).__name__, e)


# ------------------------------------------------------------------ browser / scherm
def kiosk_user():
    return cfg.get("kioskUser") or None


def browser_alive():
    return run(["pgrep", "-f", "semtv-kiosk-profile"])[0] == 0


def restart_browser():
    # het kiosk-script (semtv-kiosk.sh) draait de browser in een lus: afsluiten = opnieuw starten
    run(["pkill", "-f", "semtv-kiosk-profile"])


def clear_cache():
    home = os.path.expanduser("~" + (kiosk_user() or "root"))
    for sub in ("semtv-kiosk-profile/Default/Cache", "semtv-kiosk-profile/Default/Code Cache"):
        shutil.rmtree(os.path.join(home, sub), ignore_errors=True)
    restart_browser()


def dpms(on):
    """Echt scherm uit/aan (alleen X11; onder Wayland doet de zwarte laag in de speler het werk)."""
    u = kiosk_user()
    if not u:
        return
    env = {"DISPLAY": cfg.get("display", ":0"), "XAUTHORITY": "/home/%s/.Xauthority" % u}
    run(["xset", "dpms", "force", "on" if on else "off"], user=u, env=env)


def execute(ctype, params):
    state["lastResult"] = "%s @ %s" % (ctype, time.strftime("%H:%M:%S"))
    if ctype in ("reload", "restart_app"):
        state["reload"] += 1                       # de speler laadt zichzelf opnieuw (via de lokale status)
        if ctype == "restart_app":
            threading.Timer(1.0, restart_browser).start()   # hele browser opnieuw starten
    elif ctype == "clear_cache":
        clear_cache()
    elif ctype == "screen_off":
        state["off"] = True; dpms(False)
    elif ctype == "screen_on":
        state["off"] = False; dpms(True); state["reload"] += 1
    elif ctype == "reboot":
        run(["systemctl", "reboot"])
    elif ctype == "update_app":
        threading.Thread(target=self_update, daemon=True).start()
    # touch_lock / touch_unlock / set_brightness: niet van toepassing


# ------------------------------------------------------------------ zelf bijwerken
def vtuple(v):
    return tuple(int(x) for x in re.findall(r"\d+", v or "0"))


def self_update():
    try:
        state["update"] = "agent downloaden van de server…"
        new = http("GET", "/ubuntu/semtv-agent.py", raw=True, timeout=30).decode()
        m = re.search(r'^AGENT_VERSION = "([^"]+)"', new, re.M)
        if not m:
            state["update"] = "mislukt: gedownload bestand is geen geldige agent"; return
        if vtuple(m.group(1)) <= vtuple(AGENT_VERSION):
            state["update"] = "niet nodig: server heeft %s, hier draait %s" % (m.group(1), AGENT_VERSION); return
        tmp = SELF_PATH + ".new"
        with open(tmp, "w") as f:
            f.write(new)
        rc, out = run([sys.executable, "-m", "py_compile", tmp])
        if rc != 0:
            os.remove(tmp); state["update"] = "mislukt: nieuwe agent is ongeldig (%s)" % out; return
        os.chmod(tmp, 0o755)
        if DRYRUN:
            state["update"] = "DRYRUN: zou bijwerken naar %s" % m.group(1); os.remove(tmp); return
        os.replace(tmp, SELF_PATH)
        state["update"] = "bijgewerkt naar %s; agent herstart" % m.group(1)
        log(state["update"])
        time.sleep(2)
        os._exit(0)          # systemd (Restart=always) start de nieuwe versie
    except Exception as e:
        state["update"] = "mislukt: " + reason(e)


# ------------------------------------------------------------------ heartbeat
def local_ip():
    rc, out = run(["hostname", "-I"])
    return out.split()[0] if rc == 0 and out else ""


def os_name():
    try:
        with open("/etc/os-release") as f:
            return re.search(r'PRETTY_NAME="?([^"\n]+)', f.read()).group(1)
    except Exception:
        return platform.platform()


def payload(ack=None):
    try:
        up = int(float(open("/proc/uptime").read().split()[0]) / 60)
    except Exception:
        up = 0
    return {"kind": "linux", "appVersion": AGENT_VERSION, "screenOn": not state["off"], "activityAlive": browser_alive(),
            "lastResult": state["lastResult"], "localIp": local_ip(), "model": os_name(), "uptimeMin": up,
            "hbFails": state["hbFails"], "hbError": state["hbError"], "updateStatus": state["update"], "ackIds": ack or []}


def beat():
    sid = urllib.request.quote(cfg["screenId"], safe="")
    try:
        r = http("POST", "/api/device/%s/heartbeat" % sid, payload())
    except Exception as e:
        state["hbFails"] += 1; state["hbError"] = reason(e); log("heartbeat mislukt: " + state["hbError"]); return
    state["hbFails"] = 0
    for c in r.get("commands", []):
        try:     # eerst bevestigen, dan uitvoeren (zoals de Android-app)
            http("POST", "/api/device/%s/heartbeat" % sid, payload([c["id"]]))
        except Exception:
            pass
        log("commando: " + c["type"])
        execute(c["type"], c.get("params") or {})


# ------------------------------------------------------------------ lokale status voor de kiosk-browser
class Local(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def do_OPTIONS(self):
        self.send_response(204); self._cors(); self.end_headers()

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Private-Network", "true")
        self.send_header("Access-Control-Allow-Headers", "*")

    def do_GET(self):
        body = json.dumps({"off": state["off"], "reload": state["reload"], "version": AGENT_VERSION}).encode()
        self.send_response(200); self._cors(); self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store"); self.end_headers(); self.wfile.write(body)


def main():
    load_config()
    log("SEM-TV agent %s gestart (server %s, scherm %s)" % (AGENT_VERSION, cfg["server"], cfg["screenId"]))
    srv = HTTPServer(("127.0.0.1", LOCAL_PORT), Local)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    while True:
        try:
            beat()
        except Exception as e:
            log("fout in heartbeat-lus: %s" % e)
        time.sleep(INTERVAL if not os.environ.get("SEMTV_FAST") else 2)


if __name__ == "__main__":
    main()
