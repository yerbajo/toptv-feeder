[English](README.md) · [Español](README.es.md) · [Français](README.fr.md) · Deutsch

# TV Feeder: die nationalen Toplisten auf dem TV-Startbildschirm

<p align="center"><img src="docs/img/logo-toptv-feeder.png" width="780" alt="TopTV-Feeder"></p>

## Das Problem und was diese App dazu beiträgt

Weder Netflix noch Prime Video veröffentlichen ein Länder-Top-10 im TV Provider
von Android TV: Sie veröffentlichen *continue watching* und einen kuratierten
Vorschaukanal. `TvContract` hat keine Tabelle für ein Ranking, deshalb können der
Launcher ab Werk und Projectivy nur zeigen, was die Apps zu veröffentlichen wählen,
und eine Zeile „Top series España“ kann ohne eine App, die sie veröffentlicht,
nicht existieren. Diese App verantwortet diese beiden sortierten Zeilen,
veröffentlicht sie als eigene durchsuchbare Kanäle im TV Provider und lässt
*continue watching* sowie die Zeilen aller anderen Pakete unberührt.

TV Feeder ist eine kleine Android-TV-App, die man manuell installiert. Sie baut
eine Zeile **Top series España** und eine Zeile **Top películas España** aus den
offiziellen Quellen von Netflix und JustWatch, veröffentlicht sie als eigene
Kanäle des TV Providers und öffnet beim Auswählen einer Karte die passende App.
Sie läuft auf der Box selbst: ohne PC, ohne Server, ohne API-Schlüssel und ohne
Konto.

## Was sie veröffentlicht

| Zeile | Erzeuger der Zeile | Quelle |
|---|---|---|
| Netflix · Top series España | diese App | die Netflix-Tudum-Seite Top 10 Spanien (`SERIES`) |
| Netflix · Top películas España | diese App | die Spanien-Top-10-Seite derselben Website (`MOVIES`) |
| Prime Video · Top series / películas España | diese App | beliebte Titel von JustWatch, `country: ES`, im Abonnement enthaltene Angebote |

*Seguir viendo* bleibt unangetastet: Diese Zeile veröffentlichen Netflix und
Prime Video selbst. Das Dokument entsteht aus den zwei Spanien-Seiten und den zwei
JustWatch-Abfragen, nie aus dem weltweiten Wochendatensatz von Netflix mit 33 MB,
den keine TV-Box herunterladen sollte.

Jede Karte wird vor der Veröffentlichung auf dem Gerät aufgelöst, sodass ein
Auswählen Netflix oder Prime Video öffnet statt eines toten Links. Eine Karte,
deren Link sich nicht auflösen lässt, wird nicht veröffentlicht, und der
Synchronisationsbericht zählt sie.

Die Prime-Video-Karten verwenden ein Querformat-Standbild von JustWatch (480 px)
als Kartenbild und das Hochformat-Poster (592 px) als Poster, weil ein 2:3-Poster in
die 16:9-Karte gestreckt wurde; ein Titel ohne Standbild verwendet sein Poster. Die
Netflix-Karten ändern sich nicht. Amazon veröffentlicht kein sortiertes
Prime-Video-Top-10 pro Land, das anonym lesbar wäre, deshalb bleibt JustWatch die
Prime-Quelle: [feeder.de.md](docs/feeder.de.md) hält fest, was geprüft wurde.

## So sieht es aus

![Die Top-Reihen von Prime Video und Netflix auf dem Fernseher](docs/img/launcher-rows.jpg)

*Projectivy zeigt die Reihe **Prime Video · Top series España** über den Netflix-Reihen: die App hat Rangliste, Artwork und Ziel jeder Karte veröffentlicht.*

![Die App nach einer Synchronisierung](docs/img/app-sync.jpg)

*Eine Synchronisierung auf der Box: vier Reihen, vierzig Karten, und der Bildschirm in der Sprache des Fernsehers.*

## Voraussetzungen

| Punkt | Detail |
|---|---|
| Gerät | eine Android-TV- oder Google-TV-Box, zum Beispiel eine Mi Box S, mit **Android 9 (API 28) oder neuer** |
| Apps | **Netflix** und **Prime Video** installiert, damit die Karten etwas zu öffnen haben |
| Berechtigung | `READ_TV_LISTINGS`, zur Laufzeit erteilt im Dialog der ersten Synchronisation |
| Netzwerk | HTTPS-Zugriff auf `www.netflix.com` und `apis.justwatch.com` |

## Bauen und manuell installieren

```bash
bash tools/build-apk.sh                                  # app/build/toptv-feeder.apk
adb install -r app/build/toptv-feeder.apk
adb shell am start -n com.alberto.tvfeeder/.MainActivity
```

Lassen Sie danach das URL-Feld **leer**, drücken Sie auf **Sync** und bestätigen
Sie den Dialog für die TV-Listings. Die Zeilen erscheinen im Launcher, der den TV
Provider liest; in Projectivy müssen zuerst dessen eigene Kanäle aktiviert werden.

Ohne ADB kopieren Sie `app/build/toptv-feeder.apk` auf einen USB-Stick, erlauben
unbekannte Quellen für den Dateimanager des Fernsehers und installieren die App
von dort.

Das Dokument wird in `files/last_good.json` im privaten Datenverzeichnis der App
zwischengespeichert, sodass eine fehlgeschlagene Aktualisierung die bereits
sichtbaren Zeilen stehen lässt.

Schrittweise Einrichtung auf einem echten Gerät, mit Screenshots: [install.de.md](docs/install.de.md).

## Wann synchronisiert wird

| Auslöser | Zeitpunkt |
|---|---|
| Start | beim Hochfahren der Box, sobald das Netzwerk steht |
| täglich | alle 24 Stunden, in jedem Netzwerk |
| manuell | wenn Sie in der Activity auf **Sync** drücken |

Eine Aktualisierung ist alles oder nichts: Schlägt eine der vier Quellen fehl,
nennt der Bericht sie, und die veröffentlichten Zeilen bleiben unverändert, statt
durch Teildaten ersetzt zu werden.

## Ziel

Die Schaltfläche **Destination** in der Activity wechselt die Tabelle, in die die
Zeilen veröffentlicht werden, und synchronisiert sofort:

- **Destination: recommendation channels** (Standard) veröffentlicht
  Vorschaukanäle und Vorschauprogramme, die übliche Empfehlungsfläche.
- **Destination: Watch Next** veröffentlicht in Watch Next, neben *Seguir viendo*,
  für Launcher, die Vorschaukanäle ignorieren.

## Sprache

Der Bildschirm folgt der Sprache des Fernsehers, und die Schaltfläche **Language**
in der Activity schaltet sie weiter: die des Fernsehers → English → Español
(`Idioma`, sobald der Bildschirm spanisch ist). Die Wahl wird gespeichert und
übersteht Neustarts, und der Synchronisationsbericht wird in der Sprache
geschrieben, die der Bildschirm hat, wenn die Synchronisation läuft. Ausgeliefert
werden nur Englisch und Spanisch, ein Fernseher in jeder anderen Sprache bekommt
also Englisch.

## Prüfung

Die Prüfungen laufen offline: nur Fixtures, ohne Netzwerk und ohne Fernseher.

```bash
python3 tests/test_docs.py          # four languages present, interlinked and in sync
```

## Dokumentation und Lizenz

[feeder.md](docs/feeder.md) ist die vollständige Anleitung: Quellen, Dokumentformat,
autonomer Betrieb, Auflösung der Karten, Diagnose auf der Box und bekannte
Grenzen. Das Projekt steht unter der [MIT-Lizenz](LICENSE).