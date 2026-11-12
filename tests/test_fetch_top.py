#!/usr/bin/env python3
"""Checks for tools/fetch_top.py. Plain asserts, no test framework needed.

    python3 tests/test_fetch_top.py
"""
import json
import pathlib
import sys

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
