package com.alberto.tvfeeder;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Publishes the ranked rows of top.json into the TV provider.
 *
 * The document is built on the box by default (S1, S2); an external URL stays
 * available as the legacy mode. Every write is restricted to rows this package
 * owns (S4). A failed download or parse leaves the published rows untouched (S5).
 * Rows with missing artwork are still published, degraded, with the reason in the
 * report (S6). Every card is resolved on the device before it is published (S3a).
 */
public final class Feeder {

    private static final String TAG = "TvFeeder";

    static final String PREFS = "tvfeeder";
    static final String PREF_URL = "url";
    static final String PREF_TARGET = "target";
    static final String PREF_STATUS = "status";
    static final String PREF_REPORT = "report";
    static final String PREF_RUN = "run";
    static final String TARGET_PREVIEW = "preview";
    static final String TARGET_WATCH_NEXT = "watch_next";

    private static final String PREF_PUBLISHED = "published";
    private static final String PREF_AUTONOMOUS = "autonomous";
    private static final String CACHE_PREFIX = "last_good-";
    private static final String CARD_TITLE = "title";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int SCHEMA = 1;

    // Column names are literals: the SDK does not expose every one of them
    // (package_name is internal) and the provider uses these exact strings.
    private static final String COL_PACKAGE_NAME = "package_name";
    private static final String COL_TYPE = "type";
    private static final String COL_DISPLAY_NAME = "display_name";
    private static final String COL_SEARCHABLE = "searchable";
    private static final String COL_APP_LINK_INTENT_URI = "app_link_intent_uri";
    private static final String COL_DESCRIPTION = "description";
    private static final String COL_INPUT_ID = "input_id";

    /** Result of one sync: false means nothing was written. */
    static final class Report {
        final boolean ok;
        final String text;

        Report(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }

        String summary() {
            return text.split("\n", 2)[0];
        }
    }

    private static final class Item {
        String title = "";
        String poster = "";
        String thumb = "";
        String link = "";
        String intent = "";
    }

    private static final class Row {
        String channelId = "";
        String title = "";
        String app = "";
        String target = TARGET_PREVIEW;
        String week = "";
        final List<Item> items = new ArrayList<Item>();
        final List<String> notices = new ArrayList<String>();
        int degraded;
        int unresolved;

        /**
         * Keeps only the cards that open something on this device (S3a) and counts
         * the rest, so a dead link is reported instead of published.
         */
        void resolve(Context context) {
            List<Item> openable = new ArrayList<Item>();
            for (Item item : items) {
                String uri = cardIntent(context, this, item);
                if (uri == null) {
                    unresolved++;
                    continue;
                }
                item.intent = uri;
                openable.add(item);
            }
            items.clear();
            items.addAll(openable);
        }

        boolean watchNext() {
            return TARGET_WATCH_NEXT.equals(target);
        }

        String key() {
            return channelKey(channelId);
        }

        /** Null when the row can be published, otherwise why it cannot (S5). */
        String problem() {
            if (channelId.isEmpty()) {
                return "missing channelId";
            }
            if (title.isEmpty()) {
                return "missing title";
            }
            if (!TARGET_PREVIEW.equals(target) && !TARGET_WATCH_NEXT.equals(target)) {
                return "unknown target '" + target + "'";
            }
            if (items.isEmpty()) {
                return unresolved > 0 ? "no card opens its app" : "no items";
            }
            if (app.isEmpty()) {
                for (Item item : items) {
                    if (item.link.isEmpty()) {
                        return "item without link and no app package";
                    }
                }
            }
            return null;
        }
    }

    /** The channel description this app stores for a row; stable across versions. */
    static String channelKey(String channelId) {
        return "tvfeeder:" + channelId;
    }

    private Feeder() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Runs the sync and stores its report; called from SyncJobService's thread.
     * Synchronised because the periodic job and an immediate run can overlap, and both
     * clear and re-publish the same rows.
     */
    static synchronized Report sync(Context context) {
        String country = Country.saved(context);
        String url = configuredUrl(context);
        String source = url.isEmpty() ? TopSource.label(country) : url;
        String body;
        try {
            body = url.isEmpty()
                    ? TopSource.fetch(installedApps(context), country, AppLocale.code(context),
                            target(context))
                    : download(url);
        } catch (Exception error) {
            Log.w(TAG, "refresh failed: " + error);
            return offline(context, source, error);
        }

        List<Row> rows;
        try {
            rows = parse(body);
        } catch (JSONException error) {
            return offline(context, source, error);
        }

        // Only a document that parsed is a good copy, and it replaces the previous
        // one in a single rename, so a half-written file can never be published.
        saveCache(context, body);
        // The legacy external URL carries no country, so it plans no rows of its own.
        List<String> planned = url.isEmpty()
                ? TopSource.plannedIds(installedApps(context), country)
                : new ArrayList<String>();
        Report report = publish(context, rows, planned, source);
        if (report.ok) {
            prefs(context).edit().putBoolean(PREF_PUBLISHED, true).apply();
        }
        Log.i(TAG, report.text);
        return report;
    }

    /**
     * The optional external URL; empty means the box fetches the sources itself (S2).
     * An upgrade still carries the LAN URL the PC needed, so that pref is dropped
     * once and the box starts autonomous without the user touching it (S1).
     */
    static String configuredUrl(Context context) {
        SharedPreferences prefs = prefs(context);
        if (!prefs.getBoolean(PREF_AUTONOMOUS, false)) {
            prefs.edit().remove(PREF_URL).putBoolean(PREF_AUTONOMOUS, true).apply();
        }
        return prefs.getString(PREF_URL, "").trim();
    }

    /** Provider table for every autonomous row; this replaced swapping the URL. */
    static String target(Context context) {
        return prefs(context).getString(PREF_TARGET, TARGET_PREVIEW);
    }

    /**
     * A failed refresh leaves the published rows alone; the validated cache is
     * republished only on a cold start (S5), so an offline sync never churns the
     * provider and the second one already has nothing left to do.
     */
    private static Report offline(Context context, String source, Exception error) {
        StringBuilder text = new StringBuilder(
                context.getString(R.string.report_refresh_failed, source, error.getMessage()))
                .append('\n')
                .append(cacheNote(context));
        String cached = TopSource.recover(readCache(context),
                prefs(context).getBoolean(PREF_PUBLISHED, false));
        List<Row> rows = null;
        if (cached != null) {
            try {
                rows = parse(cached);
            } catch (JSONException unreadable) {
                text.append('\n').append(context.getString(R.string.report_cache_unreadable));
            }
        }
        if (rows == null) {
            return new Report(false, text + "\n"
                    + context.getString(R.string.report_no_valid_rows) + "\n"
                    + context.getString(R.string.report_previous_kept));
        }
        Report recovered = publish(context, rows, TopSource.plannedIds(installedApps(context), Country.saved(context)),
                context.getString(R.string.report_source_cached, source));
        if (recovered.ok) {
            prefs(context).edit().putBoolean(PREF_PUBLISHED, true).apply();
        }
        return new Report(recovered.ok,
                text + "\n" + context.getString(R.string.report_cached_copy) + "\n" + recovered.text);
    }

    static void storeReport(Context context, Report report) {
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        prefs(context).edit()
                .putString(PREF_STATUS, (report.ok ? "OK " : "FAILED ") + stamp + " · " + report.summary())
                .putString(PREF_REPORT, report.text)
                .putLong(PREF_RUN, System.currentTimeMillis())
                .apply();
    }

    // ---------------------------------------------------------------- download

    private static String download(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            int code = connection.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("HTTP " + code);
            }
            InputStream in = connection.getInputStream();
            try {
                return TopSource.readBody(in);
            } finally {
                in.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Replaces the last good copy in one rename, so no reader sees half a file. */
    /** The cache belongs to one country, so a switch never republishes the old one. */
    private static String cacheName(Context context) {
        return CACHE_PREFIX + Country.saved(context).toLowerCase(Locale.US) + ".json";
    }

    private static void saveCache(Context context, String body) {
        File target = new File(context.getFilesDir(), cacheName(context));
        File temp = new File(target.getPath() + ".tmp");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(temp);
            out.write(body.getBytes("UTF-8"));
            out.getFD().sync();
            out.close();
            out = null;
            if (!temp.renameTo(target)) {
                throw new IOException("could not replace " + target);
            }
        } catch (IOException error) {
            Log.w(TAG, "could not cache " + target + ": " + error);
            temp.delete();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    private static String readCache(Context context) {
        File cache = new File(context.getFilesDir(), cacheName(context));
        if (!cache.exists()) {
            return null;
        }
        InputStream in = null;
        try {
            in = new FileInputStream(cache);
            return TopSource.readBody(in);
        } catch (IOException error) {
            Log.w(TAG, "could not read " + cache + ": " + error);
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // nothing useful to do
                }
            }
        }
    }

    private static String cacheNote(Context context) {
        File cache = new File(context.getFilesDir(), cacheName(context));
        if (!cache.exists()) {
            return context.getString(R.string.report_no_cache);
        }
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(cache.lastModified()));
        return context.getString(R.string.report_cache_at, stamp);
    }

    // ------------------------------------------------------------------- parse

    private static List<Row> parse(String body) throws JSONException {
        JSONObject document = new JSONObject(body);
        int schema = document.optInt("schema", 0);
        if (schema != SCHEMA) {
            throw new JSONException("unsupported schema " + schema);
        }
        JSONArray array = document.optJSONArray("rows");
        if (array == null) {
            throw new JSONException("document has no rows array");
        }

        List<Row> rows = new ArrayList<Row>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) {
                continue;
            }
            Row row = new Row();
            row.channelId = object.optString("channelId", "").trim();
            row.title = object.optString("title", "").trim();
            row.app = object.optString("app", "").trim();
            row.target = object.optString("target", TARGET_PREVIEW).trim();
            row.week = object.optString("week", "").trim();

            JSONArray notices = object.optJSONArray("notices");
            if (notices != null) {
                for (int n = 0; n < notices.length(); n++) {
                    String notice = notices.optString(n, "").trim();
                    if (!notice.isEmpty()) {
                        row.notices.add(notice);
                    }
                }
            }

            JSONArray items = object.optJSONArray("items");
            if (items != null) {
                for (int n = 0; n < items.length(); n++) {
                    JSONObject object2 = items.optJSONObject(n);
                    if (object2 == null) {
                        continue;
                    }
                    Item item = new Item();
                    item.title = object2.optString("title", "").trim();
                    item.poster = object2.optString("poster", "").trim();
                    item.thumb = object2.optString("thumb", "").trim();
                    item.link = object2.optString("link", "").trim();
                    if (item.title.isEmpty()) {
                        continue; // never publish a placeholder title (S5)
                    }
                    if (item.poster.isEmpty() || item.thumb.isEmpty()) {
                        row.degraded++;
                    }
                    row.items.add(item);
                }
            }
            rows.add(row);
        }
        return rows;
    }

    // ----------------------------------------------------------------- publish

    private static Report publish(Context context, List<Row> rows, List<String> planned,
            String source) {
        ContentResolver resolver = context.getContentResolver();
        String pkg = context.getPackageName();
        StringBuilder text = new StringBuilder();
        int published = 0;
        int items = 0;
        int degraded = 0;
        int errors = 0;
        int skipped = 0;

        List<Row> publishable = new ArrayList<Row>();
        for (Row row : rows) {
            // S12: only rows whose app is installed. Another TV in the house may have
            // it, so the row is skipped here and named in the report, not degraded.
            if (!row.app.isEmpty() && !installed(context, row.app)) {
                skipped++;
                text.append(context.getString(R.string.report_row_skipped,
                        row.channelId.isEmpty() ? "?" : row.channelId, row.app)).append('\n');
                continue;
            }
            row.resolve(context); // S3a: a card that opens nothing is never published
            String problem = row.problem();
            if (problem == null) {
                publishable.add(row);
            } else {
                errors++;
                text.append(context.getString(R.string.report_row_error,
                        row.channelId.isEmpty() ? "?" : row.channelId, problem)).append('\n');
            }
        }
        if (publishable.isEmpty()) {
            // S5: never clear the previous rows without something to put in their place.
            return new Report(false, context.getString(R.string.report_no_valid_rows) + "\n"
                    + context.getString(R.string.report_source, source) + "\n"
                    + text + context.getString(R.string.report_previous_kept));
        }
        clearOwnPrograms(resolver);
        // S14: only rows the app no longer intends survive the delete, so a source
        // that failed this run keeps the row it published last time (S5). The keep
        // set holds channel keys, the same shape the provider stores as description.
        Set<String> keep = new HashSet<String>();
        for (String channelId : planned) {
            keep.add(channelKey(channelId));
        }
        for (Row row : publishable) {
            keep.add(row.key());
        }

        for (Row row : publishable) {
            String label = row.channelId + " (" + row.target + ")";
            try {
                if (row.watchNext()) {
                    publishWatchNext(context, pkg, row);
                } else {
                    publishPreview(context, pkg, row);
                }
                published++;
                items += row.items.size();
                degraded += row.degraded;
                List<String> parts = new ArrayList<String>();
                parts.add(context.getString(R.string.report_items_count, row.items.size()));
                if (!row.week.isEmpty()) {
                    parts.add(context.getString(R.string.report_week, row.week));
                }
                if (row.degraded > 0) {
                    parts.add(context.getString(R.string.report_no_artwork, row.degraded));
                }
                if (row.unresolved > 0) {
                    parts.add(context.getString(R.string.report_no_link, row.unresolved));
                }
                text.append("- ").append(label).append(": ")
                        .append(TextUtils.join(", ", parts)).append('\n');
                // The first card's URI: what a launcher will parse and fire.
                text.append(context.getString(R.string.report_opens, row.items.get(0).intent))
                        .append('\n');
                for (String notice : row.notices) {
                    text.append(context.getString(R.string.report_notice, notice)).append('\n');
                }
            } catch (RuntimeException error) {
                errors++;
                text.append(context.getString(R.string.report_row_error, label, error.toString()))
                        .append('\n');
                Log.w(TAG, "publish failed for " + label, error);
            }
        }

        boolean ok = published > 0;
        int stale = 0;
        // S14 covers a country or language switch. A document from the legacy
        // external URL plans no rows, so pruning would delete rows it omits.
        if (published > 0 && !planned.isEmpty()) {
            stale = deleteStaleChannels(context, pkg, keep);
        }
        String head = context.getString(
                ok ? R.string.report_published : R.string.report_nothing,
                published, items, degraded, errors);
        if (skipped > 0) {
            head = head + "\n" + context.getString(R.string.report_skipped, skipped);
        }
        if (stale > 0) {
            head = head + "\n" + context.getString(R.string.report_stale_rows, stale);
        }
        return new Report(ok, head + "\n" + context.getString(R.string.report_source, source)
                + "\n" + text);
    }

    private static void publishPreview(Context context, String pkg, Row row) {
        ContentResolver resolver = context.getContentResolver();
        long channelId = upsertChannel(context, pkg, row);
        Uri programs = TvContract.PreviewPrograms.CONTENT_URI;

        // Inserted back to front: some launchers order a channel by insertion and
        // show the newest row first, so rank 1 must be the last card written to be
        // the first one drawn.
        for (int i = row.items.size() - 1; i >= 0; i--) {
            Item item = row.items.get(i);
            ContentValues values = new ContentValues();
            values.put(TvContract.PreviewPrograms.COLUMN_CHANNEL_ID, channelId);
            values.put(TvContract.PreviewPrograms.COLUMN_TITLE, item.title);
            values.put(COL_TYPE, TvContract.PreviewPrograms.TYPE_MOVIE);
            values.put(TvContract.PreviewPrograms.COLUMN_POSTER_ART_URI, emptyToNull(item.poster));
            values.put(TvContract.PreviewPrograms.COLUMN_THUMBNAIL_URI, emptyToNull(item.thumb));
            values.put(TvContract.PreviewPrograms.COLUMN_INTENT_URI, item.intent);
            values.put(TvContract.PreviewPrograms.COLUMN_SHORT_DESCRIPTION, row.title);
            // A larger weight is a higher priority, for launchers that honour it.
            values.put(TvContract.PreviewPrograms.COLUMN_WEIGHT, row.items.size() - i);
            // 1 so that other launchers can read the row: TvProvider only widens
            // READ_TV_LISTINGS queries to rows with searchable=1.
            values.put(COL_SEARCHABLE, 1);
            resolver.insert(programs, values);
        }
    }

    private static void publishWatchNext(Context context, String pkg, Row row) {
        ContentResolver resolver = context.getContentResolver();
        Uri programs = TvContract.WatchNextPrograms.CONTENT_URI;

        long now = System.currentTimeMillis();
        // Back to front for the same reason as the preview cards: rank 1 is the last
        // row written, so a launcher that sorts by insertion draws it first.
        for (int i = row.items.size() - 1; i >= 0; i--) {
            Item item = row.items.get(i);
            ContentValues values = new ContentValues();
            values.put(TvContract.WatchNextPrograms.COLUMN_CONTENT_ID, row.key());
            values.put(TvContract.WatchNextPrograms.COLUMN_TITLE, item.title);
            values.put(COL_TYPE, TvContract.WatchNextPrograms.TYPE_MOVIE);
            // The table has no TYPE_NEW; the "new recommendation" flag is WATCH_NEXT_TYPE_NEW.
            values.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                    TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_NEW);
            values.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, emptyToNull(item.poster));
            values.put(TvContract.WatchNextPrograms.COLUMN_THUMBNAIL_URI, emptyToNull(item.thumb));
            values.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, item.intent);
            values.put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, row.title);
            // Watch Next has no weight column; recency keeps the ranking order.
            values.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, now - i);
            values.put(COL_SEARCHABLE, 1);
            resolver.insert(programs, values);
        }
    }

    /**
     * Drops every program this package published, before a full re-publish.
     *
     * No selection is passed on purpose: TvProvider rejects a non-empty selection with
     * SecurityException for callers without ACCESS_ALL_EPG_DATA, and it already scopes
     * deletes to the calling package (S4).
     */
    private static void clearOwnPrograms(ContentResolver resolver) {
        resolver.delete(TvContract.PreviewPrograms.CONTENT_URI, null, null);
        resolver.delete(TvContract.WatchNextPrograms.CONTENT_URI, null, null);
    }

    /**
     * Removes this package's channels that the new document does not contain (S14), so
     * switching country never leaves an empty row on the launcher.
     *
     * Ownership is re-checked here: with READ_TV_LISTINGS granted the provider also
     * returns other packages' searchable channels, and those are never touched. The
     * description holds the row key, which is what the document knows its rows by.
     */
    private static int deleteStaleChannels(Context context, String pkg, Set<String> keep) {
        ContentResolver resolver = context.getContentResolver();
        List<Long> stale = new ArrayList<Long>();
        Cursor cursor = resolver.query(TvContract.Channels.CONTENT_URI,
                new String[] {TvContract.Channels._ID, COL_PACKAGE_NAME, COL_DESCRIPTION},
                null, null, null);
        if (cursor == null) {
            return 0;
        }
        try {
            while (cursor.moveToNext()) {
                if (!pkg.equals(cursor.getString(1)) || keep.contains(cursor.getString(2))) {
                    continue;
                }
                stale.add(cursor.getLong(0));
            }
        } finally {
            cursor.close();
        }
        for (Long id : stale) {
            // By id and without a selection, for the reason given in clearOwnPrograms.
            resolver.delete(ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, id),
                    null, null);
        }
        return stale.size();
    }

    /** Creates or updates this package's channel for the row and returns its id. */
    private static long upsertChannel(Context context, String pkg, Row row) {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(COL_PACKAGE_NAME, pkg);
        values.put(COL_TYPE, TvContract.Channels.TYPE_PREVIEW);
        values.put(COL_DISPLAY_NAME, row.title);
        values.put(COL_DESCRIPTION, row.key());
        // searchable=1 is what makes the row readable by another launcher (S6).
        // browsable is deliberately not written: TvProvider rejects it for callers
        // without ACCESS_ALL_EPG_DATA (signature|privileged) and it defaults to 1.
        values.put(COL_SEARCHABLE, 1);
        values.put(COL_INPUT_ID, pkg);
        if (!row.app.isEmpty()) {
            String appLink = appLink(context, row.app);
            if (appLink != null) {
                values.put(COL_APP_LINK_INTENT_URI, appLink);
            }
        }

        long existing = findOwnedChannel(resolver, pkg, row.key());
        if (existing > 0) {
            // No selection: TvProvider refuses one from callers without
            // ACCESS_ALL_EPG_DATA, and the id in the URI is already ours.
            resolver.update(ContentUris.withAppendedId(TvContract.Channels.CONTENT_URI, existing),
                    values, null, null);
            return existing;
        }
        Uri created = resolver.insert(TvContract.Channels.CONTENT_URI, values);
        if (created == null) {
            throw new IllegalStateException("provider rejected the channel");
        }
        return ContentUris.parseId(created);
    }

    /**
     * Looks the row's channel up among this package's channels only (S4). The provider
     * already scopes the query to this package, and with READ_TV_LISTINGS granted it also
     * returns other packages' searchable channels, so ownership is re-checked here. A
     * non-empty selection would be rejected with SecurityException.
     */
    private static long findOwnedChannel(ContentResolver resolver, String pkg, String key) {
        Cursor cursor = resolver.query(TvContract.Channels.CONTENT_URI,
                new String[] {TvContract.Channels._ID, COL_PACKAGE_NAME, COL_DESCRIPTION},
                null, null, null);
        if (cursor == null) {
            return -1;
        }
        try {
            while (cursor.moveToNext()) {
                if (!pkg.equals(cursor.getString(1)) || !key.equals(cursor.getString(2))) {
                    continue;
                }
                return cursor.getLong(0);
            }
        } finally {
            cursor.close();
        }
        return -1;
    }

    /** The device's answer to "is this app here", for planning and for skipping rows. */
    private static TopSource.Installed installedApps(final Context context) {
        return new TopSource.Installed() {
            @Override
            public boolean has(String app) {
                return installed(context, app);
            }
        };
    }

    /** Whether this TV has the app, which decides if its row exists at all (S12). */
    private static boolean installed(Context context, String app) {
        try {
            context.getPackageManager().getPackageInfo(app, 0);
            return true;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    /**
     * The intent URI for one card, resolved on this device (S3a).
     *
     * A package-pinned data intent only resolves when the app registers a filter for
     * that exact link. The deep link is tried first and the app launch intent second,
     * so a card always opens the platform app itself; a row whose app is not installed
     * is not published at all (S12), which keeps every card honest.
     */
    private static String cardIntent(Context context, Row row, Item item) {
        PackageManager manager = context.getPackageManager();
        Intent intent = null;
        if (!item.link.isEmpty()) {
            intent = new Intent(Intent.ACTION_VIEW, Uri.parse(item.link));
            if (!row.app.isEmpty()) {
                intent.setPackage(row.app);
            }
            intent = resolved(manager, intent);
        }
        if (intent == null && !row.app.isEmpty()) {
            intent = resolved(manager, manager.getLaunchIntentForPackage(row.app));
        }
        if (intent == null) {
            return null;
        }
        // A launcher keys a card on this URI: the title keeps ten cards of one app
        // apart, and the target app ignores the extra.
        intent.putExtra(CARD_TITLE, item.title);
        return intent.toUri(Intent.URI_INTENT_SCHEME);
    }

    /** The same intent pointed at the activity that handles it, if any. */
    private static Intent resolved(PackageManager manager, Intent intent) {
        if (intent == null) {
            return null;
        }
        List<ResolveInfo> handlers =
                manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (handlers == null || handlers.isEmpty() || handlers.get(0).activityInfo == null) {
            return null;
        }
        ResolveInfo handler = handlers.get(0);
        intent.setComponent(new ComponentName(handler.activityInfo.packageName,
                handler.activityInfo.name));
        return intent;
    }

    /** The channel's app link: its launch intent, or null when the app is not there. */
    private static String appLink(Context context, String app) {
        PackageManager manager = context.getPackageManager();
        Intent intent = resolved(manager, manager.getLaunchIntentForPackage(app));
        return intent == null ? null : intent.toUri(Intent.URI_INTENT_SCHEME);
    }
}
