package com.alberto.tvfeeder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the top.json document on the box itself, without the PC (S1, S2).
 *
 * Same evidence as tools/fetch_top.py, the reference implementation, minus the
 * 33 MB world-wide TSV it also uses: that body cannot be held on a TV box, and the
 * two Spain tab pages already carry rank, week, title, artwork and video id (S4).
 * JustWatch popularity supplies the Prime Video rows. All or nothing: a source
 * that fails throws, so the caller keeps its last valid rows instead of half a
 * document.
 */
final class TopSource {

    static final String LABEL = "on-box sources (Netflix Tudum + JustWatch ES)";

    private static final String JUSTWATCH_URL = "https://apis.justwatch.com/graphql";
    private static final String JUSTWATCH_IMAGE = "https://images.justwatch.com";
    private static final String COUNTRY = "ES";
    private static final String NETFLIX_PACKAGE = "com.netflix.ninja";
    private static final String PRIME_PACKAGE = "com.amazon.amazonvideo.livingroom";
    private static final int MAX_ITEMS = 10;
    /** The Spain pages are ~200 KB; this only guards a runaway response. */
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 20000;
    /** netflix.com resets library HTTP clients that look like a script (see fetch_top.py). */
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    // Same query as tools/fetch_top.py: ES popularity, Prime Video included with the
    // subscription ("prv" in the filter), rentals excluded (amz/amp would leak them).
    private static final String JUSTWATCH_QUERY =
            "query P($country: Country!, $language: Language!, $first: Int!, $objectType: ObjectType!) {"
                    + " popularTitles(country: $country, first: $first, sortBy: POPULAR, sortRandomSeed: 0,"
                    + " filter: {objectTypes: [$objectType], packages: [\"prv\"]}) {"
                    + " edges { node { content(country: $country, language: $language)"
                    + " { title posterUrl backdrops { backdropUrl } }"
                    + " offers(country: $country, platform: WEB)"
                    + " { monetizationType standardWebURL package { shortName } } } } } }";

    /** One Netflix row: the selector label, the page tab label, the page URL and its identity. */
    private static final class Category {
        final String label;
        final String page;
        final String url;
        final String channelId;
        final String title;

        Category(String label, String page, String url, String channelId, String title) {
            this.label = label;
            this.page = page;
            this.url = url;
            this.channelId = channelId;
            this.title = title;
        }
    }

    private static final Category[] NETFLIX = {
            new Category("TV", "SERIES", "https://www.netflix.com/tudum/top10/spain/tv",
                    "netflix-top-series-es", "Netflix · Top series España"),
            new Category("Films", "MOVIES", "https://www.netflix.com/tudum/top10/spain",
                    "netflix-top-films-es", "Netflix · Top películas España"),
    };

    /** One ranked card of a page, every field taken from the same entity (S4). */
    private static final class Card {
        int rank;
        String title = "";
        String poster = "";
        String thumb = "";
        String link = "";
    }

    private static final class Page {
        String week = "";
        final List<Card> cards = new ArrayList<Card>();
    }

    private static final Comparator<Card> BY_RANK = new Comparator<Card>() {
        @Override
        public int compare(Card left, Card right) {
            return Integer.valueOf(left.rank).compareTo(right.rank);
        }
    };

    private TopSource() {
    }

    /** Fetches every source and returns the finished document, or throws (S2). */
    static String fetch(String target) throws IOException, JSONException {
        String tv = get(NETFLIX[0].url);
        String films = get(NETFLIX[1].url);
        JSONArray rows = netflixRows(tv, films, target);
        JSONObject show = new JSONObject(post(request("SHOW")));
        rows.put(justwatchRow("prime-top-series-es", "Prime Video · Top series España",
                "SHOW", show, target));
        JSONObject movie = new JSONObject(post(request("MOVIE")));
        rows.put(justwatchRow("prime-top-films-es", "Prime Video · Top películas España",
                "MOVIE", movie, target));
        return document(rows);
    }

    /**
     * The two Netflix rows in document order, from the two page bodies. A page that
     * does not parse throws here, so no partial document ever reaches the cache (S4).
     */
    static JSONArray netflixRows(String tvHtml, String filmsHtml, String target)
            throws JSONException {
        JSONArray rows = new JSONArray();
        rows.put(netflixRow(NETFLIX[0].label, tvHtml, target));
        rows.put(netflixRow(NETFLIX[1].label, filmsHtml, target));
        return rows;
    }

    static String document(JSONArray rows) throws JSONException {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        JSONObject document = new JSONObject();
        document.put("schema", 1);
        document.put("generated", format.format(new Date()));
        document.put("rows", rows);
        return document.toString(1);
    }

    /**
     * The document to publish after a failed refresh: the cached copy only on a cold
     * start, never over rows the provider already has (S5).
     */
    static String recover(String cached, boolean published) {
        return published ? null : cached;
    }

    /**
     * One Netflix row from its tab page alone (S4): rank, week, title, artwork and
     * watch id come from one entity, so a lagging page cannot mismatch them.
     */
    static JSONObject netflixRow(String label, String html, String target) throws JSONException {
        Category category = category(label);
        Page page = page(html, category.page);
        if (page.cards.isEmpty()) {
            throw new JSONException("Top 10 page " + category.page + " has no ranked items");
        }
        if (page.week.isEmpty()) {
            throw new JSONException("Top 10 page " + category.page + " has no week");
        }
        JSONArray items = new JSONArray();
        for (Card card : page.cards) {
            JSONObject item = new JSONObject();
            item.put("title", card.title);
            item.put("poster", card.poster);
            item.put("thumb", card.thumb);
            item.put("link", card.link);
            items.put(item);
        }
        JSONObject row = new JSONObject();
        row.put("channelId", category.channelId);
        row.put("title", category.title);
        row.put("app", NETFLIX_PACKAGE);
        row.put("source", "netflix-tudum");
        row.put("week", page.week);
        row.put("target", target);
        row.put("items", items);
        row.put("notices", new JSONArray());
        return row;
    }

    private static Category category(String label) throws JSONException {
        for (Category category : NETFLIX) {
            if (category.label.equals(label)) {
                return category;
            }
        }
        throw new JSONException("unknown Netflix row " + label);
    }

    /** The ranked cards of one Spain tab page, ordered by rank and capped at ten. */
    private static Page page(String html, String pageCategory) throws JSONException {
        Page page = new Page();
        Map<Integer, Card> byRank = new LinkedHashMap<Integer, Card>();
        JSONObject data = payload(html);
        Iterator<String> keys = data.keys();
        while (keys.hasNext()) {
            JSONObject entity = data.optJSONObject(keys.next());
            if (entity == null
                    || !"PulseTop10ItemEntity".equals(entity.optString("__typename", ""))) {
                continue;
            }
            JSONObject top10 = entity.optJSONObject("top10");
            if (top10 == null || !pageCategory.equals(top10.optString("category", ""))) {
                continue;
            }
            int rank = top10.optInt("weeklyRank", 0);
            if (rank <= 0 || byRank.containsKey(rank)) {
                continue;
            }
            String title = title(entity.optJSONObject("top10Video"));
            if (title.isEmpty()) {
                continue; // never publish a placeholder title (S5)
            }
            JSONObject images = entity.optJSONObject("artwork");
            Card card = new Card();
            card.rank = rank;
            card.title = title;
            card.poster = firstUrl(images, "storyArt");
            if (card.poster.isEmpty()) {
                card.poster = firstUrl(images, "sdpArt");
            }
            card.thumb = firstUrl(images, "sdpArt");
            if (card.thumb.isEmpty()) {
                card.thumb = firstUrl(images, "storyArt");
            }
            card.link = "https://www.netflix.com/watch/" + top10.optInt("videoId", 0);
            if (page.week.isEmpty()) {
                page.week = top10.optString("weekEndDate", "");
            }
            byRank.put(rank, card);
        }
        page.cards.addAll(byRank.values());
        Collections.sort(page.cards, BY_RANK);
        while (page.cards.size() > MAX_ITEMS) {
            page.cards.remove(page.cards.size() - 1);
        }
        return page;
    }

    /**
     * The card's title: a series' parent show ("Below", not "Below: Limited Series"),
     * a film's own title when it has no parent.
     */
    private static String title(JSONObject video) {
        if (video == null) {
            return "";
        }
        JSONObject parent = video.optJSONObject("parentShow");
        String title = parent == null ? "" : parent.optString("title", "").trim();
        return title.isEmpty() ? video.optString("title", "").trim() : title;
    }

    /** The graphql payload the Top 10 page embeds; a page without it aborts the sync. */
    private static JSONObject payload(String html) throws JSONException {
        String marker = "graphql = JSON.parse('";
        int start = html.indexOf(marker);
        if (start < 0) {
            throw new JSONException("Top 10 page has no embedded payload");
        }
        StringBuilder quoted = new StringBuilder();
        int pos = start + marker.length();
        while (pos < html.length()) {
            char c = html.charAt(pos);
            if (c == '\\') {
                quoted.append(html, pos, Math.min(pos + 2, html.length()));
                pos += 2;
                continue;
            }
            if (c == '\'') {
                break;
            }
            quoted.append(c);
            pos++;
        }
        if (pos >= html.length()) {
            throw new JSONException("Top 10 page payload is unterminated");
        }
        JSONObject data = new JSONObject(unescape(quoted.toString())).optJSONObject("data");
        if (data == null) {
            throw new JSONException("Top 10 payload has no data");
        }
        return data;
    }

    private static String firstUrl(JSONObject images, String name) {
        JSONObject sized = images == null ? null : images.optJSONObject(name);
        if (sized == null) {
            return "";
        }
        Iterator<String> keys = sized.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!key.startsWith("urlsSized")) {
                continue;
            }
            JSONArray urls = sized.optJSONArray(key);
            if (urls == null || urls.length() == 0) {
                continue;
            }
            JSONObject first = urls.optJSONObject(0);
            return first == null ? "" : first.optString("url", "");
        }
        return "";
    }

    /**
     * Undoes the JavaScript string escapes the page wraps its JSON in. {@code \\}
     * pairs are copied raw out of the page and only decoded here, exactly like
     * fetch_top.py's _js_unescape.
     */
    private static String unescape(String body) {
        StringBuilder out = new StringBuilder(body.length());
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i++);
            if (c != '\\' || i >= body.length()) {
                out.append(c);
                continue;
            }
            char escape = body.charAt(i++);
            int digits = escape == 'u' ? 4 : escape == 'x' ? 2 : 0;
            if (digits > 0 && i + digits <= body.length()) {
                out.append((char) Integer.parseInt(body.substring(i, i + digits), 16));
                i += digits;
                continue;
            }
            switch (escape) {
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'v': out.append('\u000b'); break;
                case '0': out.append('\0'); break;
                default: out.append(escape); break;
            }
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- justwatch

    static JSONObject justwatchRow(String channelId, String title, String objectType,
            JSONObject response, String target) throws JSONException {
        JSONArray items = new JSONArray();
        JSONObject popular = response.optJSONObject("data");
        popular = popular == null ? null : popular.optJSONObject("popularTitles");
        JSONArray edges = popular == null ? null : popular.optJSONArray("edges");
        for (int i = 0; edges != null && i < edges.length() && items.length() < MAX_ITEMS; i++) {
            JSONObject node = edges.optJSONObject(i);
            node = node == null ? null : node.optJSONObject("node");
            JSONObject content = node == null ? null : node.optJSONObject("content");
            if (content == null) {
                continue;
            }
            String name = content.optString("title", "").trim();
            if (name.isEmpty()) {
                continue;
            }
            String poster = image(content.optString("posterUrl", ""), "s592");
            JSONObject item = new JSONObject();
            item.put("title", name);
            item.put("poster", poster);
            // The card is 16:9 and the poster is 2:3, so the landscape still is the
            // card image; without one the poster doubles as the thumb.
            String thumb = firstBackdrop(content.optJSONArray("backdrops"));
            item.put("thumb", thumb.isEmpty() ? poster : thumb);
            item.put("link", primeLink(node.optJSONArray("offers")));
            items.put(item);
        }
        if (items.length() == 0) {
            throw new JSONException("JustWatch returned no " + objectType + " for Prime Video " + COUNTRY);
        }
        JSONObject row = new JSONObject();
        row.put("channelId", channelId);
        row.put("title", title);
        row.put("app", PRIME_PACKAGE);
        row.put("source", "justwatch-popularity");
        row.put("target", target);
        row.put("items", items);
        row.put("notices", new JSONArray());
        return row;
    }

    /** A JustWatch image URL with its {profile}/{format} template filled in. */
    private static String image(String path, String profile) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        return JUSTWATCH_IMAGE + path.replace("{profile}", profile).replace("{format}", "jpg");
    }

    /** The landscape still for the card, or "" when the listing has none. */
    private static String firstBackdrop(JSONArray backdrops) {
        for (int i = 0; backdrops != null && i < backdrops.length(); i++) {
            JSONObject entry = backdrops.optJSONObject(i);
            String url = entry == null ? "" : image(entry.optString("backdropUrl", ""), "s480");
            if (!url.isEmpty()) {
                return url;
            }
        }
        return "";
    }

    /** The Prime deep link carried by an included offer, or "" when none has one. */
    private static String primeLink(JSONArray offers) {
        for (int i = 0; offers != null && i < offers.length(); i++) {
            JSONObject offer = offers.optJSONObject(i);
            if (offer == null) {
                continue;
            }
            JSONObject included = offer.optJSONObject("package");
            String packageName = included == null ? "" : included.optString("shortName", "");
            String url = offer.optString("standardWebURL", "");
            boolean subscription = "prv".equals(packageName) || "pva".equals(packageName);
            if (subscription && "FLATRATE".equals(offer.optString("monetizationType", ""))
                    && !url.isEmpty()) {
                return url;
            }
        }
        return "";
    }

    private static JSONObject request(String objectType) throws JSONException {
        JSONObject variables = new JSONObject();
        variables.put("country", COUNTRY);
        variables.put("language", "es");
        variables.put("first", MAX_ITEMS);
        variables.put("objectType", objectType);
        JSONObject payload = new JSONObject();
        payload.put("query", JUSTWATCH_QUERY);
        payload.put("variables", variables);
        return payload;
    }

    // ---------------------------------------------------------------- transport

    private static String get(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            return body(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static String post(JSONObject payload) throws IOException {
        byte[] bytes = payload.toString().getBytes("UTF-8");
        HttpURLConnection connection = (HttpURLConnection) new URL(JUSTWATCH_URL).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = connection.getOutputStream();
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            return body(connection);
        } finally {
            connection.disconnect();
        }
    }

    private static String body(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        if (code < 200 || code > 299) {
            throw new IOException("HTTP " + code + " from " + connection.getURL());
        }
        InputStream in = connection.getInputStream();
        try {
            return readBody(in);
        } finally {
            in.close();
        }
    }

    /** Reads a whole response body, refusing anything over the size guard. */
    static String readBody(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > MAX_BODY_BYTES) {
                throw new IOException("body larger than " + MAX_BODY_BYTES + " bytes");
            }
        }
        return out.toString("UTF-8");
    }
}
