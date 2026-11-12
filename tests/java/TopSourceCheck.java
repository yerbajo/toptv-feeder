package com.alberto.tvfeeder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Fixture-driven check for TopSource, the on-box document builder (S1, S2, S4).
 *
 * Host only: tests/test_autonomous_feeder.py compiles this file with TopSource.java
 * against a pinned JVM org.json jar, runs it on tests/fixtures, and compares the
 * document it writes with the reference output of tools/fetch_top.py on the same
 * fixtures. It is not part of the APK.
 */
public final class TopSourceCheck {

    private static int checks;
    private static int failed;

    private TopSourceCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path fixtures = Paths.get(args[0]);
        Path documentFile = Paths.get(args[1]);
        String films = read(fixtures.resolve("tudum-spain.html"));
        String tv = read(fixtures.resolve("tudum-spain-tv.html"));
        JSONObject movie = new JSONObject(read(fixtures.resolve("justwatch-movie-es.json")));
        JSONObject show = new JSONObject(read(fixtures.resolve("justwatch-show-es.json")));

        TopSource.Category[] spain = TopSource.categories("ES", new Locale("es"));
        TopSource.Category[] germany = TopSource.categories("DE", new Locale("en"));
        check("the country table decides the ids, titles and page urls",
                "netflix-top-series-de".equals(germany[0].channelId)
                        && "Netflix · Top series Germany".equals(germany[0].title)
                        && ("https://www.netflix.com/tudum/top10/germany/tv").equals(germany[0].url)
                        && "netflix-top-films-de".equals(germany[1].channelId)
                        && "Netflix · Top películas Germany".equals(germany[1].title)
                        && ("https://www.netflix.com/tudum/top10/germany").equals(germany[1].url));
        TopSource.Category[] dutch = TopSource.categories("NL", new Locale("es"));
        check("names come from the locale, not a hardcoded string",
                ("Netflix · Top series Países Bajos").equals(dutch[0].title)
                        && "netflix-top-series-nl".equals(dutch[0].channelId));
        check("an unsupported country has no category", !TopSource.supported("ZZ"));

        JSONObject netflixFilms = TopSource.netflixRow(spain[1], films, "preview");
        check("films row identity",
                "netflix-top-films-es".equals(netflixFilms.optString("channelId", ""))
                        && "Netflix · Top películas España".equals(netflixFilms.optString("title", "")));
        check("films row keeps the page week", "2026-10-18".equals(netflixFilms.optString("week", "")));
        check("films row carries the target", "preview".equals(netflixFilms.optString("target", "")));
        JSONArray filmItems = netflixFilms.getJSONArray("items");
        check("ten film items", filmItems.length() == 10);
        for (int i = 0; i < filmItems.length(); i++) {
            JSONObject item = filmItems.getJSONObject(i);
            check("film item " + i + " has poster art", item.optString("poster", "").startsWith("https://"));
            check("film item " + i + " links a Netflix title",
                    item.optString("link", "").startsWith("https://www.netflix.com/watch/"));
        }

        JSONObject netflixTv = TopSource.netflixRow(spain[0], tv, "preview");
        JSONArray tvItems = netflixTv.getJSONArray("items");
        check("ten series items", tvItems.length() == 10);
        check("series row keeps the page week", "2026-10-18".equals(netflixTv.optString("week", "")));
        check("series title comes from the parent show",
                "Below".equals(tvItems.getJSONObject(0).optString("title", "")));

        boolean aborted = false;
        try {
            TopSource.netflixRow(spain[1], "<html>the page moved</html>", "preview");
        } catch (JSONException expected) {
            aborted = true;
        }
        check("a page without payload aborts the row", aborted);

        aborted = false;
        try {
            TopSource.netflixRow(spain[0], "<html>the page moved</html>", "preview");
        } catch (JSONException expected) {
            aborted = true;
        }
        check("the failure stays on its own row", aborted);

        JSONObject primeShows = TopSource.justwatchRow("prime-top-series-es",
                "Prime Video · Top series España", "SHOW", show, "watch_next", "ES");
        JSONObject primeFilms = TopSource.justwatchRow("prime-top-films-es",
                "Prime Video · Top películas España", "MOVIE", movie, "watch_next", "ES");
        JSONObject firstFilm = primeFilms.getJSONArray("items").getJSONObject(0);
        check("prime row names the app",
                "com.amazon.amazonvideo.livingroom".equals(primeFilms.optString("app", "")));
        check("prime item keeps the deep link",
                firstFilm.optString("link", "").startsWith("https://app.primevideo.com/detail?gti="));
        check("prime poster is sized",
                firstFilm.optString("poster", "").endsWith("/s592/proyecto-salvacion.jpg"));
        // S10: the card is 16:9, so the thumb is JustWatch's landscape still rather
        // than the 2:3 poster the old check expected.
        check("prime thumb is the landscape still",
                firstFilm.optString("thumb", "").endsWith("/s480/proyecto-salvacion.jpg")
                        && !firstFilm.optString("thumb", "").equals(firstFilm.optString("poster", "")));
        check("prime rows carry the requested target", "watch_next".equals(primeShows.optString("target", "")));

        check("each justwatch platform asks for its own package",
                "[\"dnp\"]".equals(quotedPackages("disney"))
                        && "[\"mxx\"]".equals(quotedPackages("max"))
                        && "[\"prv\"]".equals(quotedPackages("prime")));

        JSONArray rows = new JSONArray();
        rows.put(TopSource.netflixRow(spain[0], tv, "preview"));
        rows.put(TopSource.netflixRow(spain[1], films, "preview"));
        for (TopSource.Platform platform : TopSource.JUSTWATCH_PLATFORMS) {
            TopSource.Platform selected = platform;
            for (String[] kind : TopSource.platformRows(selected, "ES", new Locale("es"))) {
                rows.put(TopSource.justwatchRow(kind[2], kind[1], kind[0],
                        "SHOW".equals(kind[0]) ? show : movie, "preview", "ES",
                        selected.app, selected.linkPackages, selected.label));
            }
        }
        String[][] appleIdentities = TopSource.appleRows("ES", new Locale("es"));
        rows.put(TopSource.appleRow(appleIdentities[0][1], appleIdentities[0][0],
                read(fixtures.resolve("apple-es-tv.html")), "preview"));
        rows.put(TopSource.appleRow(appleIdentities[1][1], appleIdentities[1][0],
                read(fixtures.resolve("apple-es.html")), "preview"));
        int expected = 2 + 2 * TopSource.JUSTWATCH_PLATFORMS.length + 2;
        check("every row in document order", rows.length() == expected
                && "netflix-top-series-es".equals(rows.getJSONObject(0).optString("channelId", ""))
                && "netflix-top-films-es".equals(rows.getJSONObject(1).optString("channelId", ""))
                && ("apple-top-series-es").equals(
                        rows.getJSONObject(expected - 2).optString("channelId", ""))
                && ("apple-top-films-es").equals(
                        rows.getJSONObject(expected - 1).optString("channelId", "")));
        for (int i = 0; i < rows.length(); i++) {
            check("row " + i + " carries a target",
                    "preview".equals(rows.getJSONObject(i).optString("target", "")));
        }
        Files.write(documentFile, TopSource.document(rows).getBytes(StandardCharsets.UTF_8));

        check("a cold start republishes the cache", "cached".equals(TopSource.recover("cached", false)));
        check("a warm start never republishes the cache", TopSource.recover("cached", true) == null);
        check("no cache, nothing to recover", TopSource.recover(null, false) == null);

        System.out.println(failed == 0
                ? checks + " checks passed"
                : failed + " of " + checks + " checks failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** The package filter the app puts in the query, as the generator serialises it. */
    private static String quotedPackages(String platform) {
        for (TopSource.Platform candidate : TopSource.JUSTWATCH_PLATFORMS) {
            if (candidate.key.equals(platform)) {
                StringBuilder text = new StringBuilder("[");
                for (int i = 0; i < candidate.queryPackages.length; i++) {
                    if (i > 0) {
                        text.append(", ");
                    }
                    text.append('"').append(candidate.queryPackages[i]).append('"');
                }
                return text.append(']').toString();
            }
        }
        return "";
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            failed++;
        }
        System.out.println((ok ? "ok   " : "FAIL ") + what);
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
