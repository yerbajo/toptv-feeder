package com.alberto.tvfeeder;

import android.content.Context;

import java.util.Locale;

/**
 * The country whose rankings are collected (S11).
 *
 * The default is the TV's own country when the sources answer it, otherwise ES.
 * The screen stores the user's choice in the same preferences as the language, so
 * it survives a reboot, and every name comes from Locale rather than a hardcoded
 * string, in the language the screen is drawn in (S12).
 */
final class Country {

    static final String PREF_COUNTRY = "country";

    private Country() {
    }

    /** The stored choice, or the device's country when supported, otherwise ES. */
    static String saved(Context context) {
        String stored = Feeder.prefs(context).getString(PREF_COUNTRY, "").trim();
        if (TopSource.supported(stored)) {
            return stored;
        }
        String device = Locale.getDefault().getCountry();
        return TopSource.supported(device) ? device : TopSource.DEFAULT_COUNTRY;
    }

    static void save(Context context, String country) {
        Feeder.prefs(context).edit().putString(PREF_COUNTRY, country).apply();
    }

    /** The country's name in the screen's language (S12). */
    static String name(Context context, String country) {
        return TopSource.countryName(country, AppLocale.screenLocale(context));
    }
}
