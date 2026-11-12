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
 * country's two Tudum tab pages already carry rank, week, title, artwork and video
 * id (S4). JustWatch popularity supplies the Prime Video rows. Both sources follow
 * the selected country (S13) and neither takes the other down (S15).
 */
final class TopSource {

    /** The country used when the device's is not in the table (S11). */
    static final String DEFAULT_COUNTRY = "ES";

    /**
     * ISO code and the Tudum slug of every country the sources answer for (S11).
     * Each pair returned 200 with a payload this parser reads, and JustWatch takes
     * the ISO code itself.
     */
    private static final String[][] COUNTRIES = {
            {"ES", "spain"},
            {"DE", "germany"},
            {"NL", "netherlands"},
            {"FR", "france"},
            {"IT", "italy"},
            {"GB", "united-kingdom"},
            {"US", "united-states"},
            {"PT", "portugal"},
            {"BE", "belgium"},
            {"AT", "austria"},
            {"CH", "switzerland"},
            {"SE", "sweden"},
            {"PL", "poland"},
            {"IE", "ireland"},
            {"MX", "mexico"},
            {"BR", "brazil"},
            {"AR", "argentina"},
    };

    private static final String TUDUM_BASE = "https://www.netflix.com/tudum/top10/";
    private static final String JUSTWATCH_URL = "https://apis.justwatch.com/graphql";
    private static final String JUSTWATCH_IMAGE = "https://images.justwatch.com";
    static final String NETFLIX_PACKAGE = "com.netflix.ninja";
    static final String PRIME_PACKAGE = "com.amazon.amazonvideo.livingroom";
    // Deep-link hosts in each vendor APK's own intent filters (extra-platform-rows S5).
    static final String APPLE_PACKAGE = "com.apple.atve.androidtv.appletv";
    static final String DISNEY_PACKAGE = "com.disney.disneyplus";
    static final String MAX_PACKAGE = "com.wbd.stream";
    static final String MOVISTAR_PACKAGE = "es.plus.yomvi";
    static final String SKYSHOWTIME_PACKAGE = "com.skyshowtime.skyshowtime.google";
    static final String FILMIN_PACKAGE = "com.filmin.androidtv";
    private static final String APPLE_BASE = "https://tv.apple.com/";
    private static final String[] APPLE_CHARTS = {
            "uts.col.ChartsShows.tvs.sbd.4000", "uts.col.ChartsMovies"};
    private static final int MAX_ITEMS = 10;
    /** A Top 10 page is ~200 KB; this only guards a runaway response. */
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 20000;
    /** netflix.com resets library HTTP clients that look like a script (see fetch_top.py). */
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36"
            + " (KHTML, like Gecko) Chrome/126.0 Safari/537.36";
    // Same query as tools/fetch_top.py: popularity, Prime Video included with the
    // subscription ("prv" in the filter), rentals excluded (amz/amp would leak them).
    private static final String JUSTWATCH_QUERY =
            "query P($country: Country!, $language: Language!, $first: Int!, $objectType: ObjectType!) {"
                    + " popularTitles(country: $country, first: $first, sortBy: POPULAR, sortRandomSeed: 0,"
                    + " filter: {objectTypes: [$objectType], packages: [\"prv\"]}) {"
                    + " edges { node { content(country: $country, language: $language)"
                    + " { title posterUrl backdrops { backdropUrl } }"
                    + " offers(country: $country, platform: WEB)"
                    + " { monetizationType standardWebURL package { shortName } } } } } }";

    /**
     * One JustWatch-backed platform: its label, the app its cards open, the package
     * filter its query asks for, and the packages whose link may be published.
     */
    static final class Platform {
        final String key;
        final String label;
        final String app;
        final String[] queryPackages;
        final String[] linkPackages;

        Platform(String key, String label, String app, String[] queryPackages, String[] linkPackages) {
            this.key = key;
            this.label = label;
            this.app = app;
            this.queryPackages = queryPackages;
            this.linkPackages = linkPackages;
        }
    }

    /** Same short names as tools/fetch_top.py; Prime keeps its ad tier as a link only. */
    static final Platform[] JUSTWATCH_PLATFORMS = {
            new Platform("prime", "Prime Video", PRIME_PACKAGE,
                    new String[] {"prv"}, new String[] {"prv", "pva"}),
            new Platform("disney", "Disney+", DISNEY_PACKAGE,
                    new String[] {"dnp"}, new String[] {"dnp"}),
            new Platform("max", "Max", MAX_PACKAGE,
                    new String[] {"mxx"}, new String[] {"mxx"}),
            new Platform("movistar", "Movistar+", MOVISTAR_PACKAGE,
                    new String[] {"mvs"}, new String[] {"mvs"}),
            new Platform("skyshowtime", "SkyShowtime", SKYSHOWTIME_PACKAGE,
                    new String[] {"sst"}, new String[] {"sst"}),
            new Platform("filmin", "Filmin", FILMIN_PACKAGE,
                    new String[] {"fil"}, new String[] {"fil"}),
    };

    /** One Netflix row: the selector label, the page tab label, the page URL and its identity. */
    static final class Category {
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

    /** The store's own ordinal is the ranking, not the order the shelf arrives in. */
    private static final Comparator<JSONObject> BY_ORDINAL = new Comparator<JSONObject>() {
        @Override
        public int compare(JSONObject left, JSONObject right) {
            return Integer.valueOf(left.optInt("ordinal", 0))
                    .compareTo(right.optInt("ordinal", 0));
        }
    };

    private TopSource() {
    }

    /** The supported ISO codes, in the order the country button lists them (S11). */
    static String[] countries() {
        String[] codes = new String[COUNTRIES.length];
        for (int i = 0; i < COUNTRIES.length; i++) {
            codes[i] = COUNTRIES[i][0];
        }
        return codes;
    }

    /** Whether the table has this country, so every source can answer it. */
    static boolean supported(String country) {
        return country != null && !country.isEmpty() && !slug(country).isEmpty();
    }

    /** The country's Tudum slug, or "" when the table does not have it. */
    static String slug(String country) {
        for (String[] entry : COUNTRIES) {
            if (entry[0].equals(country)) {
                return entry[1];
            }
        }
        return "";
    }

    /** The country's name in the screen's language; never a hardcoded string (S12). */
    static String countryName(String country, Locale locale) {
        String name = new Locale("", country).getDisplayCountry(locale);
        return name.isEmpty() ? country : name;
    }

    /** The locale the country names are drawn in; an empty code follows English. */
    static Locale screenLocale(String language) {
        if (language == null || language.isEmpty()) {
            return Locale.ENGLISH;
        }
        return Locale.forLanguageTag(language);
    }

    /** The report's source line, which names the country the box fetched (S11). */
    static String label(String country) {
        return "on-box sources (Netflix Tudum + JustWatch + Apple chart " + country + ")";
    }

    /** The two Netflix rows of one country, in document order (S12, S13). */
    /** The two Prime rows: object type, row title and channel id. */
    static String[][] primeRows(String country, Locale locale) {
        String cc = country.toLowerCase(Locale.US);
        String name = countryName(country, locale);
        return new String[][] {
                {"SHOW", "Prime Video · Top series " + name, "prime-top-series-" + cc},
                {"MOVIE", "Prime Video · Top películas " + name, "prime-top-films-" + cc},
        };
    }

    /** The two rows of one JustWatch platform: object type, row title and channel id. */
    static String[][] platformRows(Platform platform, String country, Locale locale) {
        String cc = country.toLowerCase(Locale.US);
        String name = countryName(country, locale);
        return new String[][] {
                {"SHOW", platform.label + " · Top series " + name,
                        platform.key + "-top-series-" + cc},
                {"MOVIE", platform.label + " · Top películas " + name,
                        platform.key + "-top-films-" + cc},
        };
    }

    /** The two Apple rows: row title and channel id, in document order. */
    static String[][] appleRows(String country, Locale locale) {
        String cc = country.toLowerCase(Locale.US);
        String name = countryName(country, locale);
        return new String[][] {
                {"Apple TV · Top series " + name, "apple-top-series-" + cc},
                {"Apple TV · Top películas " + name, "apple-top-films-" + cc},
        };
    }

    /**
     * Every row id this app intends to publish for the country, published or not.
     *
     * S14 must not delete the row of a source that failed: the keep set is the
     * published rows plus these, so a partial document only removes other countries.
     */
    static List<String> plannedIds(Installed installed, java.util.Set<String> selected, String country) {
        List<String> ids = new ArrayList<String>();
        try {
            for (Category category : categories(country, Locale.US)) {
                if (wanted(installed, selected, "netflix", NETFLIX_PACKAGE)) {
                    ids.add(category.channelId);
                }
            }
        } catch (IOException error) {
            // Country.saved only ever returns a supported country, so this cannot
            // happen; planning nothing is the safe reading if it ever does.
            return ids;
        }
        for (Platform platform : JUSTWATCH_PLATFORMS) {
            if (!wanted(installed, selected, platform.key, platform.app)) {
                continue;
            }
            for (String[] kind : platformRows(platform, country, Locale.US)) {
                ids.add(kind[2]);
            }
        }
        if (wanted(installed, selected, "apple", APPLE_PACKAGE)) {
            for (String[] kind : appleRows(country, Locale.US)) {
                ids.add(kind[1]);
            }
        }
        return ids;
    }

    /** Whether this TV has an app, which decides if its row may exist at all (S12). */
    interface Installed {
        boolean has(String app);
    }

    /** A row is built only when the platform is ticked on the screen and installed here. */
    private static boolean wanted(Installed installed, java.util.Set<String> selected,
            String key, String app) {
        if (selected != null && !selected.contains(key)) {
            return false;
        }
        return installed.has(app);
    }

    static Category[] categories(String country, Locale locale) throws IOException {
        String slug = slug(country);
        if (slug.isEmpty()) {
            throw new IOException("unsupported country " + country);
        }
        String cc = country.toLowerCase(Locale.US);
        String name = countryName(country, locale);
        String base = TUDUM_BASE + slug;
        return new Category[] {
                new Category("TV", "SERIES", base + "/tv",
                        "netflix-top-series-" + cc, "Netflix · Top series " + name),
                new Category("Films", "MOVIES", base,
                        "netflix-top-films-" + cc, "Netflix · Top películas " + name),
        };
    }

    /**
     * Fetches every source of one country and returns the document (S2, S13).
     *
     * A source that fails or returns nothing only costs its own rows: the reason
     * travels in the notices of the rows that survived (S15). Nothing publishable
     * throws, and the caller then keeps its previous rows (S5).
     */
    static String fetch(Installed installed, java.util.Set<String> selected, String country,
            String language, String target)
            throws IOException, JSONException {
        Locale locale = screenLocale(language);
        String cc = country.toLowerCase(Locale.US);
        String name = countryName(country, locale);

        List<JSONObject> netflix = new ArrayList<JSONObject>();
        List<String> netflixNotices = new ArrayList<String>();
        // S12: this build ships to several TVs, so a platform the device does not have is
        // never fetched and no row is built for it in the first place.
        if (wanted(installed, selected, "netflix", NETFLIX_PACKAGE)) {
            for (Category category : categories(country, locale)) {
                try {
                    netflix.add(netflixRow(category, get(category.url), target));
                } catch (IOException error) {
                    netflixNotices.add(category.title + ": " + error.getMessage());
                } catch (JSONException error) {
                    netflixNotices.add(category.title + ": " + error.getMessage());
                }
            }
        }

        List<JSONObject> published = new ArrayList<JSONObject>(netflix);
        List<List<JSONObject>> justwatchGroups = new ArrayList<List<JSONObject>>();
        List<List<String>> justwatchNotices = new ArrayList<List<String>>();
        for (Platform platform : JUSTWATCH_PLATFORMS) {
            if (!wanted(installed, selected, platform.key, platform.app)) {
                continue;
            }
            List<JSONObject> group = new ArrayList<JSONObject>();
            List<String> notices = new ArrayList<String>();
            for (String[] kind : platformRows(platform, country, locale)) {
                try {
                    JSONObject response = new JSONObject(post(request(kind[0], country, language,
                            platform.queryPackages)));
                    group.add(justwatchRow(kind[2], kind[1], kind[0], response, target, country,
                            platform.app, platform.linkPackages, platform.label));
                } catch (IOException error) {
                    notices.add(kind[1] + ": " + error.getMessage());
                } catch (JSONException error) {
                    notices.add(kind[1] + ": " + error.getMessage());
                }
            }
            justwatchGroups.add(group);
            justwatchNotices.add(notices);
            published.addAll(group);
        }

        List<String> appleNotices = new ArrayList<String>();
        List<JSONObject> apple = wanted(installed, selected, "apple", APPLE_PACKAGE)
                ? appleDocuments(country, locale, target, appleNotices)
                : new ArrayList<JSONObject>();
        published.addAll(apple);

        if (published.isEmpty()) {
            throw new IOException("no source produced rows: " + netflixNotices + " "
                    + justwatchNotices + " " + appleNotices);
        }
        // A source that produced nothing says why on the rows that survived; one that
        // still produced rows carries its own reason there.
        addNotices(netflix.isEmpty() ? published : netflix, netflixNotices);
        for (int i = 0; i < justwatchGroups.size(); i++) {
            List<JSONObject> group = justwatchGroups.get(i);
            addNotices(group.isEmpty() ? published : group, justwatchNotices.get(i));
        }
        addNotices(apple.isEmpty() ? published : apple, appleNotices);

        JSONArray rows = new JSONArray();
        for (JSONObject row : published) {
            rows.put(row);
        }
        return document(rows);
    }

    /** Appends the reasons of a failed source to the rows that survived it (S15). */
    private static void addNotices(List<JSONObject> rows, List<String> notices)
            throws JSONException {
        for (String notice : notices) {
            for (JSONObject row : rows) {
                row.getJSONArray("notices").put(notice);
            }
        }
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
    static JSONObject netflixRow(Category category, String html, String target)
            throws JSONException {
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

    /** The ranked cards of one tab page, ordered by rank and capped at ten. */
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
            JSONObject response, String target, String country) throws JSONException {
        return justwatchRow(channelId, title, objectType, response, target, country,
                PRIME_PACKAGE, new String[] {"prv", "pva"}, "Prime Video");
    }

    static JSONObject justwatchRow(String channelId, String title, String objectType,
            JSONObject response, String target, String country, String app,
            String[] linkPackages, String label) throws JSONException {
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
            item.put("link", platformLink(node.optJSONArray("offers"), linkPackages));
            items.put(item);
        }
        if (items.length() == 0) {
            throw new JSONException("JustWatch returned no " + objectType
                    + " for " + label + " " + country);
        }
        JSONObject row = new JSONObject();
        row.put("channelId", channelId);
        row.put("title", title);
        row.put("app", app);
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

    /**
     * The platform's own deep link from an included offer, or "" when none has one.
     *
     * A title can carry several providers (Max listings also carry Movistar+ URLs),
     * so the offer is chosen by its package and never by its position (S6).
     */
    private static String platformLink(JSONArray offers, String[] packages) {
        for (int i = 0; offers != null && i < offers.length(); i++) {
            JSONObject offer = offers.optJSONObject(i);
            if (offer == null) {
                continue;
            }
            JSONObject included = offer.optJSONObject("package");
            String packageName = included == null ? "" : included.optString("shortName", "");
            String url = offer.optString("standardWebURL", "");
            if (matches(packageName, packages)
                    && "FLATRATE".equals(offer.optString("monetizationType", ""))
                    && !url.isEmpty()) {
                return url;
            }
        }
        return "";
    }

    /** The Prime deep link carried by an included offer, or "" when none has one. */
    private static String primeLink(JSONArray offers) {
        return platformLink(offers, new String[] {"prv", "pva"});
    }

    /** The package list as GraphQL expects it: ["prv"] or ["dnp", "pva"]. */
    private static String quoted(String[] packages) {
        StringBuilder text = new StringBuilder("[");
        for (int i = 0; packages != null && i < packages.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append('"').append(packages[i]).append('"');
        }
        return text.append(']').toString();
    }

    private static boolean matches(String packageName, String[] packages) {
        for (int i = 0; packages != null && i < packages.length; i++) {
            if (packages[i].equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject request(String objectType, String country, String language,
            String[] packages) throws JSONException {
        JSONObject variables = new JSONObject();
        variables.put("country", country);
        variables.put("language", language);
        variables.put("first", MAX_ITEMS);
        variables.put("objectType", objectType);
        JSONObject payload = new JSONObject();
        payload.put("query", JUSTWATCH_QUERY.replace("[\"prv\"]", quoted(packages)));
        payload.put("variables", variables);
        return payload;
    }

    // ---------------------------------------------------------------- apple charts

    /** The two chart URLs of one storefront (extra-platform-rows S6). */
    static String[] appleChartUrls(String country) {
        String base = APPLE_BASE + country.toLowerCase(Locale.US) + "/collection/most-popular/";
        return new String[] {base + APPLE_CHARTS[0], base + APPLE_CHARTS[1]};
    }

    /** mzstatic artwork template -> poster URL, or "" when there is no template. */
    static String applePoster(String template) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        return template.replace("{w}x{h}", "592x888").replace("{f}", "jpg");
    }

    /** Items of one Apple chart, ordered by the store's own ordinal (S6). */
    static JSONArray parseAppleChart(String html, int maxItems) throws JSONException {
        String payload = serializedServerData(html);
        if (payload.isEmpty()) {
            throw new JSONException("no serialized-server-data in the page");
        }
        JSONObject root = new JSONObject(payload);
        List<JSONObject> chart = new ArrayList<JSONObject>();
        collectChart(root, chart);
        Collections.sort(chart, BY_ORDINAL);
        JSONArray items = new JSONArray();
        for (JSONObject entry : chart) {
            if (items.length() >= maxItems) {
                break;
            }
            JSONObject action = entry.optJSONObject("contextAction");
            String title = entry.optString("title", entry.optString("ariaLabel", "")).trim();
            String link = action == null ? "" : action.optString("url", "").trim();
            if (title.isEmpty() || link.isEmpty()) {
                continue;
            }
            JSONObject art = entry.optJSONObject("artwork");
            String poster = applePoster(art == null ? "" : art.optString("template", ""));
            JSONObject item = new JSONObject();
            item.put("title", title);
            item.put("poster", poster);
            item.put("thumb", poster);
            item.put("link", link);
            items.put(item);
        }
        return items;
    }

    /** One Apple row from a chart page, so the same shape is built offline in tests. */
    static JSONObject appleRow(String channelId, String title, String html, String target)
            throws JSONException {
        JSONArray items = parseAppleChart(html, MAX_ITEMS);
        if (items.length() == 0) {
            throw new JSONException("Apple returned no chart");
        }
        JSONObject row = new JSONObject();
        row.put("channelId", channelId);
        row.put("title", title);
        row.put("app", APPLE_PACKAGE);
        row.put("source", "apple-chart");
        row.put("target", target);
        row.put("items", items);
        row.put("notices", new JSONArray());
        return row;
    }

    /** The two Apple rows of one country, each chart degrading on its own (S9). */
    static List<JSONObject> appleDocuments(String country, Locale locale, String target,
            List<String> notices) throws JSONException {
        List<JSONObject> rows = new ArrayList<JSONObject>();
        String[][] identities = appleRows(country, locale);
        String[] urls = appleChartUrls(country);
        for (int i = 0; i < identities.length; i++) {
            try {
                rows.add(appleRow(identities[i][1], identities[i][0], get(urls[i]), target));
            } catch (IOException error) {
                notices.add(identities[i][0] + ": " + error.getMessage());
            } catch (JSONException error) {
                notices.add(identities[i][0] + ": " + error.getMessage());
            }
        }
        return rows;
    }

    /** The JSON script tag Apple server-renders its chart into, or "". */
    private static String serializedServerData(String html) {
        if (html == null) {
            return "";
        }
        String marker = "id=\"serialized-server-data\"";
        int at = html.indexOf(marker);
        if (at < 0) {
            return "";
        }
        int start = html.indexOf('>', at);
        int end = html.indexOf("</script>", start < 0 ? 0 : start);
        if (start < 0 || end < 0) {
            return "";
        }
        return html.substring(start + 1, end).trim();
    }

    private static void collectChart(Object node, List<JSONObject> chart) {
        if (node instanceof JSONObject) {
            JSONObject object = (JSONObject) node;
            if ("OrdinalChartLockup".equals(object.optString("$kind", ""))) {
                chart.add(object);
            }
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                collectChart(object.opt(keys.next()), chart);
            }
        } else if (node instanceof JSONArray) {
            JSONArray array = (JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                collectChart(array.opt(i), chart);
            }
        }
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
