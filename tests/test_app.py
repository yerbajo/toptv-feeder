#!/usr/bin/env python3
"""Static checks on the app sources. Plain asserts, no test framework needed.

    python3 tests/test_app.py
"""
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
APP = HERE.parent / "app"
MANIFEST = (APP / "AndroidManifest.xml").read_text(encoding="utf-8")
FEEDER = (APP / "src" / "com" / "alberto" / "tvfeeder" / "Feeder.java").read_text(encoding="utf-8")


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
