English · [Español](install.es.md) · [Français](install.fr.md) · [Deutsch](install.de.md)

# Install TV Feeder on an Android TV device

This guide installs the app on a real device: a TV with Android TV or Google TV, a
TV stick or a set-top box. It takes one APK, one install and one sync. The rest of
the app is described in [feeder.md](feeder.md).

## 1. What you need

| Item | Detail |
|---|---|
| Device | Android TV or Google TV on **Android 9 (API 28) or later**: a Mi Box S, a Chromecast-style stick or any box that has ADB or a file manager |
| Apps | **Netflix** and **Prime Video** installed on the same device, so the cards have an app to open |
| Network | the device on the same network as your PC while you install. Afterwards it only needs HTTPS to `www.netflix.com` and `apis.justwatch.com` |
| PC, only to build | the Android SDK **build-tools 34.0.0** and **platforms/android-34**; `javac`, `keytool` and `zip` on the `PATH` |

An already built `app/build/toptv-feeder.apk` needs no PC at all.

## 2. Build the APK

```bash
bash tools/build-apk.sh
```

The script looks for the SDK in two fixed paths and takes overrides when yours are
elsewhere:

```bash
TVFEEDER_BUILD_TOOLS=/opt/android/build-tools/34.0.0 \
TVFEEDER_ANDROID_JAR=/opt/android/platforms/android-34/android.jar \
bash tools/build-apk.sh
```

The output is `app/build/toptv-feeder.apk`. Before it declares success the script
checks the built file: `aapt2 dump badging` for the package name
`com.alberto.tvfeeder`, `sdkVersion` and `targetSdkVersion` 28, the `INTERNET`,
`READ_TV_LISTINGS`, `RECEIVE_BOOT_COMPLETED`, `READ_EPG_DATA` and `WRITE_EPG_DATA`
permissions, and a launchable plus leanback-launchable `MainActivity`; it refuses
an APK that requests `ACCESS_ALL_EPG_DATA`; it checks the manifest for
`BootReceiver` and the `BOOT_COMPLETED` and `QUICKBOOT_POWERON` filters; and it runs
`apksigner verify`. It prints the `sha256` of the APK at the end.

## 3. Install over ADB

Enable ADB on the device first: `Settings > Device Preferences > About`, press OK
on **Build** seven times, then `Developer options` and turn on **USB debugging** (or
**Network debugging**) and accept the RSA prompt on the TV.

```bash
adb connect <BOX_IP>:5555
adb install -r app/build/toptv-feeder.apk
adb shell am start -n com.alberto.tvfeeder/.MainActivity
```

If the port stays shut, the box has no network debugging and needs one USB
connection first: attach it to the PC, accept the prompt, run `adb tcpip 5555`, then
unplug.

## 4. Install without ADB

Either copy `app/build/toptv-feeder.apk` to a USB stick and plug it into the TV, or
serve the file from the PC:

```bash
cd app/build && python3 -m http.server 8000
```

On the TV, open `http://<PC_IP>:8000/toptv-feeder.apk` with the file manager or with the
**Downloader** app, allow unknown sources for that app
(`Settings > Security & restrictions > Unknown sources`), and install.

## 5. First run

Open **TV Feeder**, leave the URL field empty, press **Sync** and accept the
`READ_TV_LISTINGS` dialog. The report reads:

```
OK 2026-11-11 20:25 · Published 4 rows, 40 items, 0 without artwork, 0 errors
Source: on-box sources (Netflix Tudum + JustWatch ES)
```

![The activity after a sync, in the TV's language](img/app-sync.jpg)

`JustWatch ES` is the code of the selected country: another country changes that
code, the row titles and the pages the app reads.

The numbers mean:

| Value | Meaning |
|---|---|
| `4 rows` | the two Netflix rows and the two Prime Video rows were published |
| `40 items` | ten cards per row; a card whose link resolved to nothing is dropped before publishing, so this can be lower |
| `0 without artwork` | cards whose poster or thumbnail came back empty; they still publish, and the count tells you the artwork is degraded |
| `0 errors` | rows that failed as a whole; any non-zero value names the row below the summary |

Under the summary, each row line carries its week and the `opens:` URI of its first
card: that is the string the launcher fires.

### The screen language

The **Language** button reads `Language: the TV's own` and cycles the screen through
the TV's own language → English → Español. The choice is stored, survives reboots,
and the report is written in the language the screen is in, so a Spanish screen
prints the same lines in Spanish. Only English and Spanish ship; a TV set to any
other language gets English.

On a Spanish screen the buttons read `Sincronizar`, `Diagnóstico`, `Destino` and
`Idioma`, so the steps above name the button, not its label.

### The screen country

The **Country** button shows the current choice and opens the list of supported
countries: Spain, Germany, Netherlands, France, Italy, United Kingdom, United
States, Portugal, Belgium, Austria, Switzerland, Sweden, Poland, Ireland, Mexico,
Brazil and Argentina. The app starts with the country of the TV's locale when it is
in that list, and with Spain otherwise. The choice is stored like the language, and
the row titles carry the country name in the screen's language: a Spanish screen
with Germany selected shows `Netflix · Top series Alemania`, an English one shows
`Netflix · Top series Germany`. Switching country or language removes this app's
older rows, so no empty row from the previous country stays behind.

## 6. Show the rows

The cards appear in ranking order: the first card is rank 1. The app publishes the
ranked cards back to front, so rank 1 is the last one inserted, and it carries the
largest preview weight for launchers that honour `COLUMN_WEIGHT`; on the Mi Box the
Netflix · Top series España row read rank 1 (*A Couple of Lies*) through rank 7 in
order, and the Prime · Top películas España row opened with its first published
title (*Proyecto Salvación*). The launcher still decides how many of the ten cards
fit on screen, and a launcher that honours neither insertion order nor weight may
still reorder them.

In **Projectivy**, enable its own channels first:
`Categorías / Canales → Habilitar canales`. The feeder rows then appear next to the
app row and *Continuar viendo*:

![Projectivy home](img/launcher-home.jpg)

Launchers that read preview channels show the rows without further setup. If your
launcher ignores preview channels, press **Destination** until it reads
**Destination: Watch Next**, which publishes the same rows into Watch Next instead;
press **Sync** again afterwards, because switching clears the other table.

![The four rows in Projectivy](img/launcher-rows.jpg)

## 7. Daily life

The app syncs at boot, every 24 hours and when you press **Sync**. A source that
fails or comes back empty is named in the report and the other rows are published
anyway, so a country where JustWatch is thin may publish only the Netflix rows;
when nothing at all can be published, the rows already on the launcher stay and the
last good document stays in `files/last_good-<cc>.json`.

## 8. Troubleshooting

| Symptom | Cause | What to do |
|---|---|---|
| No rows at all | the launcher hides the channels, or **Destination** targets the other table | enable the launcher's own channels (Projectivy: `Categorías / Canales → Habilitar canales`), or switch **Destination** and press **Sync** |
| The report is OK but cards do nothing | the target app is missing, or the card was dropped because its link did not resolve on the device | install Netflix or Prime Video; the report counts dropped cards as `N without a resolvable link` |
| `FAILED refresh of on-box sources …` | no HTTPS route to `www.netflix.com` or `apis.justwatch.com`; transient edge outages happen | nothing to repair in the app: the report names the source and the other rows still publish, and when nothing publishes the old rows stay. Retry later with **Sync** |
| **Diagnostics** shows 0 items per channel | the channels exist but the programs did not publish, or the table in view is the other **Destination** | run **Sync** with the **Destination** the launcher shows, then read **Diagnostics** again |
| `SecurityException` or a cleartext error in the report | `READ_TV_LISTINGS` was not granted, or the external top.json URL is plain `http` | grant the dialog on the next sync; for the optional external URL use `https` where possible |

The **Diagnostics** button prints every provider row it can read, its
`package_name`, type and `searchable` flag, the items per channel, and the total
count of Watch Next items, which is how you tell an empty row from a missing one.

## 9. Removing it

```bash
adb uninstall com.alberto.tvfeeder
```

Uninstalling removes the app, its channels and the cached document. The rows
disappear from the launcher at its next refresh.
