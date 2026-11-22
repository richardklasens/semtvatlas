# SEM-TV Atlas op een Ubuntu-computer

Een Ubuntu-computer achter een scherm draait dezelfde speler als de panelen, in Chromium (kiosk-modus), plus een kleine
**agent** (alleen Python 3, geen extra pakketten) die in het portaal verschijnt en opdrachten uitvoert.

## Installeren
Maak het scherm eerst aan in het portaal (Schermen → + Scherm) en onthoud het scherm-ID. Daarna op de Ubuntu-computer,
ingelogd als het account dat het scherm toont:

    curl -fsSL http://SERVER/ubuntu/install.sh | sudo bash -s -- http://SERVER SCHERM-ID [DEVICE_KEY]
    sudo reboot

Het script installeert de agent als systeemdienst, zet Chromium in kiosk-modus op automatisch starten (en weer opnieuw
starten als hij sluit), schakelt schermbeveiliging/slaapstand/vergrendeling uit en zet automatisch inloggen (GDM) aan.

## Wat er vanuit het portaal kan
| Knop | Effect op Ubuntu |
|---|---|
| Herladen | speler laadt opnieuw |
| Herstart app | Chromium wordt opnieuw gestart |
| Herstart paneel | computer herstart |
| Scherm uit / aan | zwart scherm (alle desktops); op X11 ook echt scherm uit (DPMS) |
| Cache wissen | browsercache wissen + Chromium opnieuw starten |
| Update app | agent haalt de nieuwste agent van de server (`/ubuntu/semtv-agent.py`) en herstart zichzelf |
| Touch aan/uit, helderheid | niet van toepassing |

De agent-versie op de server en per scherm staat in het portaal (tabblad Schermen); een scherm dat achterloopt krijgt
"↑ update beschikbaar". De speler zelf (het uiterlijk) werkt zich bij met een redeploy van de server plus **Herladen**.

## Problemen zoeken
* Portaal → **Diagnose**: laat zien of de speler en de agent contact hebben en waarom niet.
* Op de computer: `journalctl -u semtv-agent -f`
* Niet-geïnstalleerd/andere map: instellingen staan in `/etc/semtv/config.json`.

## Opmerkingen
* Onder **Wayland** (standaard op Ubuntu 22.04+) kan een dienst het scherm niet echt uitzetten; "Scherm uit" is dan een
  zwarte laag over de speler (hetzelfde als bij de Android-panelen). Kies bij het inloggen "Ubuntu on Xorg" voor echt DPMS.
* Chromium komt op Ubuntu als snap. De installer detecteert Chromium/Chrome en installeert anders de snap.
