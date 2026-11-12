English · [Español](feeder.es.md) · [Français](feeder.fr.md) · [Deutsch](feeder.de.md)

# TV feeder: ranked rows the apps cannot give you

The stock launcher and Projectivy can only show content an app publishes to the
**TV Provider**. Netflix publishes *continue watching* and one curated preview
channel; Prime Video publishes *continue watching*. Neither publishes its
national ranking, because `TvContract` has no table for one.

This feeder fills that gap: a tiny sideloaded app fetches the ranking from the
official sources **itself**, keeps the document in its own private files
directory, and publishes its own channels. No PC has to stay on.
`tools/fetch_top.py` remains the reference implementation and still works as the
optional external source (see *Legacy: an external top.json*).

## What you get

| Row | Producer | Source |
|---|---|---|
| Seguir viendo | Netflix / Prime Video themselves | their own Watch Next programs, untouched by this project |
| Netflix · Top series *country* | this feeder | the Netflix Tudum Top 10 series page of the selected country (`SERIES`) |
| Netflix · Top películas *country* | this feeder | the same site's film page of that country (`MOVIES`) |
| Prime Video · Top series *country* | this feeder | JustWatch popular titles, Prime Video included, `country: <ISO code>` |
| Prime Video · Top películas *country* | this feeder | the same JustWatch query, Prime Video included, `country: <ISO code>` |
| Disney+ · Top series *country* | this app | JustWatch with the `dnp` package, offers included with the subscription |
| Disney+ · Top películas *country* | this app | JustWatch with the `dnp` package, offers included with the subscription |
| Max · Top series *country* | this app | JustWatch with the `mxx` package, offers included with the subscription |
| Max · Top películas *country* | this app | JustWatch with the `mxx` package, offers included with the subscription |
| Apple TV · Top series *country* | this app | Apple's own series chart of the storefront |
| Apple TV · Top películas *country* | this app | Apple's own film chart of the storefront |
| Movistar+ · Top series *country* | this app | JustWatch with the `mvs` package; the link is http and the app accepts http and https |
| Movistar+ · Top películas *country* | this app | JustWatch with the `mvs` package; the link is http and the app accepts http and https |
| SkyShowtime · Top series *country* | this app | JustWatch with the `sst` package, offers included with the subscription |
| SkyShowtime · Top películas *country* | this app | JustWatch with the `sst` package, offers included with the subscription |
| Filmin · Top series *country* | this app | JustWatch with the `fil` package, offers included with the subscription; its TV app claims only its own hosts, so the card opens Filmin, not the title |
| Filmin · Top películas *country* | this app | JustWatch with the `fil` package, offers included with the subscription; its TV app claims only its own hosts, so the card opens Filmin, not the title |

Every card is resolved on the device before it is published: the platform app's deep
link first, then the app itself, then the platform's web page in the television's browser,
then the app's store page. A card with no destination at all is dropped and counted, so a
TV that lacks one of these apps still shows the ranking instead of an empty row.


One country at a time, and its name is in every row title, in the language of the
screen: `Netflix · Top series Alemania` on a Spanish screen with Germany selected,
`Netflix · Top series Germany` on an English one. The channel ids carry the country
code (`netflix-top-series-de`, `prime-top-films-de`), so two countries never share a
row. The examples in this guide are Spain (`netflix-top-series-es`).

The rows are published in ranking order: rank 1 goes in last and carries the
largest preview weight, so the row opens on rank 1; a launcher that honours neither
insertion order nor weight may still reorder the cards.

Prime Video publishes no per-country ranking of its own, so the Prime rows use
JustWatch: a GraphQL `popularTitles` query scoped to the selected country
(`country: <ISO code>`) and to the
packages that are **included** with the subscription (`prv`, and `pva` for the
ad-supported tier). Rentals are excluded by filtering on `prv`: JustWatch's
`amz` is the store and `amp` the whole Amazon group, and both leak rentals into
the list. The app runs exactly that query; the Python script only mirrors it.

No credentials are needed, and each item carries the deep link JustWatch already
knows: `standardWebURL` for the included offer, which is
`https://app.primevideo.com/detail?gti=<gti>`. That is what opens the title in
the Prime Video app, so the rows need no manual id table.

A Prime card is 16:9 and the JustWatch poster is 2:3, so a poster stretched into
the card read wrong. The query therefore asks for `backdrops { backdropUrl }` as
well: `thumb`, the card image, is the first landscape still at 480 px, and
`poster` is the portrait poster at 592 px. A title with no backdrop uses its
poster for both, so its card still has artwork. The Netflix
rows are unchanged: their `thumb` is still the `storyArt` image from the Tudum
page.

Amazon publishes no ordered per-country Prime Video Top 10 that can be read
anonymously, so JustWatch stays the Prime source. Checked: the storefront
`https://www.primevideo.com/-/es_419/storefront?tr=es` answers 200 but renders the
signup page (`DVSignupOffer`, customerID "unrecognised") instead of rows; the
anonymous search page `https://www.primevideo.com/-/es_419/search?phrase=<title>`
does render ES cards, and each card carries a "TOP 10" badge plus "Estuvo N
semanas en el Top 10", but no rank order; the Top 10 published on aboutamazon.com
is worldwide and Originals-only, and its own text says it differs from what the
app shows per country; and the official Android TV APK installed on the box is a
3.2 MB ignition stub whose dex has no storefront or ranking code.

The JustWatch path is covered by offline checks against **captured** responses
(trimmed to three titles and to the Prime offers), plus an assertion that a
failing endpoint produces a notice instead of rows. On the box the two sources are
independent: a source that fails or comes back empty is named in the report and the
other rows are published anyway.

```bash
python3 tools/fetch_top.py --out top.json      # same rows, built on a PC
```

## Quick start

The box needs no PC and no URL:

1. `adb install -r app/build/toptv-feeder.apk`
2. `adb shell am start -n com.alberto.tvfeeder/.MainActivity`
3. Leave the URL field **empty**, press **Sync** and accept the
   `READ_TV_LISTINGS` dialog.

The app fetches the two Netflix Tudum Top 10 pages of the selected country and the
two JustWatch queries for it over HTTPS, builds the document, replaces the cached
copy and publishes the rows.
The boot run and the daily job repeat it without the screen open. An install with
no network at all still recovers the cached copy (see *Autonomous mode*).

Those two pages are read **instead of** Netflix's weekly `all-weeks-countries.tsv`,
which is 33 MB and 521,000 lines: the box rejected it on the body cap, and holding
that body in a TV heap once a day is not an option. Each page entity already
carries rank, week, title, artwork and video id, so nothing has to be joined
across downloads (S4).

Setup on a real TV, stick or box, with screenshots: [install.md](install.md).

### Install without ADB, from the TV itself

1. `Settings > Device Preferences > Security & restrictions > Unknown sources`
   and allow it for the app you will download with.
2. Copy `app/build/toptv-feeder.apk` to a USB stick, or serve it from any machine
   that is already on the LAN, and install it from there.
3. Open **TV Feeder**, leave the URL empty, press **Sync**.

### Legacy: an external top.json

Typing a URL in the field keeps the previous mode: the app downloads that
document instead of fetching anything. It is the way to pin a document or to
serve one built offline with `tools/fetch_top.py`, and it is no longer the
default: on the first run after the upgrade the stored LAN URL is dropped once,
so an updated box starts autonomous by itself.

## Autonomous mode

| Question | Answer |
|---|---|
| Where does the document live? | `files/last_good-<cc>.json` in the app's private data dir, unreachable by other apps and by `adb` without root |
| When is it replaced? | Only after the freshly built document parsed; it is written to `last_good-<cc>.json.tmp`, fsynced and renamed, so a crash never leaves half a file |
| What if a source fails? | Only that source's rows are left out: the report names it and the other rows are published, so a country where JustWatch is thin may publish only the Netflix rows. When nothing can be published, the previous rows stay as they are |
| What if the box is offline? | Nothing is published and the rows you see stay. The cache is republished only on a **cold start** (nothing published since the app data was created), so a daily offline run never churns the provider |
| What is a Netflix card built from? | One page entity: rank, week, title, poster and video id all come from the same payload, so a page that updates late cannot mismatch them. The 33 MB world-wide dataset is never fetched |
| How is the table chosen? | The **Destination** button on the activity toggles recommendation channels / Watch Next and syncs immediately; it replaced swapping the URL |

Series titles come from the entity's `top10Video.parentShow.title` ("Below", not
"Below: Limited Series"); films have no parent show, so their own
`top10Video.title` is used. Netflix does not always name a title the same way in
the two sources: for the fixture week the dataset calls the rank-10 film "Ice Age:
Collision Course" and the page calls the same video "Ice Age 5: Collision
Course". On the box the page wins, because it is the only naming the box can
read; `tests/test_autonomous_feeder.py` records that single allowed difference and
fails if any other title drifts.

A source that fails or comes back empty is named in the report, and the rows of the
other one are published anyway: a country where JustWatch has no titles still gets
the two Netflix rows, and a Tudum failure still leaves the Prime rows. When nothing
at all can be published, the previous rows stay on the launcher untouched.

## Language

The screen follows the TV's own language, and the **Language** button on the
activity cycles it: the TV's own → English → Español. The label always names the
current choice (`Language: the TV's own`, and `Idioma: English` once the screen is
Spanish), and the two language names stay untranslated. The choice is stored in
the app's preferences and survives reboots, and the sync report is written in the
language the screen is in when the sync runs, so the boot and the daily job report
in the language you see.

Only English and Spanish ship, so a TV set to any other language gets English.
Android 9 has no per-app language API: the choice is applied by handing the
activity and the sync path a context whose configuration carries that locale, and
the screen is rebuilt when the button is pressed.

## Country

The app collects the rankings of **one country**, also chosen on the activity. The
default comes from the TV: the country of its locale when the app supports it, and
Spain otherwise. The **Country** button shows the current choice (`Country: Spain`,
and `País: Alemania` on a Spanish screen) and opens a list of the supported
countries:

Spain (ES), Germany (DE), Netherlands (NL), France (FR), Italy (IT), United
Kingdom (GB), United States (US), Portugal (PT), Belgium (BE), Austria (AT),
Switzerland (CH), Sweden (SE), Poland (PL), Ireland (IE), Mexico (MX), Brazil (BR)
and Argentina (AR).

The choice is stored like the language and survives reboots, and it decides
everything that is per country: the two Tudum pages (`/tudum/top10/<slug>/tv` for
series and `/tudum/top10/<slug>` for films), the `country` and the `language` of
the JustWatch query, the channel ids (`netflix-top-series-de`) and the row titles,
which carry the country name in the screen's language: "Netflix · Top series
Alemania" on a Spanish screen, "Netflix · Top series Germany" on an English one.
Switching country or language deletes the channels this package owns that the new
document does not contain, so the launcher does not keep an empty row from the
previous country; no other package's channels are touched.

## Platforms and where each row comes from

| Platform | Source of the ranking | JustWatch package | App the card targets | Link host the app claims |
|---|---|---|---|---|
| Netflix | its own Tudum Top 10 pages | — | `com.netflix.ninja` | `www.netflix.com` |
| Prime Video | JustWatch popularity | `prv` | `com.amazon.amazonvideo.livingroom` | `app.primevideo.com` |
| Disney+ | JustWatch popularity | `dnp` | `com.disney.disneyplus` | `disneyplus.com`, `www.disneyplus.com` |
| Max | JustWatch popularity | `mxx` | `com.wbd.stream` | `play.max.com`, `play.hbomax.com` |
| Movistar+ | JustWatch popularity | `mvs` | `es.plus.yomvi` | `wl.movistarplus.es` (http and https) |
| SkyShowtime | JustWatch popularity | `sst` | `com.skyshowtime.skyshowtime.google` | any https URL (no host in its filter) |
| Filmin | JustWatch popularity | `fil` | `com.filmin.androidtv` | `androidtv.filmin.com`, `vf.filmin.es` |
| Apple TV | Apple's own store chart | — | `com.apple.atve.androidtv.appletv` | `tv.apple.com` |

Three different claims live in that table, and the guide states them instead of blurring
them. The Netflix rows are Netflix's own published Top 10. The Apple rows are Apple's own
store chart, which can contain titles you buy or rent rather than stream. Everything else
is JustWatch popularity restricted to titles included with that service, which is what the
Prime rows have always been.

The link hosts come from each vendor APK's own intent filters, not from guesswork. Filmin
is the one exception worth knowing: its TV app registers only its own deeplink hosts, so a
`filmin.es` page does not match by data and its card opens the Filmin app itself.

Adding a platform is one entry in `JUSTWATCH_PLATFORMS` in both generators: label, app
package, the package names the query asks for and the ones accepted as a link.

## Why the top cards did not open their apps

A launcher fires `COLUMN_INTENT_URI`; the old code published
`intent://www.netflix.com/watch/81652678#Intent;scheme=https;package=com.netflix.ninja;end`,
which only resolves if that app registers a filter for that scheme, host **and**
path. Items with no link got `intent:#Intent;package=…;end`: no action, no
category, no component, which normally resolves to nothing. Correct-looking
links were dead cards.

Now every card is resolved on the device first:

1. `PackageManager.queryIntentActivities` on the deep link, pinned to the row's
   package (`ACTION_VIEW` + the https URL).
2. If nothing handles it, the app's own launch intent
   (`getLaunchIntentForPackage`), so the card still opens Netflix or Prime Video.
3. If neither resolves, the card is **not published** and the row's report line
   counts it: `2 without a resolvable link`. A row with nothing left to open is
   reported as `no card opens its app` and the previous rows stay.

The published value is `Intent.toUri(Intent.URI_INTENT_SCHEME)` of the intent
that resolved, so a launcher receives an action plus a component. The title
rides along as an extra: the target app ignores it, and it keeps the ten cards
of one app from collapsing into a single card.

## Data contract

`tools/fetch_top.py` writes the document the app reads, and the app writes the
same shape to its cache:

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

The generator sets `target` for every row with `--target preview|watch_next`
(default `preview`), so a document can drive either table. On the box the same
knob is the **Destination** button, which needs no rebuild and no PC.

The example above is Spain. `channelId` and `title` are per country:
`netflix-top-series-es` carries `"Netflix · Top series España"` for Spain, and
`netflix-top-series-de` carries `"Netflix · Top series Alemania"` on a Spanish
screen for Germany, so two countries never share a channel.

`target` selects the provider table and is the one knob worth understanding:

- `preview` (default) — a `TYPE_PREVIEW` channel plus preview programs. This is
  the standard "recommendations" surface.
- `watch_next` — Watch Next programs, the same table the official apps write to.
  Use it if a launcher ignores preview channels; the trade-off is that the row
  lands next to *Seguir viendo* instead of being its own row.

`link` is the deep link the app tries first. Netflix rows carry
`https://www.netflix.com/watch/<id>`, which the Netflix app registers. Prime rows
carry the `standardWebURL` JustWatch already knows,
`https://app.primevideo.com/detail?gti=<gti>`. Either way the published intent is
the one the device resolved, not a guess.

## Verification

Offline checks, no network and no TV needed:

```bash
python3 tests/test_fetch_top.py          # 11/11, the Python reference logic
python3 tests/test_app.py                # 6/6, static guards on the app sources
python3 tools/fetch_top.py --offline tests/fixtures --out /tmp/top.json
```

The on-box builder is checked by compiling `TopSource.java` with the JDK and
running `tests/java/TopSourceCheck.java` on the same fixtures, then comparing its
document with the reference document `tools/fetch_top.py` produces from those
fixtures. It needs a test-only `org.json` jar; the suite never downloads it:

```bash
TVFEEDER_JSON_JAR=/path/json-20240303.jar python3 tests/test_autonomous_feeder.py
```

The jar is Maven Central `org.json:json:20240303`, used for the host run only:
production code takes `org.json` from the platform, exactly as before. This
proves the page payload parsing, the parent-show titles and the document shape,
and it fails if the app starts fetching the world-wide dataset again (S4). It does **not** prove that Netflix or JustWatch
answer an Android client, and the card resolution in `Feeder` needs the box (see
*Limits*).

On the box, use the app's **Diagnostics** button. The `content query` commands
below read nothing from a PC shell: `TvProvider` scopes every query to the
calling package, and `adb shell` is not a package, so it sees zero rows.

```bash
# returns "No result found" from a PC shell, by design
adb shell content query --uri content://android.media.tv/channel \
  --projection package_name,display_name,type,searchable
```

What a PC can still see is the sync itself, and the intent the app resolved for
each card:

```bash
adb logcat -d -s TvFeeder:* | tail -8
```

```
Published 4 rows, 40 items, 0 without artwork, 0 errors
- netflix-top-series-es (watch_next): 10 items, week 2026-10-18
  opens: intent:https://www.netflix.com/watch/81652678#Intent;action=android.intent.action.VIEW;component=com.netflix.ninja/.ui.MainActivity;S.title=Below;end
```

That `opens:` line is what a tap on the TV fires; you can also start it from a
PC shell.

```bash
adb shell am start -a android.intent.action.VIEW \
  -n com.netflix.ninja/.ui.MainActivity -d https://www.netflix.com/watch/81652678
```

## Why the app looks the way it does

- `WRITE_EPG_DATA` and `READ_EPG_DATA` are `normal` permissions, so any app can
  hold them; `ACCESS_ALL_EPG_DATA` is `signature|privileged` and is deliberately
  not requested.
- `TvProvider` throws `SecurityException` when a caller without
  `ACCESS_ALL_EPG_DATA` passes a non-empty `selection` to `query`, `update` or
  `delete`. The app therefore passes `null` selections everywhere: the provider
  already scopes them to the calling package.
- `Channels.COLUMN_BROWSABLE` is refused the same way: on the Mi Box S every
  insert failed with `SecurityException: Not allowed to access
  Channels.COLUMN_BROWSABLE` until the app stopped writing that column, which
  `TvProvider` allows only to holders of `ACCESS_ALL_EPG_DATA`. The column
  already defaults to 1, so nothing is lost. `tests/test_app.py` guards it.
- A non-empty `selection` is refused too, on `query`, `update` and `delete`:
  `SecurityException: Selection not allowed for
  content://android.media.tv/channel/<id>`. Channels survive the first sync
  (insert path) and fail on the second (update path) unless the selection is
  dropped. `tests/test_app.py` guards it.
- Switching `target` is not free: `clearOwnPrograms()` empties both tables, so
  after a **Destination → Watch Next** sync the preview **channels stay but lose
  every item** and a launcher shows them as empty rows. Publish the same target
  the launcher is showing, and press **Sync** after switching.
- Every card carries an intent the device already resolved (S3a): the deep link
  if an activity handles it, the app's launch intent otherwise, and nothing at
  all if neither resolves. The title rides along as an `S.title` extra so the ten
  cards of one app keep distinct URIs and a launcher does not merge them.
- The autonomous fetch runs on the job thread with 20 s connect/read timeouts per
  request, five requests. A failing source no longer fails the sync: it is named in
  the report and the other rows publish. It never runs on the UI thread.
- Reading *another* package's rows needs `android.permission.READ_TV_LISTINGS`
  (`dangerous`, granted at runtime) **and** the row must have `searchable=1`,
  which is why the feeder marks its own channels and programs that way.
- Deleting and re-publishing happens in one pass at the start of each sync, and
  only when at least one row is valid, so a failed download never wipes the
  rows you already see.
- Changing the country or the language republishes under new channel ids, so the
  channels this package owns that the new document does not contain are deleted:
  no empty row from the previous country stays on the launcher, and no other
  package's channels are touched.

## Limits

- The on-box sync succeeds. On 2026-11-11 it published 4 rows and 40 items, 0
  without artwork and 0 errors, for the Netflix week 2026-11-08, built from the two
  Spain pages plus the two JustWatch queries. The 33 MB world-wide dataset is never
  fetched, and a page body is ~200 KB against the 1 MiB cap (S4).
- Verified on a Mi Box S (MIBOX4, Android TV 9) **before** this change: install,
  the `READ_TV_LISTINGS` grant, publishing 4 channels and 40 Watch Next items,
  and the boot/job scheduling of later runs. Focus order and the report screen
  were exercised with a D-pad.
- Card intents are proven on the device (S3a): an `app_process` probe parsed the
  published strings with `Intent.parseUri` at flags 0 and `URI_INTENT_SCHEME` and
  resolved them to `com.netflix.ninja/.MainActivity` and
  `com.amazon.amazonvideo.livingroom/com.amazon.ignition.IgnitionActivity`, while
  the previous action-less package intent resolved to nothing. What is still open
  is a human tap on the TV, and a launcher that hides preview channels shows
  nothing until its own channel switch is on (Projectivy, see below).
- www.netflix.com became unreachable from this connection for about ten minutes
  during testing: its edge addresses timed out on a raw TCP connect while
  `apis.justwatch.com` answered 200. The app reported the failure and left the
  published rows untouched, which is the specified behaviour (L15).
- The Python generator needs `curl` because netflix.com resets HTTP/1.1 requests
  from scripted clients. The box uses `HttpURLConnection`, so if Netflix resets
  it the same way, the report names that source and the Prime rows still publish;
  when nothing at all publishes, the previous rows stay. The external top.json
  mode is then the fallback until the transport works.
- `targetSdkVersion 28` blocks cleartext HTTP by default, so the manifest has to
  set `android:usesCleartextTraffic="true"` for the external mode; without it
  every sync fails with `IOException: Cleartext HTTP traffic to ... not
  permitted`. `tests/test_app.py` guards it.
- Card resolution calls `PackageManager.queryIntentActivities`, which needs a
  `<queries>` element once `targetSdkVersion` reaches 30. At 28 all packages are
  visible, which is why the manifest needs no change today.
- Netflix poster art comes from its CDN; if those URLs change, rows publish
  without artwork and the report counts them.
- A launcher that ignores `TYPE_PREVIEW` channels will not show the rows at all.
  Projectivy is one of them until its own **Categorías / Canales → Habilitar
  canales** switch is on; with `target` set to `watch_next` the rows land in its
  *Continuar viendo* row instead, next to what the official apps publish.
- Card titles are Netflix's own page titles. They usually match the weekly
  dataset word for word, but not always (see *Autonomous mode*), so a title on the
  TV can differ from the one on `netflix.com/tudum/top10`'s dataset view.
