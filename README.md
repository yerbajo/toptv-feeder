English · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md)

# TV Feeder: national top tens on the TV home screen

<p align="center"><img src="docs/img/logo-toptv-feeder.png" width="780" alt="TopTV-Feeder"></p>

## The problem, and what this app does about it

Neither Netflix nor Prime Video publishes a per-country Top 10 to the Android TV
Provider: they publish continue-watching and one curated preview channel.
`TvContract` has no table for a ranking, so the stock launcher and Projectivy can
only show what the apps choose to publish, and a "Top series España" row cannot
exist without an app that publishes it. This app owns those two ranked rows,
publishes them into the TV Provider as its own searchable channels, and leaves
continue-watching and every other package's rows untouched.

TV Feeder is a small sideloaded Android TV app. It builds a **Top series España**
and a **Top películas España** row from the official Netflix and JustWatch
sources, publishes them as its own TV Provider channels, and opens the matching
app when a card is tapped. It runs on the box itself: no PC, no server, no API
key and no account.

## What it publishes

| Row | Row producer | Source |
|---|---|---|
| Netflix · Top series España | this app | the Netflix Tudum Spain Top 10 page (`SERIES`) |
| Netflix · Top películas España | this app | the same site's Spain Top 10 page (`MOVIES`) |
| Prime Video · Top series / películas España | this app | JustWatch popular titles, `country: ES`, offers included with the subscription |

*Seguir viendo* is untouched: Netflix and Prime Video publish that row
themselves. The document is built from the two Spain pages plus the two JustWatch
queries, never from Netflix's 33 MB world-wide weekly dataset, which no TV box
should have to download.

Each card is resolved on the device before it is published, so a tap opens
Netflix or Prime Video instead of a dead link. A card whose link resolves to
nothing is not published, and the sync report counts it.

The Prime Video cards use a JustWatch landscape still (480 px) as the card image and
the portrait poster (592 px) as poster art, because a 2:3 poster was being stretched
into the 16:9 card; a title with no backdrop falls back to its poster. The Netflix
cards are unchanged. Amazon publishes no ordered per-country Prime Video Top 10 that
can be read anonymously, so JustWatch stays the Prime source: [feeder.md](docs/feeder.md)
records what was checked.

## What it looks like

![The Prime Video and Netflix top rows on the TV home screen](docs/img/launcher-rows.jpg)

*Projectivy drawing the **Prime Video · Top series España** row over the Netflix rows: the app published the ranking, the artwork and the tap target.*

![The app after one sync](docs/img/app-sync.jpg)

*One sync on the box: four rows, forty cards, and the screen in the TV's own language.*

## Requirements

| Item | Detail |
|---|---|
| Device | an Android TV or Google TV box, e.g. a Mi Box S, on **Android 9 (API 28) or later** |
| Apps | **Netflix** and **Prime Video** installed, so the cards have something to open |
| Permission | `READ_TV_LISTINGS`, granted at runtime in the dialog the first sync shows |
| Network | HTTPS access to `www.netflix.com` and `apis.justwatch.com` |

## Build and sideload

```bash
bash tools/build-apk.sh                                  # app/build/toptv-feeder.apk
adb install -r app/build/toptv-feeder.apk
adb shell am start -n com.alberto.tvfeeder/.MainActivity
```

Then leave the URL field **empty**, press **Sync** and accept the TV listings
dialog. The rows appear in the launcher that reads the TV Provider; in Projectivy
its own channels have to be enabled first.

Without ADB, copy `app/build/toptv-feeder.apk` to a USB stick, allow unknown sources
for the file manager on the TV, and install it from there.

The document is cached in `files/last_good.json` inside the app's private data
directory, so a refresh that fails leaves the rows you already see in place.

Step-by-step device setup with screenshots: [install.md](docs/install.md).

## When it syncs

| Trigger | When |
|---|---|
| boot | when the box starts, once the network is up |
| daily | every 24 hours, on any network |
| manual | when you press **Sync** on the activity |

A refresh is all or nothing: if one of the four sources fails, the report names
it and the published rows stay untouched rather than being replaced by partial
data.

## Destination

The **Destination** button on the activity switches the table the rows go to and
syncs immediately:

- **Destination: recommendation channels** (default) publishes preview channels
  and preview programs, the usual recommendation surface.
- **Destination: Watch Next** publishes into Watch Next, next to *Seguir viendo*,
  for launchers that ignore preview channels.

## Language

The screen follows the TV's own language, and the **Language** button on the
activity cycles it: the TV's own → English → Español (`Idioma` on a Spanish
screen). The choice is stored and survives reboots, and the sync report is written
in the language the screen is in when the sync runs. Only English and Spanish ship,
so a TV in any other language gets English.

## Verification

The checks are offline: fixtures only, no network and no TV needed.

```bash
python3 tests/test_docs.py          # four languages present, interlinked and in sync
```

## Documentation and licence

[feeder.md](docs/feeder.md) is the full guide: sources, document format, autonomous mode,
card resolution, on-box diagnostics and the known limits. The project is
[MIT licensed](LICENSE).