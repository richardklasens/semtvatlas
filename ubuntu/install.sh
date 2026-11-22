#!/usr/bin/env bash
# SEM-TV Atlas — installatie op een Ubuntu-computer die als scherm dient.
#
# Gebruik (als root, vanaf de computer zelf):
#   curl -fsSL http://SERVER/ubuntu/install.sh | sudo bash -s -- http://SERVER SCHERM-ID [DEVICE_KEY] [GEBRUIKER]
# of na het downloaden:  sudo bash install.sh http://SERVER SCHERM-ID [DEVICE_KEY] [GEBRUIKER]
#
# GEBRUIKER = het account dat inlogt en de browser toont (standaard: degene die sudo gebruikte).
set -euo pipefail

SERVER="${1:-}"; SCREEN="${2:-}"; KEY="${3:-}"; KUSER="${4:-${SUDO_USER:-}}"
[ "$(id -u)" = 0 ] || { echo "Draai dit met sudo."; exit 1; }
[ -n "$SERVER" ] && [ -n "$SCREEN" ] || { echo "Gebruik: sudo bash install.sh http://SERVER SCHERM-ID [DEVICE_KEY] [GEBRUIKER]"; exit 1; }
[ -n "$KUSER" ] && id "$KUSER" >/dev/null 2>&1 || { echo "Geef het gebruikersaccount op als 4e argument."; exit 1; }
SERVER="${SERVER%/}"
KHOME="$(getent passwd "$KUSER" | cut -d: -f6)"
command -v python3 >/dev/null || apt-get install -y python3

echo "==> Browser zoeken"
BROWSER=""
for b in chromium chromium-browser google-chrome google-chrome-stable; do command -v "$b" >/dev/null 2>&1 && { BROWSER="$(command -v $b)"; break; }; done
if [ -z "$BROWSER" ]; then
  echo "Geen Chromium/Chrome gevonden; Chromium installeren (snap)…"
  snap install chromium
  BROWSER="$(command -v chromium || echo /snap/bin/chromium)"
fi
echo "    $BROWSER"

echo "==> Agent installeren"
mkdir -p /opt/semtv /etc/semtv
if ! curl -fsSL "$SERVER/ubuntu/semtv-agent.py" -o /opt/semtv/semtv-agent.py; then
  [ -f "$(dirname "$0")/semtv-agent.py" ] && cp "$(dirname "$0")/semtv-agent.py" /opt/semtv/semtv-agent.py || { echo "Kon de agent niet downloaden van $SERVER"; exit 1; }
fi
chmod 755 /opt/semtv/semtv-agent.py
python3 - "$SERVER" "$SCREEN" "$KEY" "$KUSER" <<'PY'
import json, sys
s, sid, key, u = sys.argv[1:5]
json.dump({"server": s, "screenId": sid, "deviceKey": key, "kioskUser": u, "display": ":0"}, open("/etc/semtv/config.json", "w"), indent=2)
PY
chmod 600 /etc/semtv/config.json

cat > /etc/systemd/system/semtv-agent.service <<UNIT
[Unit]
Description=SEM-TV Atlas agent
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 /opt/semtv/semtv-agent.py
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

echo "==> Kiosk-browser instellen voor $KUSER"
URL="$SERVER/player/?screen=$(python3 -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1],safe=""))' "$SCREEN")&app=1&agent=1"
cat > /opt/semtv/semtv-kiosk.sh <<KIOSK
#!/usr/bin/env bash
# Start de speler schermvullend en start hem opnieuw als hij sluit of crasht.
xset s off -dpms s noblank 2>/dev/null || true       # (X11) geen schermbeveiliging
while true; do
  "$BROWSER" --kiosk --noerrdialogs --disable-infobars --no-first-run --no-default-browser-check \\
    --disable-session-crashed-bubble --disable-translate --password-store=basic \\
    --autoplay-policy=no-user-gesture-required --overscroll-history-navigation=0 \\
    --disable-features=BlockInsecurePrivateNetworkRequests,PrivateNetworkAccessSendPreflights,Translate \\
    --user-data-dir="$KHOME/semtv-kiosk-profile" "$URL"
  sleep 3
done
KIOSK
chmod 755 /opt/semtv/semtv-kiosk.sh
mkdir -p "$KHOME/.config/autostart"
cat > "$KHOME/.config/autostart/semtv-kiosk.desktop" <<DESK
[Desktop Entry]
Type=Application
Name=SEM-TV kiosk
Exec=/opt/semtv/semtv-kiosk.sh
X-GNOME-Autostart-enabled=true
DESK
chown -R "$KUSER":"$KUSER" "$KHOME/.config/autostart"

echo "==> Schermbeveiliging, slaapstand en vergrendeling uitzetten"
UID_K="$(id -u "$KUSER")"
GS() { sudo -u "$KUSER" env DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$UID_K/bus" gsettings set "$@" 2>/dev/null || echo "    (overgeslagen: $1 $2 — geen actieve sessie; zet dit later handmatig)"; }
GS org.gnome.desktop.session idle-delay 0
GS org.gnome.desktop.screensaver lock-enabled false
GS org.gnome.desktop.screensaver idle-activation-enabled false
GS org.gnome.settings-daemon.plugins.power sleep-inactive-ac-type "'nothing'"
GS org.gnome.settings-daemon.plugins.power sleep-inactive-battery-type "'nothing'"
GS org.gnome.desktop.notifications show-banners false
systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target >/dev/null 2>&1 || true

echo "==> Automatisch inloggen (GDM)"
if [ -f /etc/gdm3/custom.conf ]; then
  cp -n /etc/gdm3/custom.conf /etc/gdm3/custom.conf.semtv-backup
  sed -i '/^AutomaticLoginEnable/d;/^AutomaticLogin=/d' /etc/gdm3/custom.conf
  sed -i "/^\[daemon\]/a AutomaticLoginEnable=true\nAutomaticLogin=$KUSER" /etc/gdm3/custom.conf
  echo "    ingesteld voor $KUSER (backup: /etc/gdm3/custom.conf.semtv-backup)"
else
  echo "    GDM niet gevonden: stel automatisch inloggen zelf in voor je desktopomgeving."
fi

systemctl daemon-reload
systemctl enable --now semtv-agent.service
echo
echo "Klaar. Herstart de computer (sudo reboot): hij start dan automatisch de speler."
echo "In het portaal verschijnt scherm '$SCREEN' binnen 15 seconden als online (Ubuntu-agent)."
echo "Logboek van de agent: journalctl -u semtv-agent -f"
