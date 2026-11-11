package com.alberto.tvfeeder;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/** Single remote-friendly screen: last report, optional external URL, sync and diagnostics. */
public final class MainActivity extends Activity {

    private static final int REQUEST_LISTINGS = 1;
    private static final int REQUEST_DIAGNOSTICS = 2;
    private static final long POLL_MS = 1500L;
    private static final int POLL_TRIES = 40;
    // Not in the public SDK surface; the provider uses this exact name.
    private static final String READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private EditText urlView;
    private Button targetView;
    private TextView statusView;
    private TextView reportView;
    private long polledRun;
    private int pollsLeft;

    /** The job reports through SharedPreferences, so the screen polls until it lands. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            long run = Feeder.prefs(MainActivity.this).getLong(Feeder.PREF_RUN, 0);
            if (run != polledRun || pollsLeft-- <= 0) {
                refresh();
                return;
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.main);
        urlView = (EditText) findViewById(R.id.url);
        statusView = (TextView) findViewById(R.id.status);
        reportView = (TextView) findViewById(R.id.report);
        // Empty means the box builds the document itself (S1, S2).
        urlView.setText(Feeder.configuredUrl(this));
        targetView = (Button) findViewById(R.id.target);
        showTarget();

        targetView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean watchNext = Feeder.TARGET_WATCH_NEXT.equals(Feeder.target(MainActivity.this));
                Feeder.prefs(MainActivity.this).edit()
                        .putString(Feeder.PREF_TARGET,
                                watchNext ? Feeder.TARGET_PREVIEW : Feeder.TARGET_WATCH_NEXT)
                        .apply();
                showTarget();
                startSync();
            }
        });

        ((Button) findViewById(R.id.sync)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startSync();
            }
        });
        ((Button) findViewById(R.id.diagnose)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startDiagnostics();
            }
        });

        SyncJobService.schedulePeriodic(this);
        refresh();
        if (!hasListings()) {
            // Reading our own channels already needs the runtime permission.
            requestPermissions(new String[] {READ_TV_LISTINGS}, REQUEST_LISTINGS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] granted) {
        boolean ok = granted.length > 0 && granted[0] == PackageManager.PERMISSION_GRANTED;
        if (!ok) {
            statusView.setText(R.string.listings_denied);
            return;
        }
        if (code == REQUEST_DIAGNOSTICS) {
            reportView.setText(diagnostics());
        } else {
            refresh();
        }
    }

    private void startSync() {
        // An empty URL is the normal case now: the box fetches the sources itself.
        SharedPreferences prefs = Feeder.prefs(this);
        prefs.edit().putString(Feeder.PREF_URL, urlView.getText().toString().trim()).apply();
        polledRun = prefs.getLong(Feeder.PREF_RUN, 0);
        pollsLeft = POLL_TRIES;
        statusView.setText(R.string.status_running);
        handler.removeCallbacks(poll);
        SyncJobService.syncNow(this);
        handler.postDelayed(poll, POLL_MS);
    }

    private void startDiagnostics() {
        if (!hasListings()) {
            requestPermissions(new String[] {READ_TV_LISTINGS}, REQUEST_DIAGNOSTICS);
            return;
        }
        reportView.setText(diagnostics());
    }

    private void showTarget() {
        boolean watchNext = Feeder.TARGET_WATCH_NEXT.equals(Feeder.target(this));
        targetView.setText(watchNext ? R.string.target_watch_next : R.string.target_preview);
    }

    private void refresh() {
        SharedPreferences prefs = Feeder.prefs(this);
        String status = prefs.getString(Feeder.PREF_STATUS, null);
        statusView.setText(status == null ? getString(R.string.status_idle) : status);
        reportView.setText(prefs.getString(Feeder.PREF_REPORT, ""));
    }

    private boolean hasListings() {
        return checkSelfPermission(READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    private String diagnostics() {
        ContentResolver resolver = getContentResolver();
        StringBuilder out = new StringBuilder("Provider rows (package_name | display_name | type | searchable)\n");
        Map<String, String> channelNames = new LinkedHashMap<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(TvContract.Channels.CONTENT_URI,
                    new String[] {"_id", "package_name", "display_name", "type", "searchable"},
                    null, null, "package_name, display_name");
            int rows = 0;
            while (cursor != null && cursor.moveToNext()) {
                rows++;
                channelNames.put(cursor.getString(0), cursor.getString(2));
                out.append(cursor.getString(1)).append(" | ")
                        .append(cursor.getString(2)).append(" | ")
                        .append(cursor.getString(3)).append(" | ")
                        .append(cursor.getInt(4)).append('\n');
            }
            out.append(rows).append(" rows; this app is ").append(getPackageName());
        } catch (RuntimeException error) {
            out.append("Query failed: ").append(error);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        // A channel with no items renders as an empty row, so the count is what
        // tells an empty row apart from a missing one.
        out.append("\nItems per channel\n");
        Map<String, Integer> counts = new LinkedHashMap<>();
        Cursor programs = null;
        try {
            programs = resolver.query(TvContract.PreviewPrograms.CONTENT_URI,
                    new String[] {"channel_id"}, null, null, null);
            while (programs != null && programs.moveToNext()) {
                String id = programs.getString(0);
                Integer seen = counts.get(id);
                counts.put(id, seen == null ? 1 : seen + 1);
            }
        } catch (RuntimeException error) {
            out.append("Query failed: ").append(error).append('\n');
        } finally {
            if (programs != null) {
                programs.close();
            }
        }
        for (Map.Entry<String, String> channel : channelNames.entrySet()) {
            Integer seen = counts.get(channel.getKey());
            out.append(channel.getValue()).append(" (" ).append(channel.getKey()).append("): ")
                    .append(seen == null ? 0 : seen).append(" items\n");
        }
        out.append(watchNextCount(resolver)).append(" watch_next items in total");
        return out.toString();
    }

    private int watchNextCount(ContentResolver resolver) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(TvContract.WatchNextPrograms.CONTENT_URI,
                    new String[] {"_id"}, null, null, null);
            return cursor == null ? 0 : cursor.getCount();
        } catch (RuntimeException error) {
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }
}
