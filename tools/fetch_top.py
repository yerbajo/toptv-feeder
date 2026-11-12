#!/usr/bin/env python3
"""Build top.json for the TV feeder app (see odd/tasks/tv-feeder.md).

Sources, both declared in the spec:
  S3 - Netflix: the official weekly Top 10 dataset for Spain.
       https://www.netflix.com/tudum/top10
  S3 - Prime Video: JustWatch popular titles, region ES, Prime Video package
       included with the subscription, by popularity. No credentials.

Usage:
    python3 tools/fetch_top.py --out top.json
    python3 tools/fetch_top.py --out top.json --serve 8080
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
# One embedded list per tab: /top10/spain is the Films list, /top10/spain/tv the series list.
PAGE_URLS = {
    "Films": "https://www.netflix.com/tudum/top10/spain",
    "TV": "https://www.netflix.com/tudum/top10/spain/tv",
}
JUSTWATCH_URL = "https://apis.justwatch.com/graphql"
JUSTWATCH_POSTER = "https://images.justwatch.com"
# JustWatch package short names for titles *included* with Prime Video in Spain.
# "amz" is the rental store and "amp" the whole Amazon group, both too wide.
JW_PRIME_PACKAGES = ("prv", "pva")
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

# dataset category -> (channel id, row title, page category)
CATEGORIES = {
    "TV": ("netflix-top-series-es", "Netflix · Top series España", "SERIES"),
    "Films": ("netflix-top-films-es", "Netflix · Top películas España", "MOVIES"),
}
NETFLIX_PACKAGE = "com.netflix.ninja"
PRIME_PACKAGE = "com.amazon.amazonvideo.livingroom"


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


def build_netflix_rows(dataset_text, page_htmls, max_items=10):
    """Join the weekly dataset with the artwork of each tab page."""
    rows = []
    dataset = parse_netflix_dataset(dataset_text, max_items=max_items)
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
            print("note: dataset has no %s rows" % category)
            continue
        channel_id, title, page_category = CATEGORIES[category]
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


def parse_justwatch_listing(payload, max_items=10):
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
                "link": prime_link(node.get("offers") or []),
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


def prime_link(offers):
    """The Prime deep link carried by an included offer, or "" if none has one."""
    for offer in offers:
        package = (offer.get("package") or {}).get("shortName")
        url = offer.get("standardWebURL") or ""
        if package in JW_PRIME_PACKAGES and offer.get("monetizationType") == "FLATRATE" and url:
            return url
    return ""


def justwatch_request(object_type, first=10):
    """POST payload for one JustWatch popularTitles query, Prime-only, country ES."""
    return {
        "query": JW_QUERY,
        "variables": {"country": "ES", "language": "es", "first": first, "objectType": object_type},
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


def build_prime_rows(max_items=10, fetch_json=None):
    """Prime Video publishes no ranking, so JustWatch popularity is the declared proxy."""
    if fetch_json is None:
        fetch_json = lambda payload: justwatch_post(JUSTWATCH_URL, payload)  # noqa: E731

    rows = []
    notices = []
    for kind, kind_label, channel_id, title in (
        ("SHOW", "series", "prime-top-series-es", "Prime Video · Top series España"),
        ("MOVIE", "films", "prime-top-films-es", "Prime Video · Top películas España"),
    ):
        try:
            items = parse_justwatch_listing(
                fetch_json(justwatch_request(kind, first=max_items)), max_items=max_items
            )
        except (OSError, ValueError, KeyError, http.client.HTTPException) as exc:
            notices.append("JustWatch %s failed: %s" % (kind_label, exc))
            continue
        if not items:
            notices.append("JustWatch returned no %s for Prime Video ES" % kind_label)
            continue
        rows.append(
            {
                "channelId": channel_id,
                "title": title,
                "app": PRIME_PACKAGE,
                "source": "justwatch-popularity",
                "items": items,
                "notices": [],
            }
        )
    return rows, notices


def apply_target(rows, target):
    """Sets the provider table every row goes to (preview channels or Watch Next)."""
    for row in rows:
        row["target"] = target
    return rows


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
    parser.add_argument("--serve", type=int, metavar="PORT", help="serve the output directory after writing")
    parser.add_argument(
        "--target",
        choices=("preview", "watch_next"),
        default="preview",
        help="provider table for every row: preview channels (default) or Watch Next",
    )
    parser.add_argument("--offline", metavar="PAGE", help="use a saved Top 10 page and dataset dump instead of the network")
    args = parser.parse_args(argv)

    if args.offline:
        base = Path(args.offline)
        dataset_text = (base / "all-weeks-countries.tsv").read_text(encoding="utf-8")
        page_htmls = {
            "Films": (base / "tudum-spain.html").read_text(encoding="utf-8"),
            "TV": (base / "tudum-spain-tv.html").read_text(encoding="utf-8"),
        }
    else:
        print("fetching %s" % DATASET_URL)
        dataset_text = fetch(DATASET_URL)
        page_htmls = {}
        for category, url in PAGE_URLS.items():
            print("fetching %s" % url)
            page_htmls[category] = fetch(url)
            time.sleep(1)

    rows = build_netflix_rows(dataset_text, page_htmls, max_items=args.max_items)
    notices = []
    if not args.no_prime:
        prime_rows, prime_notices = build_prime_rows(max_items=args.max_items)
        rows.extend(prime_rows)
        notices.extend(prime_notices)
    apply_target(rows, args.target)
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
    output.write_text(json.dumps(document(rows), ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print("wrote %s (%d rows)" % (output, len(rows)))
    if args.serve:
        serve(str(output.parent.resolve()), args.serve)
    return 0


if __name__ == "__main__":
    sys.exit(main())
