#!/usr/bin/env python3
"""Checks for tools/fetch_top.py. Plain asserts, no test framework needed.

    python3 tests/test_fetch_top.py
"""
import json
import pathlib
import sys
import tempfile

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tools"))

import fetch_top as ft  # noqa: E402

FIX = HERE / "fixtures"
DATASET = (FIX / "tudum-es-week.tsv").read_text(encoding="utf-8")
# The Spain page embeds one list per tab: Films at /top10/spain, series at /top10/spain/tv.
PAGES = {
    "Films": (FIX / "tudum-spain.html").read_text(encoding="utf-8"),
    "TV": (FIX / "tudum-spain-tv.html").read_text(encoding="utf-8"),
}
# Germany: synthesised, not captured. The two pages are the minimum payload the
# parser reads (two ranked entities with artwork each), and the dataset is a five
# row dump holding DE plus one MX row that must be filtered out. A real German page
# would add ~200 KB to the tree for the same code path.
DE_DATASET = (FIX / "tudum-de-week.tsv").read_text(encoding="utf-8")
DE_PAGES = {
    "Films": (FIX / "tudum-germany.html").read_text(encoding="utf-8"),
    "TV": (FIX / "tudum-germany-tv.html").read_text(encoding="utf-8"),
}
# Apple: synthesised from the real payload shape (serialized-server-data with
# OrdinalChartLockup items), two files, small enough to read. The real pages are
# ~220 KB each and add no code path.
APPLE_PAGES = {
    "TV": (FIX / "apple-es-tv.html").read_text(encoding="utf-8"),
    "Films": (FIX / "apple-es.html").read_text(encoding="utf-8"),
}



def test_dataset_latest_week_only():
    rows = ft.parse_netflix_dataset(DATASET)
    assert set(rows) == {"TV", "Films"}, sorted(rows)
    for category in ("TV", "Films"):
        items = rows[category]
        assert len(items) == 10, (category, len(items))
        assert [i["rank"] for i in items] == list(range(1, 11)), category
        assert all(i["title"] for i in items), category
        assert {i["week"] for i in items} == {"2026-10-18"}, category


def test_dataset_ignores_other_countries():
    other = (
        "country_name\tcountry_iso2\tweek\tcategory\tweekly_rank\t"
        "show_title\tseason_title\tcumulative_weeks_in_top_10\n"
        "Mexico\tMX\t2026-10-18\tTV\t1\tFoo\tN/A\t1\n"
    )
    assert ft.parse_netflix_dataset(other) == {}


def test_page_artwork_maps_rank_to_art():
    for page, category in (("Films", "MOVIES"), ("TV", "SERIES")):
        art, week = ft.parse_tudum_art(PAGES[page])
        assert week == "2026-10-18", (page, week)
        assert len([k for k in art if k[0] == category]) == 10, (page, len(art))
    films, _ = ft.parse_tudum_art(PAGES["Films"])
    entry = films[("MOVIES", 1)]
    for field in ("poster", "thumb", "link"):
        assert entry[field].startswith("https://"), (field, entry[field])
    assert entry["link"].startswith("https://www.netflix.com/watch/")


def test_rows_join_art_and_survive_mismatch():
    rows = ft.build_netflix_rows(DATASET, PAGES)
    assert [r["channelId"] for r in rows] == [
        "netflix-top-series-es",
        "netflix-top-films-es",
    ], [r["channelId"] for r in rows]
    assert rows[0]["title"] == "Netflix · Top series España"
    assert rows[1]["title"] == "Netflix · Top películas España"
    for row in rows:
        assert len(row["items"]) == 10
        assert all(i["title"] for i in row["items"])
        assert all(i["poster"].startswith("https://") for i in row["items"])
        assert all(i["link"].startswith("https://www.netflix.com/watch/") for i in row["items"])

    stale = {"Films": PAGES["Films"].replace('"weekEndDate":"2026-10-18"', '"weekEndDate":"2026-09-27"'), "TV": PAGES["TV"]}
    assert stale["Films"] != PAGES["Films"]
    degraded = ft.build_netflix_rows(DATASET, stale)
    assert len(degraded) == 2
    assert degraded[0]["channelId"] == "netflix-top-series-es"
    assert degraded[0]["items"][0]["poster"].startswith("https://")
    assert degraded[1]["channelId"] == "netflix-top-films-es"
    assert len(degraded[1]["items"]) == 10
    assert degraded[1]["items"][0]["poster"] == ""
    assert degraded[1]["notices"], "degradation reason must be recorded"


def test_parse_justwatch_listing_shape():
    # Captured responses from apis.justwatch.com/graphql, trimmed to three items
    # and to the Prime offers, so the shape is the real one.
    movie = json.loads((FIX / "justwatch-movie-es.json").read_text(encoding="utf-8"))
    items = ft.parse_justwatch_listing(movie)
    assert [i["title"] for i in items] == ["Proyecto Salvación", "El amor de tu vida", "Normal"], items
    assert items[0]["poster"].startswith("https://images.justwatch.com/poster/"), items[0]["poster"]
    assert "{profile}" not in items[0]["poster"] and "{format}" not in items[0]["poster"]
    assert items[0]["poster"].endswith("/s592/proyecto-salvacion.jpg"), items[0]["poster"]
    # JustWatch hands out the exact Prime deep link for the title.
    assert items[0]["link"].startswith("https://app.primevideo.com/detail?gti=amzn1.dv.gti."), items[0]["link"]
    # The card is 16:9, so the landscape still is the card image and the portrait
    # poster stays as poster art; a listing without backdrops falls back to it.
    assert items[0]["thumb"].startswith("https://images.justwatch.com/backdrop/"), items[0]["thumb"]
    assert items[0]["thumb"].endswith("/s480/proyecto-salvacion.jpg"), items[0]["thumb"]


def test_parse_justwatch_listing_falls_back_to_the_poster():
    movie = json.loads((FIX / "justwatch-movie-es.json").read_text(encoding="utf-8"))
    for edge in movie["data"]["popularTitles"]["edges"]:
        edge["node"]["content"].pop("backdrops", None)
    items = ft.parse_justwatch_listing(movie)
    assert items[0]["thumb"] == items[0]["poster"], "the card image falls back to the poster"


def test_justwatch_request_targets_prime_es():
    payload = ft.justwatch_request("MOVIE", first=7)
    assert payload["variables"] == {"country": "ES", "language": "es", "first": 7, "objectType": "MOVIE"}
    query = payload["query"]
    assert "popularTitles" in query and "sortBy: POPULAR" in query
    assert 'packages: ["prv"]' in query, query
    assert "standardWebURL" in query, query


def test_prime_rows_build_two_rows_without_credentials():
    tv = (FIX / "justwatch-show-es.json").read_text(encoding="utf-8")
    movie = (FIX / "justwatch-movie-es.json").read_text(encoding="utf-8")
    seen = []

    def fake_fetch(payload):
        seen.append(payload["variables"]["objectType"])
        return json.loads(movie if payload["variables"]["objectType"] == "MOVIE" else tv)

    rows, notices = ft.build_prime_rows(fetch_json=fake_fetch)
    assert seen == ["SHOW", "MOVIE"], seen
    assert notices == [], notices
    assert [r["channelId"] for r in rows] == ["prime-top-series-es", "prime-top-films-es"]
    assert rows[0]["app"] == "com.amazon.amazonvideo.livingroom"
    assert rows[0]["source"] == "justwatch-popularity"
    assert len(rows[0]["items"]) == 3 and len(rows[1]["items"]) == 3
    assert rows[0]["items"][0]["title"] == "Tierra de mafiosos"


def test_prime_rows_survive_a_failing_endpoint():
    def failing(payload):
        raise OSError("HTTP 429")

    rows, notices = ft.build_prime_rows(fetch_json=failing)
    assert rows == []
    assert len(notices) == 2 and all("429" in n for n in notices), notices


def test_every_supported_country_has_a_slug_and_a_name():
    # S11: the table is the set of countries the sources answer for.
    assert len(ft.COUNTRY_SLUGS) == 17, sorted(ft.COUNTRY_SLUGS)
    for code, slug in ft.COUNTRY_SLUGS.items():
        assert len(code) == 2 and code.isupper(), code
        assert slug and slug.islower(), (code, slug)
        for language in ("en", "es"):
            name = ft.country_name(code, language)
            assert name and name != code, (code, language, name)


def test_country_changes_the_tudum_urls():
    # S13: the slug is the only thing that changes in the two page URLs.
    assert ft.page_urls("DE") == {
        "Films": "https://www.netflix.com/tudum/top10/germany",
        "TV": "https://www.netflix.com/tudum/top10/germany/tv",
    }, ft.page_urls("DE")
    assert ft.page_urls("ES")["TV"] == "https://www.netflix.com/tudum/top10/spain/tv"
    assert ft.page_urls("GB")["Films"] == "https://www.netflix.com/tudum/top10/united-kingdom"


def test_country_changes_the_row_ids_and_titles():
    # S12: the id carries the lowercased code, the title the country name in the
    # screen's language.
    assert ft.categories("DE", "en") == {
        "TV": ("netflix-top-series-de", "Netflix · Top series Germany", "SERIES"),
        "Films": ("netflix-top-films-de", "Netflix · Top películas Germany", "MOVIES"),
    }, ft.categories("DE", "en")
    assert ft.categories("DE", "es")["TV"][1] == "Netflix · Top series Alemania"
    assert ft.categories("ES", "es")["Films"][:2] == (
        "netflix-top-films-es",
        "Netflix · Top películas España",
    )


def test_rows_follow_the_country():
    rows = ft.build_netflix_rows(DE_DATASET, DE_PAGES, country="DE", language="en")
    assert [r["channelId"] for r in rows] == ["netflix-top-series-de", "netflix-top-films-de"]
    assert [r["title"] for r in rows] == [
        "Netflix · Top series Germany",
        "Netflix · Top películas Germany",
    ]
    assert [len(r["items"]) for r in rows] == [2, 2]
    assert rows[0]["items"][0]["title"] == "Serie Alemana Eins"
    assert rows[0]["week"] == "2026-11-08"
    assert rows[1]["items"][0]["poster"].startswith("https://")
    # The ES dump has no DE row, so the same call yields nothing for Germany.
    assert ft.build_netflix_rows(DATASET, PAGES, country="DE") == []


def test_country_reaches_the_justwatch_query():
    payload = ft.justwatch_request("SHOW", first=5, country="DE", language="en")
    assert payload["variables"] == {
        "country": "DE",
        "language": "en",
        "first": 5,
        "objectType": "SHOW",
    }, payload["variables"]


def test_prime_rows_follow_the_country():
    movie = json.loads((FIX / "justwatch-movie-es.json").read_text(encoding="utf-8"))

    def fake_fetch(payload):
        assert payload["variables"]["country"] == "DE", payload["variables"]
        return movie

    rows, notices = ft.build_prime_rows(fetch_json=fake_fetch, country="DE", language="en")
    assert notices == [], notices
    assert [r["channelId"] for r in rows] == ["prime-top-series-de", "prime-top-films-de"]
    assert rows[0]["title"] == "Prime Video · Top series Germany"
    assert rows[1]["title"] == "Prime Video · Top películas Germany"


def test_one_source_failing_keeps_the_other_rows():
    # S15: JustWatch down, so the Netflix rows are published with the reason on them.
    def failing(payload):
        raise OSError("HTTP 500")

    rows, notices = ft.build_rows(DE_DATASET, DE_PAGES, country="DE", language="en",
                                  fetch_json=failing)
    assert [r["channelId"] for r in rows] == ["netflix-top-series-de", "netflix-top-films-de"]
    assert len(rows[0]["items"]) == 2
    assert notices and any("JustWatch" in n for n in notices), notices
    assert any("JustWatch" in n for n in rows[0]["notices"]), rows[0]["notices"]


def test_tudum_failing_keeps_the_prime_rows():
    # S15: no German dataset, so only the two Prime rows are published.
    movie = json.loads((FIX / "justwatch-movie-es.json").read_text(encoding="utf-8"))

    def fake_fetch(payload):
        return movie

    rows, notices = ft.build_rows("", {}, country="DE", language="en", fetch_json=fake_fetch)
    # Every JustWatch-backed platform survives a dead Tudum, in table order.
    expected = []
    for platform in ft.JUSTWATCH_PLATFORMS:
        expected += ["%s-top-series-de" % platform, "%s-top-films-de" % platform]
    assert [r["channelId"] for r in rows] == expected
    for row in rows:
        assert any("Tudum" in n for n in row["notices"]), (row["channelId"], row["notices"])
    assert notices


def test_country_without_justwatch_items_keeps_the_netflix_rows():
    # S15: a country can come back thin, and that must not cost the Netflix rows.
    empty = {"data": {"popularTitles": {"edges": []}}}
    rows, notices = ft.build_rows(DE_DATASET, DE_PAGES, country="DE", language="en",
                                  fetch_json=lambda payload: empty)
    assert [r["channelId"] for r in rows] == ["netflix-top-series-de", "netflix-top-films-de"]
    assert all(len(r["items"]) == 2 for r in rows)
    assert any("no series" in n for n in notices), notices
    assert any("JustWatch" in n for n in rows[1]["notices"]), rows[1]["notices"]


def test_a_run_that_fetched_nothing_does_not_report_success(tmp=None):
    # S15 lets one source fail; a run where every source failed must not look like a
    # written ranking, or an outage silently becomes an empty launcher day (L41.1).
    def boom(*args, **kwargs):
        raise RuntimeError("network down")

    out = pathlib.Path(tempfile.mkdtemp()) / "top.json"
    original = ft.fetch
    ft.fetch = boom
    try:
        code = ft.main(["--out", str(out), "--no-prime", "--no-extra", "--max-items", "2"])
    finally:
        ft.fetch = original
    assert code != 0, "a run with no reachable source exited 0"
    assert not out.exists(), "a run that fetched nothing still wrote a document"


def test_apple_chart_is_ordered_by_its_own_ordinal():
    # S6: the store's ordinal is the ranking, not the order the shelf happens to use.
    items = ft.parse_apple_chart(APPLE_PAGES["TV"])
    assert [i["title"] for i in items] == ["Slow Horses", "Sticks: cracks del golf", "Nocturno"]
    assert items[0]["link"] == "https://tv.apple.com/es/show/slow-horses/umc.cmc.aaa"


def test_apple_poster_uses_the_card_profile():
    items = ft.parse_apple_chart(APPLE_PAGES["Films"])
    assert items[0]["poster"] == "https://is1-ssl.mzstatic.com/image/thumb/111/592x888nr.jpg"
    assert items[0]["thumb"] == items[0]["poster"]


def test_apple_chart_urls_follow_the_country():
    # S6: one storefront per country, and the collection id identifies the chart.
    es = ft.apple_chart_urls("ES")
    de = ft.apple_chart_urls("DE")
    assert "/es/" in es["TV"] and "uts.col.ChartsShows.tvs.sbd.4000" in es["TV"]
    assert "uts.col.ChartsMovies" in es["Films"]
    assert "/de/" in de["TV"] and de["TV"] != es["TV"]


def test_apple_rows_carry_the_platform_and_the_country():
    rows = ft.build_apple_rows(APPLE_PAGES, country="DE", language="es")
    assert [r["channelId"] for r in rows] == ["apple-top-series-de", "apple-top-films-de"]
    assert rows[0]["title"] == "Apple TV · Top series Alemania"
    assert rows[1]["title"] == "Apple TV · Top películas Alemania"
    assert {r["app"] for r in rows} == {ft.APPLE_PACKAGE}


def test_apple_chart_without_payload_keeps_the_other_rows():
    # S9: a broken Apple page is reported and costs nothing else.
    rows, notices = ft.build_rows(DE_DATASET, DE_PAGES, country="DE", language="en",
                                  fetch_json=lambda p: {"data": {"popularTitles": {"edges": []}}},
                                  apple_pages={"TV": "<html>no payload</html>"})
    assert "apple-top-series-de" not in [r["channelId"] for r in rows]
    assert any("Apple" in n for n in notices), notices
    assert any("netflix-top-series-de" == r["channelId"] for r in rows)


def test_each_justwatch_platform_asks_for_its_own_package():
    # S6: one filter per platform, so a Disney+ row never lists Max titles.
    for platform, package in (("prime", "prv"), ("disney", "dnp"), ("max", "mxx")):
        meta = ft.JUSTWATCH_PLATFORMS[platform]
        query = ft.justwatch_request("SHOW", packages=meta["packages"])["query"]
        assert '["%s"]' % package in query, (platform, query)


def test_platform_link_ignores_another_providers_offer():
    # S6: Max titles also carry Movistar+ offers, which must never land on the card.
    offers = [{"monetizationType": "FLATRATE", "package": {"shortName": "mvp"},
               "standardWebURL": "http://wl.movistarplus.es/ficha/?id=1"},
              {"monetizationType": "FLATRATE", "package": {"shortName": "mxx"},
               "standardWebURL": "https://play.max.com/video/watch/abc"}]
    assert ft.platform_link(offers, ("mxx",)) == "https://play.max.com/video/watch/abc"
    assert ft.platform_link(offers, ("dnp",)) == ""


def test_extra_platform_rows_are_planned_with_their_own_ids_and_apps():
    # S7: ids carry platform and country, and each row names its app.
    movie = json.loads((FIX / "justwatch-movie-es.json").read_text(encoding="utf-8"))
    rows, _ = ft.build_rows(DE_DATASET, DE_PAGES, country="DE", language="en",
                            fetch_json=lambda p: movie, apple_pages=APPLE_PAGES)
    ids = [r["channelId"] for r in rows]
    for expected in ("apple-top-series-de", "apple-top-films-de", "disney-top-series-de",
                     "disney-top-films-de", "max-top-series-de", "max-top-films-de"):
        assert expected in ids, ids
    apps = {r["channelId"]: r["app"] for r in rows}
    assert apps["apple-top-series-de"] == ft.APPLE_PACKAGE
    assert apps["disney-top-films-de"] == ft.DISNEY_PACKAGE
    assert apps["max-top-series-de"] == ft.MAX_PACKAGE


def test_build_rows_applies_the_target_to_every_row():
    rows, _ = ft.build_rows(DE_DATASET, DE_PAGES, country="DE", language="en",
                            target="watch_next", fetch_json=lambda payload: {"data": {}})
    assert rows and {r["target"] for r in rows} == {"watch_next"}, [r.get("target") for r in rows]


def test_apply_target_sets_every_row():
    rows = ft.build_netflix_rows(DATASET, PAGES)
    assert all(r.get("target", "preview") == "preview" for r in rows)
    ft.apply_target(rows, "watch_next")
    assert {r["target"] for r in rows} == {"watch_next"}
    assert all(len(r["items"]) == 10 for r in rows)


def test_document_is_serialisable():
    doc = ft.document(
        [
            {
                "channelId": "x",
                "title": "t",
                "app": "com.example",
                "items": [{"title": "t", "poster": "p", "thumb": "", "link": "l"}],
            }
        ]
    )
    assert doc["rows"][0]["channelId"] == "x"
    assert doc["generated"]
    json.dumps(doc)


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
