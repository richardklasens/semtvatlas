# SEM-TV Atlas – Android kiosk-app

Toont de player van de server in een WebView en maakt het paneel op afstand beheerbaar.

## Functies

| Functie | Hoe |
|---|---|
| Fullscreen kiosk | immersive mode, back geblokkeerd; als **Device Owner** ook lock task, geen statusbalk/keyguard, vaste HOME-app |
| **Touch uitschakelen na x seconden** | transparante laag boven de WebView vangt alle aanraking op na x s inactiviteit (instelbaar per scherm / standaard in *Instellingen*; modus *altijd aan / na x s / altijd uit*) |
| Touch weer aan | op afstand (*Touch aan*) of ter plekke: **3 s ingedrukt houden in de linkeronderhoek** → pincode → menu |
| Op afstand bedienen | *Herladen, Herstart app, Herstart paneel, Scherm aan/uit, Touch aan/uit, Cache wissen, Helderheid* – per scherm, selectie of groep |
| Scherm automatisch aan/uit | tijdschema per scherm in het portaal (server stuurt de commando's) |
| Status in het portaal | online, batterij, touch-status, appversie, Device Owner ja/nee, laatst uitgevoerd commando |
| Zelfherstel | foreground service + watchdog, herstart na reboot/update, WebView-crash opvang, nieuwe poging als de server weg is |

> Android biedt geen API om de touch-hardware uit te zetten; de overlay werkt op elk paneel en is niet te omzeilen
> zonder de PIN. Heeft het Ilyama-paneel een eigen OSD-optie voor touch, dan kan die er naast gebruikt worden.

## Bouwen

1. Open de map `android/` in **Android Studio** (Koala of nieuwer, JDK 17). Gradle-sync haalt alles op.
2. *Build → Build APK(s)* → `app/build/outputs/apk/`. (Voor de release-APK: *Build → Generate Signed Bundle / APK*.)

> De code is geschreven zonder Android SDK en is **nog niet gecompileerd of op een paneel getest**. Verwacht bij de
> eerste build hooguit kleine compilerfoutjes; die zijn snel op te lossen.

## Installeren op een Ilyama-paneel

1. Installeer de APK (`adb install app-release.apk`, of download via `http://<server>:8080/downloads/…`).
2. Open de app: vul **serveradres** (`http://<server>:8080`), **scherm-ID** (zoals in het portaal) en eventueel de
   device key in. Het paneel meldt zich direct aan in het portaal.
3. **Device Owner maken** (nodig voor *Herstart paneel*, *Scherm uit*, volledige kiosk). Eenmalig per paneel, via USB-debugging,
   **op een paneel zonder Google-account**:
   ```
   adb shell dpm set-device-owner nl.atlas.semtv/.AdminReceiver
   ```
   Lukt dat niet ("already several accounts"), verwijder dan accounts of doe een fabrieksreset en voer het commando uit
   vóór de eerste setup-wizard is afgerond. Daarna de app herstarten.
4. Zet in Android-instellingen de batterij-optimalisatie voor de app uit (of laat Device Owner dat regelen).

Zonder Device Owner werken wel: weergave, touch-lock, herladen, herstart app, helderheid. *Herstart paneel* werkt dan
alleen op een gerootte tablet; *Scherm uit* alleen als de app device admin is.

## Instellingen die de server meestuurt

Touch-modus, touch-timeout, helderheid en admin-pincode komen met elke heartbeat (15 s) binnen en worden lokaal bewaard,
zodat het paneel ook zonder server correct opstart.
