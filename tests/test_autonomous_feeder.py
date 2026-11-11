#!/usr/bin/env python3
"""Checks for the on-box document builder, app/src/com/alberto/tvfeeder/TopSource.java.

Compiles TopSource.java (JDK only, no android.jar) with tests/java/TopSourceCheck.java
against a JVM org.json jar, runs it on tests/fixtures, and compares the rows it writes
with the reference output of the unmodified tools/fetch_top.py on the same fixtures.

The jar is a test-only dependency and is never downloaded by this suite:

    TVFEEDER_JSON_JAR=/path/json-20240303.jar python3 tests/test_autonomous_feeder.py
    python3 tests/test_autonomous_feeder.py --jar /path/json-20240303.jar

Pinned provenance: Maven Central org.json:json:20240303. Without a jar (or a JDK) the
Java checks fail loudly instead of passing: nothing here stands in for org.json.
"""
import json
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(ROOT / "tools"))

import fetch_top as ft  # noqa: E402

SRC = ROOT / "app" / "src" / "com" / "alberto" / "tvfeeder"
TOP_SOURCE = SRC / "TopSource.java"
FEEDER = SRC / "Feeder.java"
ACTIVITY = SRC / "MainActivity.java"
HARNESS = HERE / "java" / "TopSourceCheck.java"
FIX = HERE / "fixtures"
JAR_ENV = "TVFEEDER_JSON_JAR"
JAR_COORDINATE = "org.json:json:20240303"
RUN = {}


def json_jar():
    argv = sys.argv[1:]
    if "--jar" in argv:
        return pathlib.Path(argv[argv.index("--jar") + 1])
    value = os.environ.get(JAR_ENV)
    return pathlib.Path(value) if value else None


# Netflix's own Spain page and its weekly dataset disagree on one title in these
# fixtures: same video, "Ice Age 5: Collision Course" on the page (the only naming
# the box can read) and "Ice Age: Collision Course" in the dataset. The box
# publishes the page title; every other field of every row must still match the
# reference exactly, so this map is the single allowed divergence.
TITLE_FROM_PAGE = {
    "https://www.netflix.com/watch/80096067": (
        "Ice Age 5: Collision Course",
        "Ice Age: Collision Course",
    ),
}


def reference_rows():
    """The rows tools/fetch_top.py builds from the same fixtures, at target preview."""
    dataset = (FIX / "tudum-es-week.tsv").read_text(encoding="utf-8")
    pages = {
        "Films": (FIX / "tudum-spain.html").read_text(encoding="utf-8"),
        "TV": (FIX / "tudum-spain-tv.html").read_text(encoding="utf-8"),
    }
    movie = json.loads((FIX / "justwatch-movie-es.json").read_text(encoding="utf-8"))
    show = json.loads((FIX / "justwatch-show-es.json").read_text(encoding="utf-8"))

    def fetch_json(payload):
        return movie if payload["variables"]["objectType"] == "MOVIE" else show

    prime, notices = ft.build_prime_rows(fetch_json=fetch_json)
    assert notices == [], notices
    return ft.apply_target(ft.build_netflix_rows(dataset, pages) + prime, "preview")


def expected_rows():
    """Reference rows with the one page-only title the box is able to publish."""
    rows = reference_rows()
    seen = set()
    for row in rows:
        for item in row["items"]:
            pair = TITLE_FROM_PAGE.get(item["link"])
            if pair is None:
                continue
            page_title, dataset_title = pair
            assert item["title"] == dataset_title, (
                f"the documented title divergence drifted: {item['link']} is {item['title']!r}")
            item["title"] = page_title
            seen.add(item["link"])
    assert seen == set(TITLE_FROM_PAGE), (
        f"the documented title divergence is stale: {set(TITLE_FROM_PAGE) - seen}")
    return rows


def harness():
    """Compiles and runs the Java check once; returns (process, document)."""
    if RUN:
        return RUN["done"], RUN["document"]
    jar = json_jar()
    assert jar is not None, (
        "no org.json jar: pass " + JAR_ENV + "=/path/json-20240303.jar (or --jar PATH). "
        "The suite never downloads " + JAR_COORDINATE + " itself."
    )
    assert jar.is_file(), "org.json jar not found: " + str(jar)
    javac, java = shutil.which("javac"), shutil.which("java")
    assert javac and java, "no JDK on PATH: the Java fixture check cannot run"
    work = pathlib.Path(tempfile.mkdtemp(prefix="tvfeeder-java-"))
    try:
        classes = work / "classes"
        classes.mkdir()
        output = work / "top.json"
        compiled = subprocess.run(
            [javac, "-nowarn", "-cp", str(jar), "-d", str(classes), str(TOP_SOURCE), str(HARNESS)],
            capture_output=True, text=True)
        assert compiled.returncode == 0, "javac failed:\n" + compiled.stdout + compiled.stderr
        done = subprocess.run(
            [java, "-cp", os.pathsep.join([str(classes), str(jar)]),
             "com.alberto.tvfeeder.TopSourceCheck", str(FIX), str(output)],
            capture_output=True, text=True)
        document = None
        if output.exists():
            document = json.loads(output.read_text(encoding="utf-8"))
        RUN["done"], RUN["document"] = done, document
    finally:
        shutil.rmtree(work)
    return RUN["done"], RUN["document"]


def test_java_harness_passes_its_own_checks():
    done, _ = harness()
    assert done.returncode == 0, done.stdout + done.stderr
    assert "checks passed" in done.stdout, done.stdout + done.stderr


def test_java_document_matches_the_reference_generator():
    _, document = harness()
    assert document is not None, "the harness wrote no document"
    assert document["schema"] == 1, document.get("schema")
    assert document["generated"], document.get("generated")
    assert document["rows"] == expected_rows(), json.dumps(document["rows"], ensure_ascii=False, indent=1)


def test_app_never_downloads_the_worldwide_dataset():
    source = TOP_SOURCE.read_text(encoding="utf-8")
    assert "all-weeks-countries" not in source, (
        "the app must not fetch the 33 MB world-wide TSV: it does not fit the body cap (S4)")
    assert "tudum/top10/data" not in source, "only the two Spain pages are fetched on the box"


def test_a_page_without_payload_aborts_the_whole_sync():
    done, _ = harness()
    assert "ok   a page without payload aborts the row" in done.stdout, done.stdout + done.stderr
    assert "ok   one bad page aborts both Netflix rows" in done.stdout, done.stdout + done.stderr


def test_feeder_defaults_to_the_on_box_sources():
    feeder = FEEDER.read_text(encoding="utf-8")
    assert "192.168." not in feeder, "the PC's LAN URL must not stay the default source"
    assert "TopSource.fetch" in feeder, "Feeder must build the document on the box"
    assert "renameTo" in feeder, "the last good copy must be replaced in one rename"
    assert "TopSource.recover" in feeder, "an offline sync must not churn published rows"


def test_activity_accepts_an_empty_url():
    activity = ACTIVITY.read_text(encoding="utf-8")
    assert "url_missing" not in activity, "an empty URL now means the built-in sources (S2)"


def test_card_intent_code_paths():
    """Static guard for S3/S3a only: device resolution itself is T3."""
    feeder = FEEDER.read_text(encoding="utf-8")
    assert "queryIntentActivities" in feeder, "a card is only published when the device resolves it"
    assert "getLaunchIntentForPackage" in feeder, "the app launch intent is the fallback"
    assert "Intent.URI_INTENT_SCHEME" in feeder, "launchers get a full intent URI, not a pinned package"


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
