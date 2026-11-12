#!/usr/bin/env python3
"""Static checks on the app sources. Plain asserts, no test framework needed.

    python3 tests/test_app.py
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
APP = HERE.parent / "app"
SRC = APP / "src" / "com" / "alberto" / "tvfeeder"
MANIFEST = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")
FEEDER = (SRC / "Feeder.java").read_text(encoding="utf-8")
TOP_SOURCE = (SRC / "TopSource.java").read_text(encoding="utf-8")
COUNTRY = (SRC / "Country.java").read_text(encoding="utf-8")
ACTIVITY = (SRC / "MainActivity.java").read_text(encoding="utf-8")
LAYOUT = (APP / "res" / "layout" / "main.xml").read_text(encoding="utf-8")

# The countries the sources answer for, with the Tudum slug each one uses (S11).
COUNTRIES = {
    "ES": "spain",
    "DE": "germany",
    "NL": "netherlands",
    "FR": "france",
    "IT": "italy",
    "GB": "united-kingdom",
    "US": "united-states",
    "PT": "portugal",
    "BE": "belgium",
    "AT": "austria",
    "CH": "switzerland",
    "SE": "sweden",
    "PL": "poland",
    "IE": "ireland",
    "MX": "mexico",
    "BR": "brazil",
    "AR": "argentina",
}



def test_cleartext_http_allowed():
    # The feeder only talks to http://<lan-ip>:8080/top.json. From targetSdk 28
    # up, cleartext is blocked by default and every sync fails with
    # "Cleartext HTTP traffic to ... not permitted".
    application = re.search(r"<application[^>]*>", MANIFEST).group(0)
    assert 'android:usesCleartextTraffic="true"' in application, (
        "application has no android:usesCleartextTraffic=\"true\"; "
        "the plain-http sync will be refused at runtime"
    )


def test_never_writes_channel_browsable():
    # TvProvider.blockIllegalAccessToChannelsSystemColumns() throws
    # SecurityException for Channels.COLUMN_BROWSABLE unless the caller holds
    # ACCESS_ALL_EPG_DATA (signature|privileged), which a sideloaded app cannot.
    writes = [ln.strip() for ln in FEEDER.splitlines()
              if "values.put(COL_BROWSABLE" in ln and not ln.strip().startswith("//")]
    assert not writes, (
        "Feeder writes Channels.COLUMN_BROWSABLE; TvProvider rejects that write "
        f"for non-privileged callers: {writes}"
    )


def test_never_passes_a_selection():
    # TvProvider rejects a non-empty selection with
    # "SecurityException: Selection not allowed for content://android.media.tv/channel/<id>"
    # unless the caller holds ACCESS_ALL_EPG_DATA. It already scopes every
    # query, update and delete to the calling package.
    offenders = [ln.strip() for ln in FEEDER.splitlines()
                 if '"=?"' in ln and not ln.strip().startswith("//")]
    assert not offenders, (
        "Feeder passes a selection to TvProvider, which refuses it: "
        f"{offenders}"
    )


def test_cards_are_written_back_to_front():
    # Projectivy orders a channel by insertion and draws the newest card first, so
    # writing rank 1 first made the row open on the last title of the ranking. Both
    # publish loops therefore run backwards and the weight still encodes the rank for
    # launchers that honour COLUMN_WEIGHT.
    loops = [ln.strip() for ln in FEEDER.splitlines()
             if "for (int i = row.items.size() - 1; i >= 0; i--)" in ln]
    assert len(loops) == 2, (
        "every publish loop must write the cards back to front, found "
        f"{len(loops)} of them"
    )
    assert "COLUMN_WEIGHT, row.items.size() - i" in FEEDER, (
        "the preview weight must keep rank 1 at the highest value"
    )


def test_keystore_survives_a_clean_build():
    # app/build is wiped between builds; a key stored there is recreated and the
    # new APK can no longer upgrade the copy installed on the TV.
    script = (HERE.parent / "tools" / "build-apk.sh").read_text(encoding="utf-8")
    line = next((ln for ln in script.splitlines() if ln.startswith("KEYSTORE=")), "")
    assert line, "build-apk.sh does not set KEYSTORE"
    assert '"$BUILD' not in line, f"the keystore lives in the disposable build dir: {line}"


def test_country_table_is_the_verified_set():
    # S11: ISO code plus the Tudum slug, one entry per country the sources answer
    # for. A code that is not here can never reach the network.
    pairs = dict(re.findall(r'\{"([A-Z]{2})", "([a-z-]+)"\}', TOP_SOURCE))
    assert pairs == COUNTRIES, (
        f"the country table drifted: missing {sorted(set(COUNTRIES) - set(pairs))}, "
        f"unexpected {sorted(set(pairs) - set(COUNTRIES))}"
    )


def test_country_defaults_from_the_device_locale():
    # S11: the TV's own country when the table has it, otherwise ES.
    assert "Locale.getDefault().getCountry()" in COUNTRY, (
        "the default country must come from the device locale"
    )
    assert 'DEFAULT_COUNTRY = "ES"' in TOP_SOURCE, "the fallback country must be ES"
    assert "TopSource.supported(device)" in COUNTRY, (
        "an unsupported device country must fall back to ES, not be used"
    )
    # The screen and the sync resolve the country the same way, and the choice is
    # stored in the same preferences as the language.
    assert "Country.saved(context)" in FEEDER, "the sync must collect the selected country"
    assert "Country.saved(this)" in ACTIVITY, "the screen must show the selected country"
    assert 'PREF_COUNTRY = "country"' in COUNTRY
    assert "Feeder.prefs(context)" in COUNTRY, "the choice must live in the app preferences"
    assert 'android:id="@+id/country"' in LAYOUT, "the screen has no country button"
    assert "setSingleChoiceItems" in ACTIVITY, (
        "the country button must open a single-choice list"
    )
    assert "new Locale(\"\", country).getDisplayCountry" in TOP_SOURCE, (
        "country names must come from Locale, not hardcoded strings"
    )


def test_rows_and_sources_follow_the_country():
    # S12/S13: ids carry the lowercased code, titles and sources the country.
    for prefix in ("netflix-top-series-", "netflix-top-films-",
                   "prime-top-series-", "prime-top-films-"):
        assert f'"{prefix}" + cc' in TOP_SOURCE, f"{prefix}<cc> is not built from the country"
    for title in ("Netflix · Top series ", "Netflix · Top películas ",
                  "Prime Video · Top series ", "Prime Video · Top películas "):
        assert f'"{title}" + name' in TOP_SOURCE, f"{title}<country> is not built"
    for suffix in ('+ "/tv"',):
        assert suffix in TOP_SOURCE
    assert 'TUDUM_BASE = "https://www.netflix.com/tudum/top10/"' in TOP_SOURCE, (
        "the Tudum base URL drifted"
    )
    assert "TUDUM_BASE + slug" in TOP_SOURCE, (
        "the Tudum page must be built from the country slug"
    )
    assert 'variables.put("country", country)' in TOP_SOURCE, "JustWatch must follow the country"
    assert 'variables.put("language", language)' in TOP_SOURCE, (
        "JustWatch must follow the screen language"
    )
    assert "country.toLowerCase(Locale.US)" in TOP_SOURCE, "ids use the lowercased code"


def test_one_broken_source_does_not_take_the_others_down():
    # S15: each source is built inside its own try, and only an empty document
    # aborts the sync (S5 then keeps the previous rows).
    assert "netflixNotices" in TOP_SOURCE and "justwatchNotices" in TOP_SOURCE
    assert "appleNotices" in TOP_SOURCE, "the Apple charts need their own notices"
    assert "no source produced rows" in TOP_SOURCE, (
        "the sync may only fail when nothing at all could be built"
    )
    assert TOP_SOURCE.count("catch (") >= 4, "each source needs its own catch"


def test_stale_channels_only_delete_this_package_s():
    # S14: switching country must not leave empty rows, and a foreign package's
    # channels are never touched.
    assert "deleteStaleChannels" in FEEDER, "a new document must clean the rows it dropped"
    body = FEEDER.split("deleteStaleChannels(Context context")[1].split("/**")[0]
    assert "pkg.equals(" in body, (
        "the cleanup must re-check package_name, because READ_TV_LISTINGS also returns "
        "other packages' channels"
    )
    assert "withAppendedId(TvContract.Channels.CONTENT_URI, id)" in body, (
        "a stale channel must be deleted by id"
    )
    assert "null, null)" in body, "the delete must not pass a selection"
    assert '"=?"' not in body, "the delete must not pass a selection"
    # The row key written on the channel is what the cleanup compares against.
    assert "values.put(COL_DESCRIPTION, row.key())" in FEEDER
    # Only a publish that wrote something deletes, so a failed sync keeps the
    # previous rows untouched (S5).
    call = FEEDER.index("deleteStaleChannels(context, pkg, keep)")
    guard = FEEDER.rindex("if (published > 0", 0, call)
    between = FEEDER[guard:call]
    assert between.count("\n") <= 2 and "}" not in between, (
        "the cleanup must sit inside the successful-publish guard"
    )


def test_every_language_defines_the_same_strings():
    # A key missing from values-es/ silently falls back to English on a Spanish TV.
    def keys(path):
        return set(re.findall(r'<string name="([^"]+)"', path.read_text(encoding="utf-8")))

    base = keys(APP / "res" / "values" / "strings.xml")
    for lang in sorted(p.name for p in (APP / "res").iterdir() if p.name.startswith("values")):
        if lang == "values":
            continue
        other = keys(APP / "res" / lang / "strings.xml")
        assert other == base, (
            f"res/{lang}/strings.xml differs from res/values: "
            f"missing {sorted(base - other)}, unexpected {sorted(other - base)}"
        )


def test_the_external_url_document_does_not_prune_rows_it_omits():
    # S14 covers a country or language switch. A document fetched from the legacy
    # external URL plans no rows, so pruning it would delete rows that document
    # simply does not list (the base had no pruning at all, L41.4).
    assert "if (published > 0 && !planned.isEmpty()) {" in FEEDER, (
        "stale pruning must need both a published row and a planned document"
    )


def test_every_platform_row_is_planned():
    # S7: the keep set must hold the new rows too, or a country switch deletes them.
    assert "for (Platform platform : JUSTWATCH_PLATFORMS)" in TOP_SOURCE, (
        "plannedIds must plan every JustWatch platform")
    assert "installed.has(APPLE_PACKAGE)" in TOP_SOURCE, (
        "plannedIds must plan the Apple rows only where the app exists")
    assert "if (!installed.has(platform.app))" in TOP_SOURCE, (
        "plannedIds must skip platforms this TV does not have, or stale cleanup keeps them")
    for package in ("com.apple.atve.androidtv.appletv", "com.disney.disneyplus", "com.wbd.stream",
                    "es.plus.yomvi", "com.skyshowtime.skyshowtime.google",
                    "com.filmin.androidtv"):
        assert package in TOP_SOURCE, package


def test_absent_platforms_are_never_fetched():
    # S12: a platform this TV does not have must not even be downloaded, so no row is
    # built for it and nothing has to be thrown away afterwards.
    assert "static String fetch(Installed installed" in TOP_SOURCE, (
        "the on-box fetch must know what is installed")
    assert "if (installed.has(NETFLIX_PACKAGE))" in TOP_SOURCE
    assert "if (!installed.has(platform.app))" in TOP_SOURCE, (
        "a JustWatch platform must be skipped before its request")
    assert "installed.has(APPLE_PACKAGE)" in TOP_SOURCE, "the Apple charts must be gated too"
    assert "TopSource.fetch(installedApps(context)" in FEEDER, (
        "the sync must pass what the device actually has")


def test_a_failed_source_keeps_its_row():
    # S14 deletes the rows of other countries, never the row of a source that failed
    # this run: the keep set is the published rows plus every planned id.
    assert "keep.add(channelKey(channelId));" in FEEDER, (
        "the keep set must hold channel keys built from the planned ids"
    )
    assert 'return "tvfeeder:" + channelId;' in FEEDER, (
        "Row.key and the stale comparison must agree on the stored description"
    )
    assert "TopSource.plannedIds(" in FEEDER, "sync must plan the selected country's rows"
    assert 'ids.add("prime-top' not in TOP_SOURCE, (
        "the planned ids must come from primeRows/categories, not from a copy"
    )
    # One cache per country, so an offline sync cannot resurrect another country.
    assert "last_good-" in FEEDER and "Country.saved(context).toLowerCase" in FEEDER, (
        "the cached copy must be per country"
    )


def main():
    tests = [(n, f) for n, f in sorted(globals().items()) if n.startswith("test_")]
    failed = 0
    for name, fn in tests:
        try:
            fn()
        except AssertionError as exc:
            failed += 1
            print(f"FAIL {name}: {exc}")
        else:
            print(f"ok   {name}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
