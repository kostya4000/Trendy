package com.promodj.trendy;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.ListView;

import java.util.Locale;

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

    /**
     * Поднимает содержимое над панелью навигации. На жестовой навигации она
     * прозрачная и рисуется поверх окна, поэтому нижние кнопки иначе обрезаются.
     */
    static void applyBottomInset(final View view) {
        if (view == null) {
            return;
        }
        final int baseBottom = view.getPaddingBottom();
        view.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                        baseBottom + insets.getSystemWindowInsetBottom());
                return insets;
            }
        });
        view.requestApplyInsets();
    }

    /** Строка списка с действием, до которого нужно добраться пультом. */
    interface RowAction {
        int getCount();

        int getActionPosition();

        void setActionPosition(int position);

        void performAction(int position);

        /** Обычное нажатие на строку: включить трек. */
        void performPrimary(int position);
    }

    /**
     * Пускает пульт к кнопке в строке списка. ListView забирает стрелки себе и
     * focusable-кнопки внутри строк недоступны, поэтому «фокус» на кнопке
     * рисуем сами: RIGHT включает его на выбранной строке (кнопка получает
     * ring), LEFT выключает, ENTER нажимает кнопку, UP/DOWN ведут по строкам,
     * а ring едет вместе с выделением.
     */
    static void bindRowAction(final ListView list, final RowAction action) {
        list.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return false;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    int position = list.getSelectedItemPosition();
                    if (position == ListView.INVALID_POSITION) {
                        position = list.getFirstVisiblePosition();
                    }
                    if (position < 0 || position >= action.getCount()) {
                        return false;
                    }
                    action.setActionPosition(position);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (action.getActionPosition() < 0) {
                        return false;
                    }
                    action.setActionPosition(-1);
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER) {
                    if (action.getActionPosition() < 0) {
                        int position = list.getSelectedItemPosition();
                        if (position == ListView.INVALID_POSITION) {
                            return false;
                        }
                        action.performPrimary(position);
                        return true;
                    }
                    int position = action.getActionPosition();
                    action.setActionPosition(-1);
                    action.performAction(position);
                    return true;
                }
                return false;
            }
        });
        list.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                    int position, long id) {
                if (action.getActionPosition() >= 0) {
                    action.setActionPosition(position);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                action.setActionPosition(-1);
            }
        });
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

    /** Размер в русском формате: 850 КБ, 12,4 МБ. */
    static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return Math.max(0L, bytes) + " Б";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return Math.round(kb) + " КБ";
        }
        double mb = kb / 1024.0;
        if (mb < 1024.0) {
            return String.format(Locale.US, "%.1f", mb).replace('.', ',') + " МБ";
        }
        return String.format(Locale.US, "%.2f", mb / 1024.0).replace('.', ',') + " ГБ";
    }
}
