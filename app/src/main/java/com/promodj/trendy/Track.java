package com.promodj.trendy;

import java.io.Serializable;
import java.util.Locale;

/**
 * Один аудиофайл PromoDJ: трек, ремикс, микс, подкаст или live.
 */
public class Track implements Serializable {

    private static final long serialVersionUID = 1L;

    public long id;
    public String title = "";
    public String artist = "";
    public String user = "";
    public String kind = "";
    public String pageUrl = "";
    public String streamUrl = "";
    public String downloadUrl = "";
    public String coverUrl = "";
    public String coverLargeUrl = "";
    public int durationSec;

    public String durationLabel() {
        if (durationSec <= 0) {
            return "";
        }
        int h = durationSec / 3600;
        int m = (durationSec % 3600) / 60;
        int s = durationSec % 60;
        if (h > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    public String kindLabel() {
        String k = kind == null ? "" : kind.toLowerCase(Locale.US);
        if ("track".equals(k)) {
            return "Трек";
        }
        if ("remix".equals(k)) {
            return "Ремикс";
        }
        if ("mix".equals(k)) {
            return "Микс";
        }
        if ("podcast".equals(k)) {
            return "Подкаст";
        }
        if ("live".equals(k)) {
            return "Live";
        }
        if ("radioshow".equals(k) || "radio".equals(k)) {
            return "Радиошоу";
        }
        return "Аудио";
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder(kindLabel());
        if (artist != null && artist.length() > 0) {
            sb.append(" · ").append(artist);
        }
        return sb.toString();
    }
}
