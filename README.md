# SEM-TV Atlas

Eigen narrowcasting-systeem voor ATLAS Theater: ~20 schermen (Ilyama-touchpanels op Android, plus Android TV's met
een browser), beheerd vanuit één webportaal en **onafhankelijk van de weergave van Smart Event Manager (SEM)**.
SEM blijft alleen de *databron* voor reserveringen.

```
 SEM API ──► server (Node, Docker) ──► /player  (HTML: Android TV-browser of WebView)
              │  cache + schermconfig        ▲
              │  berichten, opdrachten       │ heartbeat (15 s) + opdrachten
              └──► /admin (beheerportaal) ◄──┴── Android kiosk-app op de Ilyama-panels
```

| Map        | Inhoud |
|------------|--------|
| `server/`  | Node-server (geen npm-dependencies): SEM-adapter + cache, schermconfig, berichten, opdrachten, API |
| `player/`  | Weergave-pagina (`/player/?screen=<id>`): kleedkamer, overzicht, mededelingen, klok, weer, tekst/huisregels/route |
| `admin/`   | Beheerportaal (`/admin/`) |
| `android/` | Kiosk-app (Kotlin): WebView, touch-lock na x seconden, heartbeat, herstart/herlaad/scherm aan-uit op afstand |
| `html/`    | De oorspronkelijke SEM-TV pagina's (bewaard als referentie, bereikbaar onder `/legacy/`) |

Zie [DEPLOYMENT.md](DEPLOYMENT.md) voor installatie en [android/README.md](android/README.md) voor de app.
