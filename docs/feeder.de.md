[English](feeder.md) · [Español](feeder.es.md) · [Français](feeder.fr.md) · Deutsch

# TV feeder: sortierte Zeilen, die die Apps nicht liefern

Der Launcher ab Werk und Projectivy können nur Inhalte zeigen, die eine App im
**TV Provider** veröffentlicht. Netflix veröffentlicht *continue watching* und
einen kuratierten Vorschaukanal; Prime Video veröffentlicht *continue watching*.
Keine der beiden Apps veröffentlicht ihr nationales Ranking, weil `TvContract`
keine Tabelle dafür hat.

Dieser Feeder schließt diese Lücke: eine kleine, manuell installierte App holt das
Ranking **selbst** von den offiziellen Quellen, hält das Dokument in ihrem eigenen
privaten Dateiverzeichnis und veröffentlicht eigene Kanäle. Es muss kein PC
eingeschaltet bleiben. `tools/fetch_top.py` bleibt die Referenzimplementierung und
funktioniert weiterhin als optionale externe Quelle (siehe *Altlast: ein externes
top.json*).

## Was Sie bekommen

| Zeile | Erzeuger | Quelle |
|---|---|---|
| Seguir viendo | Netflix / Prime Video selbst | ihre eigenen Watch-Next-Programme, von diesem Projekt unberührt |
| Netflix · Top series España | dieser Feeder | die Netflix-Tudum-Seite Top 10 Spanien (`SERIES`), Land ES |
| Netflix · Top películas España | dieser Feeder | die Spanien-Top-10-Seite derselben Website (`MOVIES`) |
| Prime Video · Top series / películas España | dieser Feeder | beliebte Titel von JustWatch, Prime Video enthalten, Land ES |

Die Zeilen werden in der Reihenfolge des Rankings veröffentlicht: Rang 1 wird
zuletzt eingefügt und trägt das größte Vorschaugewicht, die Zeile beginnt also mit
Rang 1; ein Launcher, der weder Einfügereihenfolge noch Gewicht beachtet, kann die
Karten noch umsortieren.

Prime Video veröffentlicht kein eigenes Ranking pro Land, deshalb nutzen die
Prime-Zeilen JustWatch: eine GraphQL-Abfrage `popularTitles`, begrenzt auf
`country: ES` und auf die Pakete, die im Abonnement **enthalten** sind (`prv`, und
`pva` für die Variante mit Werbung). Leihvideos werden durch das Filtern auf `prv`
ausgeschlossen: Bei JustWatch ist `amz` der Store und `amp` die ganze
Amazon-Gruppe, und beide lassen Leihvideos in die Liste. Die App führt genau diese
Abfrage aus; das Python-Skript bildet sie nur nach.

Es sind keine Zugangsdaten nötig, und jeder Eintrag trägt den Deep-Link, den
JustWatch bereits kennt: `standardWebURL` des enthaltenen Angebots, also
`https://app.primevideo.com/detail?gti=<gti>`. Das öffnet den Titel in der
Prime-Video-App, deshalb brauchen die Zeilen keine manuelle Id-Tabelle. JustWatch
liefert kein Hintergrundbild, deshalb verwendet `thumb` das Poster erneut: Die App
zählt eine fehlende Vorschau als beeinträchtigte Grafik und würde die Zeilen
entsprechend melden.

Der JustWatch-Pfad ist durch Offline-Prüfungen gegen **aufgezeichnete** Antworten
abgedeckt (auf drei Titel und die Prime-Angebote gekürzt), plus eine Zusicherung,
dass ein fehlschlagender Endpunkt einen Hinweis statt Zeilen erzeugt. Auf der Box
bricht eine fehlschlagende Quelle stattdessen die gesamte Aktualisierung ab, was
die strengere der beiden Varianten ist.

```bash
python3 tools/fetch_top.py --out top.json      # same rows, built on a PC
```

## Schnellstart

Die Box braucht weder PC noch URL:

1. `adb install -r app/build/tvfeeder.apk`
2. `adb shell am start -n com.alberto.tvfeeder/.MainActivity`
3. Lassen Sie das URL-Feld **leer**, drücken Sie auf **Sync** und bestätigen Sie den
   Dialog für `READ_TV_LISTINGS`.

Die App lädt die zwei Netflix-Top-10-Seiten für Spanien und die zwei
JustWatch-Abfragen über HTTPS, baut das Dokument, ersetzt die zwischengespeicherte
Kopie und veröffentlicht die Zeilen. Der Lauf beim Start und der tägliche Job
wiederholen das ohne geöffneten Bildschirm. Eine Installation ganz ohne Netzwerk
holt noch immer die zwischengespeicherte Kopie (siehe *Autonomer Betrieb*).

Diese zwei Seiten werden **statt** der wöchentlichen
`all-weeks-countries.tsv` von Netflix gelesen, die 33 MB und 521.000 Zeilen groß
ist: Die Box hat sie am Größenlimit abgelehnt, und diesen Body einmal täglich im
Heap eines Fernsehers zu halten ist keine Option. Jede Seitenentität trägt bereits
Rang, Woche, Titel, Artwork und Video-Id, es muss also nichts über mehrere
Downloads hinweg zusammengeführt werden (S4).

Einrichtung auf einem echten Fernseher, Stick oder einer Box, mit Screenshots: [install.de.md](install.de.md).

### Ohne ADB installieren, direkt am Fernseher

1. `Settings > Device Preferences > Security & restrictions > Unknown sources` und
   erlauben Sie es für die App, mit der Sie herunterladen werden.
2. Kopieren Sie `app/build/tvfeeder.apk` auf einen USB-Stick oder stellen Sie es von
   einer beliebigen Maschine im LAN bereit, und installieren Sie es von dort.
3. Öffnen Sie **TV Feeder**, lassen Sie die URL leer und drücken Sie auf **Sync**.

### Altlast: ein externes top.json

Eine eingetippte URL im Feld behält den bisherigen Modus: Die App lädt dieses
Dokument herunter, statt selbst etwas abzurufen. So lässt sich ein Dokument
festnageln oder eines ausliefern, das offline mit `tools/fetch_top.py` gebaut
wurde, und es ist nicht mehr der Standard: Beim ersten Lauf nach dem Update wird
die gespeicherte LAN-URL einmal verworfen, sodass eine aktualisierte Box von selbst
autonom startet.

## Autonomer Betrieb

| Frage | Antwort |
|---|---|
| Wo liegt das Dokument? | in `files/last_good.json` im privaten Datenverzeichnis der App, für andere Apps und für `adb` ohne Root unerreichbar |
| Wann wird es ersetzt? | erst wenn das frisch gebaute Dokument geparst wurde; es wird nach `last_good.json.tmp` geschrieben, per fsync synchronisiert und umbenannt, sodass ein Absturz nie eine halbe Datei hinterlässt |
| Was, wenn eine Quelle fehlschlägt? | Die gesamte Aktualisierung schlägt fehl. Vier Zeilen oder keine: Der Bericht nennt die fehlschlagende Quelle, und die veröffentlichten Zeilen bleiben, wie sie sind |
| Was, wenn die Box offline ist? | Es wird nichts veröffentlicht, und die sichtbaren Zeilen bleiben. Der Cache wird nur bei einem **Kaltstart** erneut veröffentlicht (seit dem Anlegen der App-Daten wurde nichts veröffentlicht), sodass ein täglicher Offline-Lauf den Provider nie unnötig verändert |
| Woraus wird eine Netflix-Karte gebaut? | aus einer Seitenentität: Rang, Woche, Titel, Poster und Video-Id stammen alle aus derselben Nutzlast, eine verspätet aktualisierte Seite kann sie also nicht auseinanderreißen. Der weltweite Datensatz mit 33 MB wird nie geladen |
| Wie wird die Tabelle gewählt? | Die Schaltfläche **Destination** in der Activity wechselt zwischen Empfehlungskanälen und Watch Next und synchronisiert sofort; sie hat den Wechsel der URL ersetzt |

Serientitel stammen aus `top10Video.parentShow.title` der Entität („Below“, nicht
„Below: Limited Series“); Filme haben keine Elternserie, deshalb wird ihr eigenes
`top10Video.title` verwendet. Netflix benennt einen Titel in den beiden Quellen
nicht immer gleich: Für die Fixture-Woche nennt der Datensatz den Film auf Rang 10
„Ice Age: Collision Course“ und die Seite dasselbe Video „Ice Age 5: Collision
Course“. Auf der Box gewinnt die Seite, weil sie die einzige Benennung ist, die die
Box lesen kann; `tests/test_autonomous_feeder.py` hält diesen einen erlaubten
Unterschied fest und schlägt fehl, sobald ein anderer Titel abweicht.

Eine fehlschlagende Quelle wird im Bericht genannt, zum Beispiel:

```
FAILED refresh of on-box sources (Netflix Tudum + JustWatch ES): HTTP 503 from https://apis.justwatch.com/graphql
Last good copy cached at 2026-11-11 18:20 (held back, not republished).
Nothing published, previous rows left untouched (S5).
```

## Warum die Top-Karten ihre Apps nicht öffneten

Ein Launcher löst `COLUMN_INTENT_URI` aus; der alte Code veröffentlichte
`intent://www.netflix.com/watch/81652678#Intent;scheme=https;package=com.netflix.ninja;end`,
was nur auflöst, wenn diese App einen Filter für dieses Schema, diesen Host **und**
diesen Pfad registriert. Einträge ohne Link bekamen `intent:#Intent;package=…;end`:
keine Action, keine Kategorie, keine Komponente, was normalerweise zu nichts
auflöst. Korrekt aussehende Links waren tote Karten.

Jetzt wird jede Karte zuerst auf dem Gerät aufgelöst:

1. `PackageManager.queryIntentActivities` auf dem Deep-Link, festgelegt auf das
   Paket der Zeile (`ACTION_VIEW` + die https-URL).
2. Wenn nichts ihn behandelt, der Launch-Intent der App selbst
   (`getLaunchIntentForPackage`), damit die Karte trotzdem Netflix oder Prime Video
   öffnet.
3. Wenn keines von beiden auflöst, wird die Karte **nicht veröffentlicht**, und die
   Berichtszeile der Zeile zählt sie: `2 without a resolvable link`. Eine Zeile, der
   nichts zum Öffnen bleibt, wird als `no card opens its app` gemeldet, und die
   vorherigen Zeilen bleiben.

Der veröffentlichte Wert ist `Intent.toUri(Intent.URI_INTENT_SCHEME)` des Intents,
der aufgelöst wurde, sodass ein Launcher eine Action plus eine Komponente erhält.
Der Titel reist als Extra mit: Die Ziel-App ignoriert ihn, und er verhindert, dass
die zehn Karten einer App zu einer einzigen verschmelzen.

## Datenvertrag

`tools/fetch_top.py` schreibt das Dokument, das die App liest, und die App schreibt
dieselbe Form in ihren Cache:

```json
{
  "schema": 1,
  "generated": "2026-10-23T20:00:00Z",
  "rows": [
    {
      "channelId": "netflix-top-series-es",
      "title": "Netflix · Top series España",
      "app": "com.netflix.ninja",
      "source": "netflix-tudum",
      "week": "2026-10-18",
      "target": "preview",
      "items": [
        {"title": "Below", "poster": "https://...", "thumb": "https://...",
         "link": "https://www.netflix.com/watch/81652678"}
      ],
      "notices": []
    }
  ]
}
```

Der Generator setzt `target` für jede Zeile mit `--target preview|watch_next`
(Standard `preview`), ein Dokument kann also beide Tabellen bedienen. Auf der Box
ist derselbe Schalter die Schaltfläche **Destination**, die weder Neubau noch PC
braucht.

`target` wählt die Provider-Tabelle und ist der einzige Schalter, den man verstehen
sollte:

- `preview` (Standard) — ein `TYPE_PREVIEW`-Kanal plus Vorschauprogramme. Das ist
  die übliche „Empfehlungen“-Fläche.
- `watch_next` — Watch-Next-Programme, dieselbe Tabelle, in die die offiziellen
  Apps schreiben. Verwenden Sie das, wenn ein Launcher Vorschaukanäle ignoriert;
  der Preis ist, dass die Zeile neben *Seguir viendo* landet, statt eine eigene
  Zeile zu sein.

`link` ist der Deep-Link, den die App zuerst versucht. Netflix-Zeilen tragen
`https://www.netflix.com/watch/<id>`, was die Netflix-App registriert. Prime-Zeilen
tragen die `standardWebURL`, die JustWatch bereits kennt,
`https://app.primevideo.com/detail?gti=<gti>`. In beiden Fällen ist der
veröffentlichte Intent der, den das Gerät aufgelöst hat, keine Vermutung.

## Prüfung

Offline-Prüfungen, ohne Netzwerk und ohne Fernseher:

```bash
python3 tests/test_fetch_top.py          # 10/10, the Python reference logic
python3 tests/test_app.py                # 3/3, static guards on the app sources
python3 tools/fetch_top.py --offline tests/fixtures --out /tmp/top.json
```

Der Builder, der auf der Box läuft, wird geprüft, indem `TopSource.java` mit dem JDK
kompiliert und `tests/java/TopSourceCheck.java` auf denselben Fixtures ausgeführt
wird, und indem anschließend sein Dokument mit dem Referenzdokument verglichen
wird, das `tools/fetch_top.py` aus diesen Fixtures erzeugt. Er braucht ein
reines Test-Jar für `org.json`; die Suite lädt es nie herunter:

```bash
TVFEEDER_JSON_JAR=/path/json-20240303.jar python3 tests/test_autonomous_feeder.py
```

Das Jar ist `org.json:json:20240303` von Maven Central, nur für den Lauf auf dem
Host verwendet: Der Produktionscode nimmt `org.json` von der Plattform, genau wie
zuvor. Das beweist das Parsen der Seitennutzlast, die Titel der Elternserie, den
Alles-oder-nichts-Abbruch und die Form des Dokuments, und es schlägt fehl, wenn die
App wieder den weltweiten Datensatz lädt (S4). Es beweist **nicht**, dass Netflix
oder JustWatch einem Android-Client antworten, und die Kartenauflösung in `Feeder`
braucht die Box (siehe *Grenzen*).

Auf der Box verwenden Sie die Schaltfläche **Diagnostics** der App. Die
`content query`-Befehle unten lesen aus einer PC-Shell nichts: `TvProvider`
beschränkt jede Abfrage auf das aufrufende Paket, und `adb shell` ist kein Paket,
sieht also null Zeilen.

```bash
# returns "No result found" from a PC shell, by design
adb shell content query --uri content://android.media.tv/channel \
  --projection package_name,display_name,type,searchable
```

Was ein PC weiterhin sehen kann, ist die Synchronisation selbst und der Intent, den
die App für jede Karte aufgelöst hat:

```bash
adb logcat -d -s TvFeeder:* | tail -8
```

```
Published 4 rows, 40 items, 0 without artwork, 0 errors
- netflix-top-series-es (watch_next): 10 items, week 2026-10-18
  opens: intent:https://www.netflix.com/watch/81652678#Intent;action=android.intent.action.VIEW;component=com.netflix.ninja/.ui.MainActivity;S.title=Below;end
```

Diese `opens:`-Zeile ist das, was ein Tipp auf dem Fernseher auslöst; Sie können
sie auch aus einer PC-Shell starten.

```bash
adb shell am start -a android.intent.action.VIEW \
  -n com.netflix.ninja/.ui.MainActivity -d https://www.netflix.com/watch/81652678
```

## Warum die App so aussieht

- `WRITE_EPG_DATA` und `READ_EPG_DATA` sind `normal`-Berechtigungen, jede App kann
  sie also halten; `ACCESS_ALL_EPG_DATA` ist `signature|privileged` und wird
  bewusst nicht angefordert.
- `TvProvider` wirft `SecurityException`, wenn ein Aufrufer ohne
  `ACCESS_ALL_EPG_DATA` eine nicht leere `selection` an `query`, `update` oder
  `delete` übergibt. Die App übergibt deshalb überall `null` als Selektion: Der
  Provider beschränkt sie bereits auf das aufrufende Paket.
- `Channels.COLUMN_BROWSABLE` wird genauso abgelehnt: Auf der Mi Box S schlug jedes
  Einfügen mit `SecurityException: Not allowed to access
  Channels.COLUMN_BROWSABLE` fehl, bis die App diese Spalte nicht mehr schrieb, die
  `TvProvider` nur Inhabern von `ACCESS_ALL_EPG_DATA` erlaubt. Die Spalte hat
  bereits den Standardwert 1, es geht also nichts verloren. `tests/test_app.py`
  sichert das ab.
- Eine nicht leere `selection` wird ebenfalls abgelehnt, bei `query`, `update` und
  `delete`: `SecurityException: Selection not allowed for
  content://android.media.tv/channel/<id>`. Kanäle überleben die erste
  Synchronisation (Einfügepfad) und scheitern an der zweiten (Aktualisierungspfad),
  solange die Selektion nicht weggelassen wird. `tests/test_app.py` sichert das ab.
- Ein Wechsel von `target` ist nicht kostenlos: `clearOwnPrograms()` leert beide
  Tabellen, nach einer Synchronisation mit **Destination → Watch Next** bleiben die
  Vorschau-**Kanäle** also **bestehen, verlieren aber jeden Eintrag**, und ein
  Launcher zeigt sie als leere Zeilen. Veröffentlichen Sie dasselbe Ziel, das der
  Launcher anzeigt, und drücken Sie nach dem Wechsel auf **Sync**.
- Jede Karte trägt einen Intent, den das Gerät bereits aufgelöst hat (S3a): den
  Deep-Link, wenn eine Activity ihn behandelt, sonst den Launch-Intent der App, und
  gar nichts, wenn keines von beiden auflöst. Der Titel reist als `S.title`-Extra
  mit, damit die zehn Karten einer App unterschiedliche URIs behalten und ein
  Launcher sie nicht zusammenlegt.
- Der autonome Abruf läuft im Job-Thread mit 20 s Verbindungs- und Lese-Timeout pro
  Anfrage, fünf Anfragen, alles oder nichts. Er läuft nie im UI-Thread.
- Das Lesen der Zeilen eines *anderen* Pakets verlangt
  `android.permission.READ_TV_LISTINGS` (`dangerous`, zur Laufzeit erteilt) **und**
  die Zeile muss `searchable=1` haben, weshalb der Feeder seine eigenen Kanäle und
  Programme so markiert.
- Löschen und erneutes Veröffentlichen geschieht in einem Durchgang am Anfang jeder
  Synchronisation, und nur wenn mindestens eine Zeile gültig ist, sodass ein
  fehlgeschlagener Download nie die bereits sichtbaren Zeilen löscht.

## Grenzen

- Die Synchronisation auf der Box funktioniert. Am 2026-11-11 veröffentlichte sie
  4 Zeilen und 40 Einträge, 0 ohne Grafik und 0 Fehler, für die Netflix-Woche
  2026-11-08, gebaut aus den zwei Spanien-Seiten plus den zwei JustWatch-Abfragen.
  Der weltweite Datensatz mit 33 MB wird nie geladen, und ein Seiten-Body hat
  ~200 KB gegenüber dem Limit von 1 MiB (S4).
- Auf einer Mi Box S (MIBOX4, Android TV 9) **vor** dieser Änderung verifiziert:
  Installation, die Erteilung von `READ_TV_LISTINGS`, das Veröffentlichen von 4
  Kanälen und 40 Watch-Next-Einträgen sowie die Planung von Start- und Job-Läufen
  späterer Durchläufe. Fokusreihenfolge und Berichtsbildschirm wurden mit einem
  D-Pad geprüft.
- Die Kartenauflösung (S3a) ist auf dem Gerät bewiesen: Eine `app_process`-Sonde
  hat die veröffentlichten Strings mit `Intent.parseUri` bei Flags 0 und
  `URI_INTENT_SCHEME` geparst und sie zu `com.netflix.ninja/.MainActivity` und
  `com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity` auf,
  während der frühere aktionslose Paket-Intent zu nichts auflöste. Offen bleibt ein
  menschlicher Tipp auf dem Fernseher, und ein Launcher, der Vorschaukanäle
  verbirgt, zeigt nichts, bis sein eigener Kanalschalter aktiv ist (Projectivy,
  siehe unten).
- www.netflix.com war während der Tests etwa zehn Minuten lang von dieser Verbindung
  aus nicht erreichbar: Seine Edge-Adressen liefen bei einem rohen TCP-Connect in
  ein Timeout, während `apis.justwatch.com` mit 200 antwortete. Die App meldete den
  Fehler und ließ die veröffentlichten Zeilen unberührt, was das spezifizierte
  Verhalten ist (L15).
- Der Python-Generator braucht `curl`, weil netflix.com HTTP/1.1-Anfragen von
  Skript-Clients zurücksetzt. Die Box nutzt `HttpURLConnection`; setzt Netflix es
  genauso zurück, zeigt der Bericht `FAILED refresh of on-box sources …: …`, und die
  vorherigen Zeilen bleiben. Der externe top.json-Modus ist dann der Rückfall, bis
  der Transport funktioniert.
- `targetSdkVersion 28` blockiert Klartext-HTTP standardmäßig, deshalb muss das
  Manifest `android:usesCleartextTraffic="true"` für den externen Modus setzen;
  ohne das scheitert jede Synchronisation mit `IOException: Cleartext HTTP traffic
  to ... not permitted`. `tests/test_app.py` sichert das ab.
- Die Kartenauflösung ruft `PackageManager.queryIntentActivities` auf, was ein
  `<queries>`-Element braucht, sobald `targetSdkVersion` 30 erreicht. Bei 28 sind
  alle Pakete sichtbar, weshalb das Manifest heute keine Änderung benötigt.
- Das Netflix-Poster kommt von seinem CDN; ändern sich diese URLs, werden die
  Zeilen ohne Grafik veröffentlicht, und der Bericht zählt sie.
- Ein Launcher, der `TYPE_PREVIEW`-Kanäle ignoriert, zeigt die Zeilen gar nicht.
  Projectivy ist einer davon, bis sein eigener Schalter **Categorías / Canales →
  Habilitar canales** aktiv ist; mit `target` auf `watch_next` landen die Zeilen
  stattdessen in seiner Zeile *Continuar viendo*, neben dem, was die offiziellen
  Apps veröffentlichen.
- Die Kartentitel sind Netflix' eigene Seitentitel. Sie stimmen meist Wort für Wort
  mit dem Wochendatensatz überein, aber nicht immer (siehe *Autonomer Betrieb*),
  deshalb kann ein Titel auf dem Fernseher von dem in der Datensatzansicht von
  `netflix.com/tudum/top10` abweichen.
