# Deployment – SEM-TV Atlas server

De server (Node 22, geen npm-installatie nodig) draait in één container. Alle instellingen, schermen en berichten
staan in het volume `/data` (`db.json`) – maak daar een backup van.

## 1. Portainer (aanbevolen)

1. **Stacks → Add stack → Repository** (of *Web editor* met `docker-compose.yml`).
2. Repository: `https://github.com/richardklasens/semtvatlas`, compose path `docker-compose.yml`.
3. Environment variables:
   | Variabele | Betekenis |
   |---|---|
   | `ADMIN_PASSWORD` | wachtwoord voor het beheerportaal (**verplicht aanpassen**) |
   | `PORT` | optioneel; poort op de server (standaard 8080). Kies een andere als 8080 al bezet is |
   | `DEVICE_KEY` | optioneel; als ingevuld moeten de Android-apps dezelfde sleutel meesturen |
   | `SEM_MOCK` | `1` = testdata zonder SEM-verbinding (handig om eerst alles uit te proberen) |
4. Deploy. Open `http://<server>:8080/admin/`.

Lokaal: `ADMIN_PASSWORD=geheim docker compose up -d --build`.

## 2. Eerste keer inrichten

1. **SEM-status → API-test**: vul een facility-ID in (bijv. `22`) en klik *Test ophalen*. Je ziet de ruwe HTML die SEM
   teruggeeft en wat de server eruit haalt. De server stuurt SEM een eigen template mee (zelfde `jsonTemplate`-aanroep
   als de oude pagina's) die per reservering een tabelrij met `WelcomeText`, `ReservationName`, `FacilityWelcomeText`
   en `FacilityName` oplevert. **Dit is nog niet tegen de echte SEM getest** – controleer dit als eerste. Komt er niets
   uit terwijl er wel een reservering is, stuur me dan de ruwe HTML en ik pas de parser aan.
2. **Instellingen**: SEM-URL/company code (voorgevuld met de waarden uit de oude pagina's), voettekst, standaard
   touch-timeout, beheer-pincode.
3. **Schermen**: bij de eerste start zijn de 17 kleedkamers en het overzicht aangemaakt (facility-ID's overgenomen uit
   `html/`). Per scherm stel je de *slides* in die achter elkaar worden getoond:

   | Slide | Toont |
   |---|---|
   | Kleedkamer | reservering van één facility (of "niet in gebruik") |
   | Overzicht | alle reserveringen van meerdere facilities |
   | Mededelingen | actieve berichten uit het tabblad *Berichten* (doel per scherm of groep, periode) |
   | Klok | tijd en datum |
   | Weer | huidig weer en 3-daagse verwachting (Open-Meteo; plaats/coördinaten bij Instellingen) |
   | Tekst | huisregels, route, vrije tekst met optionele afbeelding |

## 3. Schermen koppelen

* **Android TV / browser**: open `http://<server>:8080/player/?screen=<scherm-id>` (bijv. `kleedkamer-0-1`) en zet de
  browser op volledig scherm. Zonder `screen=` krijg je een keuzelijst. De pagina meldt zich ook aan het beheerportaal
  (status online, en *Herladen* op afstand werkt).
* **Ilyama-panels**: installeer de Android-app, zie [android/README.md](android/README.md).

## 4. APK beschikbaar maken (optioneel)

Open het beheerportaal → **Instellingen → Android-app** en upload de gebouwde apk (te downloaden via GitHub → Actions → Artifacts).
De server bewaart hem in het data-volume en serveert hem op `http://<server>:<poort>/downloads/app-release.apk`; open dat adres in de
browser van het paneel om te installeren (geen USB nodig, alleen "installeren uit onbekende bronnen" toestaan).

## Beveiliging

* Draai de server alleen op het interne netwerk, of achter een reverse proxy met HTTPS.
* Wijzig `ADMIN_PASSWORD`. Gebruik `DEVICE_KEY` als je de app-API niet open wilt hebben.
* De player-API (`/api/player/...`) is bewust zonder inlog, want de schermen tonen toch publieke informatie.

## Troubleshooting

* Logs: Portainer → Containers → `semtvatlas` → Logs.
* Scherm toont "niet in gebruik" terwijl er een reservering is → **SEM-status** bekijken (fout per facility).
* SEM tijdelijk onbereikbaar → schermen blijven de laatst bekende gegevens tonen; een rood puntje rechtsonder
  verschijnt als de server meer dan 2 minuten niet bereikbaar is.
