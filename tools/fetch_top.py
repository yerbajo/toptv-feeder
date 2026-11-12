#!/usr/bin/env python3
"""Build top.json for the TV feeder app (see odd/tasks/tv-feeder.md).

Sources, both declared in the spec:
  S3 - Netflix: the official weekly Top 10 dataset for Spain.
       https://www.netflix.com/tudum/top10
  S3 - Prime Video: JustWatch popular titles, region ES, Prime Video package
       included with the subscription, by popularity. No credentials.

Usage:
    python3 tools/fetch_top.py --out top.json
    python3 tools/fetch_top.py --out top.json --country DE --language de
    python3 tools/fetch_top.py --out top.json --serve 8080

--country selects the country of the rankings (default ES) and --language the
language of its name in the row titles, exactly like the app's country button and
screen language, so both build the same document for the same pair (S11-S13).
"""
import argparse
import csv
import http.client
import http.server
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

DATASET_URL = "https://www.netflix.com/tudum/top10/data/all-weeks-countries.tsv"
TUDUM_BASE = "https://www.netflix.com/tudum/top10"
DEFAULT_COUNTRY = "ES"
DEFAULT_LANGUAGE = "es"
# ISO code -> Tudum slug (S11). Every pair answers 200 with a payload this parser
# reads; the same table is in app/src/com/alberto/tvfeeder/TopSource.java.
COUNTRY_SLUGS = {
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
# Mirrors Android's new Locale("", code).getDisplayCountry(locale) for the two
# languages the app ships; anything else falls back to the English name.
COUNTRY_NAMES = {
    "ES": {"en": "Spain", "es": "España"},
    "DE": {"en": "Germany", "es": "Alemania"},
    "NL": {"en": "Netherlands", "es": "Países Bajos"},
    "FR": {"en": "France", "es": "Francia"},
    "IT": {"en": "Italy", "es": "Italia"},
    "GB": {"en": "United Kingdom", "es": "Reino Unido"},
    "US": {"en": "United States", "es": "Estados Unidos"},
    "PT": {"en": "Portugal", "es": "Portugal"},
    "BE": {"en": "Belgium", "es": "Bélgica"},
    "AT": {"en": "Austria", "es": "Austria"},
    "CH": {"en": "Switzerland", "es": "Suiza"},
    "SE": {"en": "Sweden", "es": "Suecia"},
    "PL": {"en": "Poland", "es": "Polonia"},
    "IE": {"en": "Ireland", "es": "Irlanda"},
    "MX": {"en": "Mexico", "es": "México"},
    "BR": {"en": "Brazil", "es": "Brasil"},
    "AR": {"en": "Argentina", "es": "Argentina"},
}
JUSTWATCH_URL = "https://apis.justwatch.com/graphql"
JUSTWATCH_POSTER = "https://images.justwatch.com"
# JustWatch package short names for titles *included* with Prime Video in Spain.
# "amz" is the rental store and "amp" the whole Amazon group, both too wide.
JW_PRIME_PACKAGES = ("prv", "pva")
# What the Prime query asks for. The ad-supported tier is accepted as a link but not
# requested, which is exactly what the generator did before the extra platforms.
JW_PRIME_QUERY_PACKAGES = ("prv",)
JW_QUERY = (
    "query P($country: Country!, $language: Language!, $first: Int!, $objectType: ObjectType!) {"
    " popularTitles(country: $country, first: $first, sortBy: POPULAR, sortRandomSeed: 0,"
    ' filter: {objectTypes: [$objectType], packages: ["prv"]}) {'
    " edges { node { content(country: $country, language: $language)"
    " { title posterUrl backdrops { backdropUrl } }"
    " offers(country: $country, platform: WEB)"
    " { monetizationType standardWebURL package { shortName } } } } } }"
)
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0 Safari/537.36"
)

NETFLIX_PACKAGE = "com.netflix.ninja"
PRIME_PACKAGE = "com.amazon.amazonvideo.livingroom"
# Deep-link hosts below come from each vendor APK's own intent filters (S5).
APPLE_PACKAGE = "com.apple.atve.androidtv.appletv"
DISNEY_PACKAGE = "com.disney.disneyplus"
MAX_PACKAGE = "com.wbd.stream"
MOVISTAR_PACKAGE = "es.plus.yomvi"
SKYSHOWTIME_PACKAGE = "com.skyshowtime.skyshowtime.google"
FILMIN_PACKAGE = "com.filmin.androidtv"
APPLE_CHARTS = {
    "TV": "uts.col.ChartsShows.tvs.sbd.4000",
    "Films": "uts.col.ChartsMovies",
}
APPLE_BASE = "https://tv.apple.com"
# The package short names are the ones JustWatch itself publishes for each service.
JUSTWATCH_PLATFORMS = {
    "prime": {"label": "Prime Video", "packages": JW_PRIME_QUERY_PACKAGES,
              "app": PRIME_PACKAGE},
    "disney": {"label": "Disney+", "packages": ("dnp",), "app": DISNEY_PACKAGE},
    "max": {"label": "Max", "packages": ("mxx",), "app": MAX_PACKAGE},
    "movistar": {"label": "Movistar+", "packages": ("mvs",), "app": MOVISTAR_PACKAGE},
    "skyshowtime": {"label": "SkyShowtime", "packages": ("sst",), "app": SKYSHOWTIME_PACKAGE},
    "filmin": {"label": "Filmin", "packages": ("fil",), "app": FILMIN_PACKAGE},
}


def country_name(country, language=DEFAULT_LANGUAGE):
    """The country's name in the screen's language, like Locale.getDisplayCountry."""
    names = COUNTRY_NAMES.get(country) or {}
    return names.get(language) or names.get("en") or country


def page_urls(country=DEFAULT_COUNTRY):
    """The two Tudum pages of one country: films and series (S13)."""
    base = "%s/%s" % (TUDUM_BASE, COUNTRY_SLUGS[country])
    return {"Films": base, "TV": base + "/tv"}


def categories(country=DEFAULT_COUNTRY, language=DEFAULT_LANGUAGE):
    """dataset category -> (channel id, row title, page category) for one country (S12)."""
    cc = country.lower()
    name = country_name(country, language)
    return {
        "TV": ("netflix-top-series-%s" % cc, "Netflix · Top series %s" % name, "SERIES"),
        "Films": ("netflix-top-films-%s" % cc, "Netflix · Top películas %s" % name, "MOVIES"),
    }


def fetch(url, timeout=120, attempts=3, headers=None):
    """GET a page. Prefers curl: netflix.com resets urllib's HTTP/1.1 requests."""
    curl = shutil.which("curl")
    last = None
    for attempt in range(1, attempts + 1):
        try:
            if curl:
                command = [curl, "-sSL", "--max-time", str(timeout), "-A", USER_AGENT]
                for name, value in (headers or {}).items():
                    command += ["-H", "%s: %s" % (name, value)]
                command.append(url)
                done = subprocess.run(command, capture_output=True, check=True)
                text = done.stdout.decode("utf-8", errors="replace")
            else:
                request_headers = {"User-Agent": USER_AGENT}
                request_headers.update(headers or {})
                req = urllib.request.Request(url, headers=request_headers)
                with urllib.request.urlopen(req, timeout=timeout) as response:
                    text = response.read().decode("utf-8", errors="replace")
            if not text:
                raise RuntimeError("empty body")
            return text
        except (OSError, subprocess.CalledProcessError, http.client.HTTPException, RuntimeError) as exc:
            last = exc
            print("retry %d/%d for %s: %s" % (attempt, attempts, url, exc))
            time.sleep(2 * attempt)
    raise RuntimeError("could not fetch %s: %s" % (url, last))


def parse_netflix_dataset(text, country="ES", max_items=10):
    """Latest published week for one country, per category, ordered by rank."""
    rows = list(csv.DictReader(io.StringIO(text), delimiter="\t"))
    mine = [r for r in rows if r.get("country_iso2") == country]
    if not mine:
        return {}
    week = max(r["week"] for r in mine)
    grouped = {}
    for r in mine:
        if r["week"] != week:
            continue
        grouped.setdefault(r["category"], []).append(
            {"rank": int(r["weekly_rank"]), "title": r["show_title"].strip(), "week": week}
        )
    for category in grouped:
        grouped[category].sort(key=lambda item: item["rank"])
        del grouped[category][max_items:]
    return grouped


_JS_ESCAPE = re.compile(r"\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|.)", re.S)
_JS_SIMPLE = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", "f": "\f", "v": "\v", "0": "\0"}


def _js_unescape(body):
    def replace(match):
        seq = match.group(1)
        if len(seq) > 1 and seq[0] in "ux":
            return chr(int(seq[1:], 16))
        return _JS_SIMPLE.get(seq, seq)

    return _JS_ESCAPE.sub(replace, body)


def _graphql_payload(html):
    """Extract the JSON.parse('...') payload the Top 10 page embeds."""
    marker = "graphql = JSON.parse('"
    start = html.find(marker)
    if start < 0:
        raise ValueError("Top 10 page has no embedded payload")
    pos = start + len(marker)
    out = []
    while pos < len(html):
        char = html[pos]
        if char == "\\":
            out.append(html[pos : pos + 2])
            pos += 2
            continue
        if char == "'":
            break
        out.append(char)
        pos += 1
    else:
        raise ValueError("unterminated payload")
    return json.loads(_js_unescape("".join(out)))


def _first_url(image):
    if not isinstance(image, dict):
        return ""
    for key, value in image.items():
        if key.startswith("urlsSized") and isinstance(value, list) and value:
            return value[0].get("url", "")
    return ""


def parse_tudum_art(html):
    """Map (page category, rank) to artwork and a watch deep link."""
    payload = _graphql_payload(html)
    art = {}
    week = ""
    for entity in payload.get("data", {}).values():
        if not isinstance(entity, dict) or entity.get("__typename") != "PulseTop10ItemEntity":
            continue
        top10 = entity.get("top10") or {}
        artwork = entity.get("artwork") or {}
        key = (top10.get("category"), top10.get("weeklyRank"))
        if not key[0] or not isinstance(key[1], int) or key in art:
            continue
        week = week or top10.get("weekEndDate") or ""
        art[key] = {
            "poster": _first_url(artwork.get("storyArt")) or _first_url(artwork.get("sdpArt")),
            "thumb": _first_url(artwork.get("sdpArt")) or _first_url(artwork.get("storyArt")),
            "link": "https://www.netflix.com/watch/%s" % top10.get("videoId", ""),
        }
    if not art:
        raise ValueError("Top 10 page has no items")
    return art, week


def build_netflix_rows(dataset_text, page_htmls, max_items=10, country=DEFAULT_COUNTRY,
                       language=DEFAULT_LANGUAGE):
    """Join the weekly dataset with the artwork of each tab page."""
    rows = []
    dataset = parse_netflix_dataset(dataset_text, country=country, max_items=max_items)
    row_categories = categories(country, language)
    arts = {}
    for category, html in (page_htmls or {}).items():
        try:
            arts[category] = parse_tudum_art(html)
        except (ValueError, json.JSONDecodeError) as exc:
            arts[category] = ({}, "")
            print("note: %s artwork unavailable: %s" % (category, exc))

    for category in ("TV", "Films"):
        items = dataset.get(category) or []
        if not items:
            print("note: dataset has no %s rows for %s" % (category, country))
            continue
        channel_id, title, page_category = row_categories[category]
        week = items[0]["week"]
        art, art_week = arts.get(category, ({}, ""))
        notices = []
        if art_week != week:
            art = {}
            notices.append(
                "artwork skipped: page week %s != dataset week %s" % (art_week or "?", week)
            )
            print("note: %s %s" % (channel_id, notices[-1]))
        rendered = []
        for item in items:
            entry = art.get((page_category, item["rank"]), {})
            rendered.append(
                {
                    "title": item["title"],
                    "poster": entry.get("poster", ""),
                    "thumb": entry.get("thumb", ""),
                    "link": entry.get("link", ""),
                }
            )
        rows.append(
            {
                "channelId": channel_id,
                "title": title,
                "app": NETFLIX_PACKAGE,
                "source": "netflix-tudum",
                "week": week,
                "items": rendered,
                "notices": notices,
            }
        )
    return rows


def parse_justwatch_listing(payload, max_items=10, packages=JW_PRIME_PACKAGES):
    """Items from one JustWatch popularTitles response, already in popularity order."""
    popular = ((payload or {}).get("data") or {}).get("popularTitles") or {}
    items = []
    for edge in popular.get("edges") or []:
        node = (edge or {}).get("node") or {}
        content = node.get("content") or {}
        name = (content.get("title") or "").strip()
        if not name:
            continue
        poster = justwatch_image(content.get("posterUrl") or "", "s592")
        items.append(
            {
                "title": name,
                "poster": poster,
                # The card is 16:9 and the poster is 2:3, so the landscape still is
                # the card image; without one the poster doubles as the thumb.
                "thumb": first_backdrop(content.get("backdrops")) or poster,
                "link": platform_link(node.get("offers") or [], packages),
            }
        )
        if len(items) >= max_items:
            break
    return items


def justwatch_image(path, profile):
    """A JustWatch image URL with its {profile}/{format} template filled in."""
    if not path:
        return ""
    return JUSTWATCH_POSTER + path.replace("{profile}", profile).replace("{format}", "jpg")


def first_backdrop(backdrops):
    """The landscape still for the card, or "" when the listing has none."""
    for entry in backdrops or []:
        url = justwatch_image((entry or {}).get("backdropUrl") or "", "s480")
        if url:
            return url
    return ""


def platform_link(offers, packages=JW_PRIME_PACKAGES):
    """The platform's own deep link from an included offer, or "" if none has one.

    A title can carry several providers (Max shows Movistar+ URLs too), so the offer
    is chosen by its package and never by its position (S6).
    """
    for offer in offers:
        package = (offer.get("package") or {}).get("shortName")
        url = offer.get("standardWebURL") or ""
        if package in packages and offer.get("monetizationType") == "FLATRATE" and url:
            return url
    return ""


def justwatch_request(object_type, first=10, country=DEFAULT_COUNTRY, language=DEFAULT_LANGUAGE,
                      packages=JW_PRIME_QUERY_PACKAGES):
    """POST payload for one JustWatch popularTitles query for one platform and country."""
    return {
        "query": JW_QUERY.replace('["prv"]', json.dumps(list(packages))),
        "variables": {
            "country": country,
            "language": language,
            "first": first,
            "objectType": object_type,
        },
    }


def justwatch_post(url, payload, timeout=60):
    body = json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={"Content-Type": "application/json", "User-Agent": USER_AGENT},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def build_justwatch_rows(platform, max_items=10, fetch_json=None, country=DEFAULT_COUNTRY,
                         language=DEFAULT_LANGUAGE):
    """JustWatch popularity for one platform, which publishes no ranking of its own (S8)."""
    if fetch_json is None:
        fetch_json = lambda payload: justwatch_post(JUSTWATCH_URL, payload)  # noqa: E731
    meta = JUSTWATCH_PLATFORMS[platform]

    cc = country.lower()
    name = country_name(country, language)
    rows = []
    notices = []
    for kind, kind_label, channel_id, title in (
        ("SHOW", "series", "%s-top-series-%s" % (platform, cc),
         "%s · Top series %s" % (meta["label"], name)),
        ("MOVIE", "films", "%s-top-films-%s" % (platform, cc),
         "%s · Top películas %s" % (meta["label"], name)),
    ):
        try:
            items = parse_justwatch_listing(
                fetch_json(justwatch_request(kind, first=max_items, country=country,
                                             language=language, packages=meta["packages"])),
                max_items=max_items,
                packages=meta["packages"],
            )
        except (OSError, ValueError, KeyError, http.client.HTTPException) as exc:
            notices.append("JustWatch %s failed: %s" % (kind_label, exc))
            continue
        if not items:
            notices.append("JustWatch returned no %s for %s %s"
                           % (kind_label, meta["label"], country))
            continue
        rows.append(
            {
                "channelId": channel_id,
                "title": title,
                "app": meta["app"],
                "source": "justwatch-popularity",
                "items": items,
                "notices": [],
            }
        )
    return rows, notices


def build_prime_rows(max_items=10, fetch_json=None, country=DEFAULT_COUNTRY,
                     language=DEFAULT_LANGUAGE):
    """Prime Video publishes no ranking, so JustWatch popularity is the declared proxy."""
    return build_justwatch_rows("prime", max_items, fetch_json, country, language)


def apple_chart_urls(country=DEFAULT_COUNTRY):
    """The two Apple charts of one storefront: series and films (S6).

    The slug in the path is localised; Apple answers 301 towards its own slug and
    the collection id is what identifies the chart, so a stable path keeps working.
    """
    base = "%s/%s/collection" % (APPLE_BASE, country.lower())
    return {"TV": "%s/most-popular/%s" % (base, APPLE_CHARTS["TV"]),
            "Films": "%s/most-popular/%s" % (base, APPLE_CHARTS["Films"])}


def apple_poster(template, profile="592x888"):
    """mzstatic artwork template -> a poster URL, or "" when there is no template."""
    template = (template or "").strip()
    if not template:
        return ""
    return template.replace("{w}x{h}", profile).replace("{f}", "jpg")


def parse_apple_chart(html, max_items=10):
    """Items of an Apple chart page, ordered by its own ordinal (S6).

    The page is server-rendered and carries its data in a JSON script tag, so the
    ranking is the store's own and not an inferred popularity.
    """
    match = re.search(
        r'<script[^>]+id="serialized-server-data"[^>]*>(.*?)</script>', html or "", re.S)
    if not match:
        raise ValueError("no serialized-server-data in the page")
    chart = []

    def walk(node):
        if isinstance(node, dict):
            if node.get("$kind") == "OrdinalChartLockup":
                chart.append(node)
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(json.loads(match.group(1)))
    chart.sort(key=lambda item: item.get("ordinal") or 0)
    items = []
    for entry in chart:
        action = entry.get("contextAction") or {}
        title = (entry.get("title") or entry.get("ariaLabel") or "").strip()
        link = (action.get("url") or "").strip()
        if not title or not link:
            continue
        poster = apple_poster((entry.get("artwork") or {}).get("template"))
        items.append({"title": title, "poster": poster, "thumb": poster, "link": link})
        if len(items) >= max_items:
            break
    return items


def build_apple_rows(page_htmls, max_items=10, country=DEFAULT_COUNTRY,
                     language=DEFAULT_LANGUAGE):
    """Apple TV's own chart rows, one per category, from its storefront pages (S6)."""
    cc = country.lower()
    name = country_name(country, language)
    rows = []
    for category, channel_id, title in (
        ("TV", "apple-top-series-%s" % cc, "Apple TV · Top series %s" % name),
        ("Films", "apple-top-films-%s" % cc, "Apple TV · Top películas %s" % name),
    ):
        html = (page_htmls or {}).get(category)
        if not html:
            continue
        items = parse_apple_chart(html, max_items=max_items)
        if not items:
            continue
        rows.append(
            {
                "channelId": channel_id,
                "title": title,
                "app": APPLE_PACKAGE,
                "source": "apple-chart",
                "items": items,
                "notices": [],
            }
        )
    return rows


def apply_target(rows, target):
    """Sets the provider table every row goes to (preview channels or Watch Next)."""
    for row in rows:
        row["target"] = target
    return rows


def _spill(rows, notices, source_rows):
    """A failed source's reason lands on its own rows, or on every row if it has none (S15)."""
    for row in source_rows or rows:
        row.setdefault("notices", [])
        row["notices"].extend(notices)


def build_rows(dataset_text, page_htmls, country=DEFAULT_COUNTRY, language=DEFAULT_LANGUAGE,
               prime=True, fetch_json=None, max_items=10, target="preview",
               apple_pages=None, extra=True):
    """Rows for one country, each source degrading without taking the others down (S15).

    Returns (rows, notices). The document is only empty when no source produced
    anything, which is what the app refuses to publish (S5).
    """
    rows, notices = [], []

    netflix, netflix_notices = [], []
    try:
        netflix = build_netflix_rows(dataset_text, page_htmls, max_items, country, language)
    except (ValueError, KeyError, TypeError, AttributeError) as exc:
        netflix_notices.append("Tudum %s failed: %s" % (country, exc))
    if not netflix and not netflix_notices:
        netflix_notices.append("Tudum returned no rows for %s" % country)
    rows.extend(netflix)
    notices.extend(netflix_notices)

    if prime:
        prime_rows, prime_notices = build_prime_rows(max_items, fetch_json, country, language)
        rows.extend(prime_rows)
        notices.extend(prime_notices)
        _spill(rows, prime_notices, prime_rows)

    if extra:
        for platform in ("disney", "max", "movistar", "skyshowtime", "filmin"):
            platform_rows, platform_notices = build_justwatch_rows(
                platform, max_items, fetch_json, country, language)
            rows.extend(platform_rows)
            notices.extend(platform_notices)
            _spill(rows, platform_notices, platform_rows)
        apple_notices = []
        try:
            apple_rows = build_apple_rows(apple_pages, max_items, country, language)
        except (ValueError, KeyError, TypeError, json.JSONDecodeError) as exc:
            apple_rows = []
            apple_notices.append("Apple TV chart failed: %s" % exc)
        if not apple_rows and not apple_notices:
            apple_notices.append("Apple TV returned no chart for %s" % country)
        rows.extend(apple_rows)
        notices.extend(apple_notices)
        _spill(rows, apple_notices, apple_rows)

    _spill(rows, netflix_notices, netflix)
    apply_target(rows, target)
    return rows, notices


def document(rows):
    return {
        "schema": 1,
        "generated": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "rows": rows,
    }


def serve(directory, port):
    handler = lambda *a, **kw: http.server.SimpleHTTPRequestHandler(*a, directory=directory, **kw)
    print("serving %s on http://0.0.0.0:%d" % (directory, port))
    http.server.ThreadingHTTPServer(("0.0.0.0", port), handler).serve_forever()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", default="top.json", help="output file (default: top.json)")
    parser.add_argument("--max-items", type=int, default=10, help="items per row")
    parser.add_argument("--no-prime", action="store_true", help="skip Prime Video rows")
    parser.add_argument("--no-extra", action="store_true",
                        help="skip the Apple TV, Disney+ and Max rows")
    parser.add_argument("--serve", type=int, metavar="PORT", help="serve the output directory after writing")
    parser.add_argument(
        "--target",
        choices=("preview", "watch_next"),
        default="preview",
        help="provider table for every row: preview channels (default) or Watch Next",
    )
    parser.add_argument("--offline", metavar="PAGE", help="use a saved Top 10 page and dataset dump instead of the network")
    parser.add_argument(
        "--country",
        choices=sorted(COUNTRY_SLUGS),
        default=DEFAULT_COUNTRY,
        help="country of the rankings, ISO code (default: %s)" % DEFAULT_COUNTRY,
    )
    parser.add_argument(
        "--language",
        default=DEFAULT_LANGUAGE,
        help="language of the country names, like the app's screen (default: %s)" % DEFAULT_LANGUAGE,
    )
    args = parser.parse_args(argv)
    if args.offline:
        base = Path(args.offline)
        slug = COUNTRY_SLUGS[args.country]
        dataset_text = (base / "all-weeks-countries.tsv").read_text(encoding="utf-8")
        page_htmls = {
            "Films": (base / ("tudum-%s.html" % slug)).read_text(encoding="utf-8"),
            "TV": (base / ("tudum-%s-tv.html" % slug)).read_text(encoding="utf-8"),
        }
        apple_pages = {}
        for category, name in (("TV", "apple-%s-tv.html"), ("Films", "apple-%s.html")):
            page = base / (name % slug)
            if page.exists():
                apple_pages[category] = page.read_text(encoding="utf-8")
    else:
        # One source failing must not cost the others (S15), so a page that never
        # answers leaves its row without artwork and says so.
        print("fetching %s" % DATASET_URL)
        try:
            dataset_text = fetch(DATASET_URL)
        except RuntimeError as exc:
            print("note: Netflix dataset unavailable: %s" % exc)
            dataset_text = ""
        apple_pages = {}
        if not args.no_extra:
            for category, url in apple_chart_urls(args.country).items():
                print("fetching %s" % url)
                try:
                    apple_pages[category] = fetch(url)
                except RuntimeError as exc:
                    print("note: Apple %s chart unavailable: %s" % (category, exc))
        page_htmls = {}
        for category, url in page_urls(args.country).items():
            print("fetching %s" % url)
            try:
                page_htmls[category] = fetch(url)
            except RuntimeError as exc:
                print("note: %s page unavailable: %s" % (category, exc))
            time.sleep(1)

    rows, notices = build_rows(
        dataset_text,
        page_htmls,
        country=args.country,
        language=args.language,
        prime=not args.no_prime,
        max_items=args.max_items,
        target=args.target,
        apple_pages=apple_pages,
        extra=not args.no_extra,
    )
    for notice in notices:
        print("note: %s" % notice)
    for row in rows:
        print(
            "row: %-28s %2d items  %s"
            % (row["channelId"], len(row["items"]), row.get("week") or row.get("source"))
        )
        for notice in row.get("notices", []):
            print("      note: %s" % notice)

    output = Path(args.out)
    if not rows:
        # S15 keeps one failing source from costing the others; when nothing at all
        # came back, an empty document would look like a written ranking (L41.1).
        print("error: no rows from any source, %s not written" % output, file=sys.stderr)
        return 1
    output.write_text(json.dumps(document(rows), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print("wrote %s (%d rows)" % (output, len(rows)))
    if args.serve:
        serve(str(output.parent.resolve()), args.serve)
    return 0


if __name__ == "__main__":
    sys.exit(main())
