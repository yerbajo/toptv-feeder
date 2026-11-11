[English](install.md) · [Español](install.es.md) · [Français](install.fr.md) · Deutsch

# TV Feeder auf einem Android-TV-Gerät installieren

Diese Anleitung installiert die App auf einem echten Gerät: einem Fernseher mit
Android TV oder Google TV, einem TV-Stick oder einer Set-Top-Box. Sie brauchen eine
APK, eine Installation und eine Synchronisation. Alles Weitere zur App steht in
[feeder.de.md](feeder.de.md).

## 1. Was Sie brauchen

| Punkt | Detail |
|---|---|
| Gerät | Android TV oder Google TV mit **Android 9 (API 28) oder neuer**: eine Mi Box S, ein Stick im Chromecast-Stil oder jede Box mit ADB oder einem Dateimanager |
| Apps | **Netflix** und **Prime Video** auf demselben Gerät installiert, damit die Karten eine App zum Öffnen haben |
| Netzwerk | das Gerät während der Installation im selben Netzwerk wie Ihr PC. Danach braucht es nur HTTPS zu `www.netflix.com` und `apis.justwatch.com` |
| PC, nur zum Bauen | das Android SDK **build-tools 34.0.0** und **platforms/android-34**; `javac`, `keytool` und `zip` im `PATH` |

Ein bereits gebautes `app/build/tvfeeder.apk` braucht gar keinen PC.

## 2. APK bauen

```bash
bash tools/build-apk.sh
```

Das Skript sucht das SDK an zwei festen Pfaden und akzeptiert Overrides, wenn Ihres
woanders liegt:

```bash
TVFEEDER_BUILD_TOOLS=/opt/android/build-tools/34.0.0 \
TVFEEDER_ANDROID_JAR=/opt/android/platforms/android-34/android.jar \
bash tools/build-apk.sh
```

Das Ergebnis ist `app/build/tvfeeder.apk`. Bevor das Skript Erfolg meldet, prüft es
die gebaute Datei: `aapt2 dump badging` auf den Paketnamen `com.alberto.tvfeeder`,
`sdkVersion` und `targetSdkVersion` 28, die Berechtigungen `INTERNET`,
`READ_TV_LISTINGS`, `RECEIVE_BOOT_COMPLETED`, `READ_EPG_DATA` und `WRITE_EPG_DATA`
sowie eine launchable und leanback-launchable `MainActivity`; es lehnt eine APK ab,
die `ACCESS_ALL_EPG_DATA` anfordert; es prüft im Manifest `BootReceiver` und die
Filter `BOOT_COMPLETED` und `QUICKBOOT_POWERON`; und es führt `apksigner verify`
aus. Am Ende gibt es den `sha256` der APK aus.

## 3. Über ADB installieren

Aktivieren Sie zuerst ADB auf dem Gerät: `Settings > Device Preferences > About`,
drücken Sie siebenmal OK auf **Build**, öffnen Sie `Developer options`, schalten Sie
**USB debugging** (oder **Network debugging**) ein und bestätigen Sie die
RSA-Abfrage am Fernseher.

```bash
adb connect <BOX_IP>:5555
adb install -r app/build/tvfeeder.apk
adb shell am start -n com.alberto.tvfeeder/.MainActivity
```

Bleibt der Port geschlossen, hat die Box kein Netzwerk-Debugging und braucht zuerst
eine USB-Verbindung: am PC anstecken, die Abfrage bestätigen, `adb tcpip 5555`
ausführen, wieder abstecken.

## 4. Ohne ADB installieren

Kopieren Sie `app/build/tvfeeder.apk` auf einen USB-Stick und stecken Sie ihn am
Fernseher an, oder stellen Sie die Datei vom PC bereit:

```bash
cd app/build && python3 -m http.server 8000
```

Öffnen Sie am Fernseher `http://<PC_IP>:8000/tvfeeder.apk` mit dem Dateimanager
oder mit der App **Downloader**, erlauben Sie unbekannte Quellen für diese App
(`Settings > Security & restrictions > Unknown sources`) und installieren Sie.

## 5. Erster Lauf

Öffnen Sie **TV Feeder**, lassen Sie das URL-Feld leer, drücken Sie auf **Sync** und
bestätigen Sie den Dialog für `READ_TV_LISTINGS`. Der Bericht lautet:

```
OK 2026-11-11 20:25 · Published 4 rows, 40 items, 0 without artwork, 0 errors
Source: on-box sources (Netflix Tudum + JustWatch ES)
```

![Die Activity nach einer Synchronisation](img/app-sync.jpg)

Die Zahlen bedeuten:

| Wert | Bedeutung |
|---|---|
| `4 rows` | die zwei Netflix-Zeilen und die zwei Prime-Video-Zeilen wurden veröffentlicht |
| `40 items` | zehn Karten pro Zeile; eine Karte, deren Link zu nichts auflöste, wird vor dem Veröffentlichen verworfen, der Wert kann also niedriger sein |
| `0 without artwork` | Karten, deren Poster oder Vorschaubild leer zurückkam; sie werden trotzdem veröffentlicht, und der Wert zeigt beeinträchtigte Grafik an |
| `0 errors` | Zeilen, die als Ganzes fehlschlugen; jeder Wert ungleich null nennt die Zeile unter der Zusammenfassung |

Unter der Zusammenfassung trägt jede Zeilenzeile ihre Woche und die `opens:`-URI
ihrer ersten Karte: Das ist der String, den der Launcher auslöst.

## 6. Die Zeilen anzeigen

Die Karten erscheinen in der Reihenfolge des Rankings: Die erste Karte ist Rang 1.
Die App veröffentlicht die sortierten Karten von hinten nach vorn, Rang 1 wird also
zuletzt eingefügt und trägt das größte Vorschaugewicht für Launcher, die
`COLUMN_WEIGHT` beachten; auf der Mi Box zeigte die Zeile Netflix · Top series
España Rang 1 (*A Couple of Lies*) bis Rang 7 in dieser Reihenfolge, und die Zeile
Prime · Top películas España begann mit ihrem ersten veröffentlichten Titel
(*Proyecto Salvación*). Der Launcher entscheidet weiterhin, wie viele der zehn
Karten auf den Bildschirm passen, und ein Launcher, der weder Einfügereihenfolge
noch Gewicht beachtet, kann sie noch umsortieren.

Aktivieren Sie in **Projectivy** zuerst dessen eigene Kanäle:
`Categorías / Canales → Habilitar canales`. Die Feeder-Zeilen erscheinen dann neben
der App-Zeile und *Continuar viendo*:

![Projectivy-Startbildschirm](img/launcher-home.jpg)

Launcher, die Vorschaukanäle lesen, zeigen die Zeilen ohne weitere Einstellung.
Ignoriert Ihr Launcher Vorschaukanäle, drücken Sie auf **Destination**, bis dort
**Destination: Watch Next** steht; das veröffentlicht dieselben Zeilen in Watch
Next. Drücken Sie danach erneut auf **Sync**, weil der Wechsel die andere Tabelle
leert.

![Die vier Zeilen in Projectivy](img/launcher-rows.jpg)

## 7. Der Alltag

Die App synchronisiert beim Start, alle 24 Stunden und wenn Sie auf **Sync**
drücken. Eine fehlgeschlagene Aktualisierung behält die bereits veröffentlichten
Zeilen, und der Bericht sagt das auch: Die vorherigen Zeilen werden nicht gelöscht,
und das letzte gültige Dokument bleibt in `files/last_good.json`.

## 8. Fehlersuche

| Symptom | Ursache | Was zu tun ist |
|---|---|---|
| Gar keine Zeilen | der Launcher verbirgt die Kanäle, oder **Destination** zielt auf die andere Tabelle | aktivieren Sie die eigenen Kanäle des Launchers (Projectivy: `Categorías / Canales → Habilitar canales`), oder wechseln Sie **Destination** und drücken Sie **Sync** |
| Der Bericht ist OK, aber die Karten tun nichts | die Ziel-App fehlt, oder die Karte wurde verworfen, weil ihr Link auf dem Gerät nicht aufgelöst wurde | installieren Sie Netflix oder Prime Video; der Bericht zählt verworfene Karten als `N without a resolvable link` |
| `FAILED refresh of on-box sources …` | keine HTTPS-Route zu `www.netflix.com` oder `apis.justwatch.com`; kurze Edge-Ausfälle kommen vor | in der App gibt es nichts zu reparieren: die alten Zeilen bleiben veröffentlicht. Versuchen Sie es später erneut mit **Sync** |
| **Diagnostics** zeigt 0 items pro Kanal | die Kanäle existieren, aber die Programme wurden nicht veröffentlicht, oder die angezeigte Tabelle gehört zur anderen **Destination** | führen Sie **Sync** mit der **Destination** aus, die der Launcher zeigt, und lesen Sie **Diagnostics** erneut |
| `SecurityException` oder ein Cleartext-Fehler im Bericht | `READ_TV_LISTINGS` wurde nicht erteilt, oder die externe top.json-URL ist klares `http` | erteilen Sie die Berechtigung im Dialog bei der nächsten Synchronisation; nutzen Sie für die optionale externe URL möglichst `https` |

Die Schaltfläche **Diagnostics** gibt jede Provider-Zeile aus, die sie lesen kann,
mit `package_name`, Typ und `searchable`-Kennzeichen, die Items pro Kanal und die
Gesamtzahl der Watch-Next-Items, woran sich eine leere Zeile von einer fehlenden
unterscheiden lässt.

## 9. Deinstallieren

```bash
adb uninstall com.alberto.tvfeeder
```

Die Deinstallation entfernt die App, ihre Kanäle und das zwischengespeicherte
Dokument. Die Zeilen verschwinden beim nächsten Aktualisieren aus dem Launcher.
