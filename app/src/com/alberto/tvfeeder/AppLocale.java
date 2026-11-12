package com.alberto.tvfeeder;

import android.content.Context;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * The screen language: the TV's own, or one the user picked on the app's screen.
 *
 * Android 9 has no per-app language API, so the choice is applied by handing the
 * activity and the sync path a context whose configuration carries that locale.
 * An empty choice means "follow the box", which is also the default.
 */
final class AppLocale {

    static final String PREF_LANG = "lang";

    /** Empty follows the device; the rest are the languages with strings here. */
    private static final String[] CHOICES = {"", "en", "es"};

    private AppLocale() {
    }

    /** The stored choice, or empty when it is absent or no longer shipped. */
    static String saved(Context context) {
        String lang = Feeder.prefs(context).getString(PREF_LANG, "");
        for (String choice : CHOICES) {
            if (choice.equals(lang)) {
                return choice;
            }
        }
        return "";
    }

    /** The same context, carrying the chosen language when there is one. */
    static Context wrap(Context context) {
        String lang = saved(context);
        if (lang.isEmpty()) {
            return context;
        }
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(new Locale(lang));
        return context.createConfigurationContext(config);
    }

    /** The locale the screen is drawn in: the chosen language, else the TV's own. */
    static Locale screenLocale(Context context) {
        // getLocales() (API 24) is what carries the createConfigurationContext
        // locale above; minSdk is 28.
        return context.getResources().getConfiguration().getLocales().get(0);
    }

    /** Its language code, which the country names and the sources follow (S13). */
    static String code(Context context) {
        String code = screenLocale(context).getLanguage();
        return code.isEmpty() ? "en" : code;
    }

    /** Stores the next language in the cycle so the screen can rebuild with it. */
    static void next(Context context) {
        String current = saved(context);
        int at = 0;
        for (int i = 0; i < CHOICES.length; i++) {
            if (CHOICES[i].equals(current)) {
                at = i;
            }
        }
        Feeder.prefs(context).edit().putString(PREF_LANG, CHOICES[(at + 1) % CHOICES.length]).apply();
    }

    /** The button label for the stored choice. */
    static int label(Context context) {
        String lang = saved(context);
        if ("en".equals(lang)) {
            return R.string.language_en;
        }
        if ("es".equals(lang)) {
            return R.string.language_es;
        }
        return R.string.language_system;
    }
}
