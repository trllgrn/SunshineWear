package com.example.android.sunshine.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Created by greent on 5/23/16.
 */
public class WeatherFaceUtility {


    public static boolean isMetric(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(context.getString(R.string.pref_units_key),
                context.getString(R.string.pref_units_metric))
                .equals(context.getString(R.string.pref_units_metric));
    }

    public static String formatTemperature(Context context, double temperature, String unit_fmt) {
        // Data stored in Celsius by default.  If user prefers to see in Fahrenheit, convert
        // the values here.
        String suffix = "\u00B0";
        if (unit_fmt.equals("metric")) {
            temperature = (temperature * 1.8) + 32;
        }

        // For presentation, assume the user doesn't care about tenths of a degree.
        return String.format(context.getString(R.string.format_temperature), temperature);
    }
}
