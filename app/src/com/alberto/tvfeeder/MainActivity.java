package com.alberto.tvfeeder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
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

import java.util.HashSet;
import java.util.Set;

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
    private Button languageView;
    private Button countryView;
    private Button platformsView;
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

    /** Applies the stored language before any resource is read. */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(AppLocale.wrap(base));
    }

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

        languageView = (Button) findViewById(R.id.language);
        showLanguage();
        languageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // Keep whatever is typed in the URL field across the rebuild.
                Feeder.prefs(MainActivity.this).edit()
                        .putString(Feeder.PREF_URL, urlView.getText().toString().trim()).apply();
                AppLocale.next(MainActivity.this);
                // Rebuild the screen so every label is read in the new language.
                recreate();
            }
        });

        platformsView = (Button) findViewById(R.id.platforms);
        platformsView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickPlatforms();
            }
        });

        countryView = (Button) findViewById(R.id.country);
        showCountry();
        showPlatforms();
        countryView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pickCountry();
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

    private void showLanguage() {
        languageView.setText(getString(R.string.language, getString(AppLocale.label(this))));
    }

    private void showPlatforms() {
        platformsView.setText(getString(R.string.platforms,
                Platforms.saved(this).size(), Platforms.count()));
    }

    private void showCountry() {
        countryView.setText(getString(R.string.country, Country.name(this, Country.saved(this))));
    }

    /**
     * The countries the sources answer for, named in the screen's language (S11).
     * Picking one stores it and republishes the rows for it (S14).
     */
    private void pickCountry() {
        final String[] codes = TopSource.countries();
        String[] names = new String[codes.length];
        String current = Country.saved(this);
        int checked = 0;
        for (int i = 0; i < codes.length; i++) {
            names[i] = Country.name(this, codes[i]);
            if (codes[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.country_title)
                .setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Country.save(MainActivity.this, codes[which]);
                        showCountry();
                        dialog.dismiss();
                        startSync();
                    }
                })
                .show();
    }

    /**
     * One checkbox per platform, ticked by default only where the app is installed
     * (S12). Ticking one that is not here is allowed but publishes nothing for it, and
     * the dialog says so, so a household never gets a row it cannot open.
     */
    private void pickPlatforms() {
        final Set<String> saved = Platforms.saved(this);
        final int count = Platforms.count();
        final String[] labels = new String[count];
        final boolean[] checked = new boolean[count];
        for (int i = 0; i < count; i++) {
            boolean here = Platforms.installed(this, i);
            labels[i] = here ? Platforms.label(i)
                    : Platforms.label(i) + getString(R.string.platforms_missing);
            checked[i] = saved.contains(Platforms.key(i));
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.platforms_title)
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Set<String> keys = new HashSet<String>();
                        for (int i = 0; i < count; i++) {
                            if (checked[i]) {
                                keys.add(Platforms.key(i));
                            }
                        }
                        Platforms.save(MainActivity.this, keys);
                        showPlatforms();
                        startSync();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
        StringBuilder out = new StringBuilder(getString(R.string.diagnostics_header)).append('\n');
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
            out.append(getString(R.string.diagnostics_rows, rows, getPackageName()));
        } catch (RuntimeException error) {
            out.append(getString(R.string.diagnostics_failed, error.toString()));
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        // A channel with no items renders as an empty row, so the count is what
        // tells an empty row apart from a missing one.
        out.append('\n').append(getString(R.string.diagnostics_items_header)).append('\n');
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
            out.append(getString(R.string.diagnostics_failed, error.toString())).append('\n');
        } finally {
            if (programs != null) {
                programs.close();
            }
        }
        for (Map.Entry<String, String> channel : channelNames.entrySet()) {
            Integer seen = counts.get(channel.getKey());
            out.append(getString(R.string.diagnostics_channel,
                    channel.getValue(), channel.getKey(), seen == null ? 0 : seen)).append('\n');
        }
        out.append(getString(R.string.diagnostics_watch_next, watchNextCount(resolver)));
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
