package com.alberto.tvfeeder;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The platforms whose rows this TV publishes (S12).
 *
 * The screen shows one checkbox per platform. The default selection is whatever is
 * installed on this device, so a household that only uses Netflix and Prime Video
 * never sees a row for a service it does not have; a household that installs more can
 * tick them, and a ticked platform only publishes where its app exists.
 */
final class Platforms {

    static final String PREF_PLATFORMS = "platforms";

    /** key, label and the app that has to be installed for the row to mean anything. */
    private static final String[][] ALL = {
            {"netflix", "Netflix", TopSource.NETFLIX_PACKAGE},
            {"prime", "Prime Video", TopSource.PRIME_PACKAGE},
            {"apple", "Apple TV", TopSource.APPLE_PACKAGE},
            {"disney", "Disney+", TopSource.DISNEY_PACKAGE},
            {"max", "Max", TopSource.MAX_PACKAGE},
            {"movistar", "Movistar+", TopSource.MOVISTAR_PACKAGE},
            {"skyshowtime", "SkyShowtime", TopSource.SKYSHOWTIME_PACKAGE},
            {"filmin", "Filmin", TopSource.FILMIN_PACKAGE},
    };

    private Platforms() {
    }

    static int count() {
        return ALL.length;
    }

    static String key(int index) {
        return ALL[index][0];
    }

    static String label(int index) {
        return ALL[index][1];
    }

    static String app(int index) {
        return ALL[index][2];
    }

    /** Whether this TV has the platform's app, which is what makes a row openable. */
    static boolean installed(Context context, int index) {
        return Feeder.hasApp(context, app(index));
    }

    /** The platforms already installed here, which is the default selection. */
    static Set<String> installed(Context context) {
        Set<String> keys = new LinkedHashSet<String>();
        for (int i = 0; i < ALL.length; i++) {
            if (installed(context, i)) {
                keys.add(key(i));
            }
        }
        return keys;
    }

    /** The stored choice, or every installed platform when the screen never asked. */
    static Set<String> saved(Context context) {
        SharedPreferences prefs = Feeder.prefs(context);
        String stored = prefs.getString(PREF_PLATFORMS, "");
        if (stored.isEmpty()) {
            return installed(context);
        }
        Set<String> keys = new LinkedHashSet<String>();
        for (String part : stored.split(",")) {
            String key = part.trim();
            if (!key.isEmpty()) {
                keys.add(key);
            }
        }
        return keys;
    }

    static void save(Context context, Set<String> keys) {
        StringBuilder text = new StringBuilder();
        for (String key : keys) {
            if (text.length() > 0) {
                text.append(',');
            }
            text.append(key);
        }
        Feeder.prefs(context).edit().putString(PREF_PLATFORMS, text.toString()).apply();
    }
}
