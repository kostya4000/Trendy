package com.promodj.trendy;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;

final class Ui {

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static String formatTime(int seconds) {
        if (seconds < 0) {
            seconds = 0;
        }
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) {
            return h + ":" + (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
        }
        return (m < 10 ? "0" : "") + m + ":" + (s < 10 ? "0" : "") + s;
    }

    static GradientDrawable rounded(Context context, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static void darkSystemBars(Context context) {
        if (!(context instanceof android.app.Activity)) {
            return;
        }
        Window window = ((android.app.Activity) context).getWindow();
        window.setStatusBarColor(0xFF0A0A0F);
        window.setNavigationBarColor(0xFF0A0A0F);
        if (Build.VERSION.SDK_INT >= 23) {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            View decor = window.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    static String pluralTracks(int count) {
        int mod10 = count % 10;
        int mod100 = count % 100;
        if (mod10 == 1 && mod100 != 11) {
            return "трек";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "трека";
        }
        return "треков";
    }

    static String safeFileName(String input) {
        if (input == null) {
            return "track";
        }
        String name = input.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (name.length() == 0) {
            name = "track";
        }
        if (name.length() > 80) {
            name = name.substring(0, 80).trim();
        }
        return name;
    }
}
