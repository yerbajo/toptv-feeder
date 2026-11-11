package com.alberto.tvfeeder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

        JSONObject netflixFilms = TopSource.netflixRow("Films", films, "preview");
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

        JSONObject netflixTv = TopSource.netflixRow("TV", tv, "preview");
        JSONArray tvItems = netflixTv.getJSONArray("items");
        check("ten series items", tvItems.length() == 10);
        check("series row keeps the page week", "2026-10-18".equals(netflixTv.optString("week", "")));
        check("series title comes from the parent show",
                "Below".equals(tvItems.getJSONObject(0).optString("title", "")));

        boolean aborted = false;
        try {
            TopSource.netflixRow("Films", "<html>the page moved</html>", "preview");
        } catch (JSONException expected) {
            aborted = true;
        }
        check("a page without payload aborts the row", aborted);

        aborted = false;
        try {
            TopSource.netflixRows(tv, "<html>the page moved</html>", "preview");
        } catch (JSONException expected) {
            aborted = true;
        }
        check("one bad page aborts both Netflix rows", aborted);

        JSONObject primeShows = TopSource.justwatchRow("prime-top-series-es",
                "Prime Video · Top series España", "SHOW", show, "watch_next");
        JSONObject primeFilms = TopSource.justwatchRow("prime-top-films-es",
                "Prime Video · Top películas España", "MOVIE", movie, "watch_next");
        JSONObject firstFilm = primeFilms.getJSONArray("items").getJSONObject(0);
        check("prime row names the app",
                "com.amazon.amazonvideo.livingroom".equals(primeFilms.optString("app", "")));
        check("prime item keeps the deep link",
                firstFilm.optString("link", "").startsWith("https://app.primevideo.com/detail?gti="));
        check("prime poster is sized",
                firstFilm.optString("poster", "").endsWith("/s332/proyecto-salvacion.jpg"));
        check("prime thumb falls back to the poster",
                firstFilm.optString("thumb", "").equals(firstFilm.optString("poster", "")));
        check("prime rows carry the requested target", "watch_next".equals(primeShows.optString("target", "")));

        JSONArray rows = TopSource.netflixRows(tv, films, "preview");
        rows.put(TopSource.justwatchRow("prime-top-series-es",
                "Prime Video · Top series España", "SHOW", show, "preview"));
        rows.put(TopSource.justwatchRow("prime-top-films-es",
                "Prime Video · Top películas España", "MOVIE", movie, "preview"));
        check("four rows in document order", rows.length() == 4
                && "netflix-top-series-es".equals(rows.getJSONObject(0).optString("channelId", ""))
                && "netflix-top-films-es".equals(rows.getJSONObject(1).optString("channelId", "")));
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
