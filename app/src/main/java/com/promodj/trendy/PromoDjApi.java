package com.promodj.trendy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Загружает ленту promodj.com/trendy и метаданные через публичный players/config API.
 */
final class PromoDjApi {

    static final String BASE = "https://promodj.com";

    /** Страницы ленты: /trendy/{genre}[/{filter}]. */
    private static final Pattern TRACK_LINK = Pattern.compile(
            "<a\\s+[^>]*href=\"(?:(?:https?:)?//promodj\\.com)?/"
                    + "([^/\"<>]+)/(tracks|remixes|mixes|podcasts|lives|radioshows|radio_shows|radios)"
                    + "/(\\d+)/([^\"/<>]+)\"[^>]*>",
            Pattern.CASE_INSENSITIVE);

    private static final int BATCH_SIZE = 20;

    private PromoDjApi() {
    }

    static String feedPath(String genre, int filter) {
        StringBuilder path = new StringBuilder("/trendy/").append(genre);
        if (filter == 1) {
            path.append("/mixes_podcasts_and_radioshows");
        } else if (filter == 2) {
            path.append("/tracks_and_remixes");
        }
        return path.toString();
    }

    static List<Track> fetch(String genre, int filter) throws IOException {
        String html = Http.get(BASE + feedPath(genre, filter));
        Map<Long, Track> parsed = new LinkedHashMap<>();
        Matcher matcher = TRACK_LINK.matcher(html);
        while (matcher.find()) {
            long id;
            try {
                id = Long.parseLong(matcher.group(3));
            } catch (NumberFormatException e) {
                continue;
            }
            if (parsed.containsKey(id)) {
                continue;
            }
            String user = matcher.group(1);
            String kindPath = matcher.group(2).toLowerCase(Locale.US);
            String slug = matcher.group(4);

            Track track = new Track();
            track.id = id;
            track.user = user;
            track.artist = prettifyUser(user);
            track.kind = kindFromPath(kindPath);
            track.pageUrl = BASE + "/" + user + "/" + kindPath + "/" + id + "/" + slug;
            track.streamUrl = BASE + "/prelisten/" + id + "/" + slug + ".mp3?hq=1";
            track.title = prettifySlug(slug);
            parsed.put(id, track);
        }

        if (parsed.isEmpty()) {
            throw new IOException("В ленте не найдено треков");
        }

        List<Track> tracks = new ArrayList<>(parsed.values());
        for (int start = 0; start < tracks.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, tracks.size());
            try {
                enrich(tracks.subList(start, end));
            } catch (Exception ignored) {
                // Оставляем данные, полученные из HTML: они позволяют играть трек.
            }
        }
        for (Track track : tracks) {
            splitArtist(track);
        }
        return tracks;
    }

    /**
     * Одним POST-запросом получаем конфигурацию плеера для пачки файлов:
     * название, обложку, длительность, ссылки на поток и скачивание.
     */
    private static void enrich(List<Track> chunk) throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) {
                body.append('&');
            }
            body.append("multi[").append(i).append("][method]=players/config");
            body.append("&multi[").append(i).append("][params][kind]=cover.big");
            body.append("&multi[").append(i).append("][params][fileID]=").append(chunk.get(i).id);
        }
        String response = Http.postForm(BASE + "/api/multi.json", body.toString());
        JSONArray array = new JSONArray(response);
        for (int i = 0; i < chunk.size() && i < array.length(); i++) {
            JSONObject config = array.optJSONObject(i);
            if (config == null || config.has("error")) {
                continue;
            }
            Track track = chunk.get(i);

            String title = config.optString("title", "").trim();
            if (title.length() > 0) {
                track.title = title;
            }

            String kind = config.optString("kind", "");
            if (kind.length() > 0) {
                track.kind = kind;
            }

            JSONArray sources = config.optJSONArray("sources");
            if (sources != null && sources.length() > 0) {
                JSONObject source = sources.optJSONObject(0);
                if (source != null) {
                    String url = absolute(source.optString("URL", ""));
                    if (url.length() > 0) {
                        track.streamUrl = url;
                    }
                    double length = source.optDouble("length", 0);
                    if (length > 0) {
                        track.durationSec = (int) Math.round(length);
                    }
                }
            }

            String download = absolute(config.optString("downloadURL", ""));
            if (download.length() > 0) {
                track.downloadUrl = download;
            }

            JSONObject covers = config.optJSONObject("coverURL");
            if (covers != null) {
                String large = absolute(covers.optString("1200", ""));
                if (large.length() == 0) {
                    large = absolute(covers.optString("2000", ""));
                }
                if (large.length() > 0) {
                    track.coverLargeUrl = large;
                }
                String cover = absolute(covers.optString("600", ""));
                if (cover.length() == 0) {
                    cover = large;
                }
                if (cover.length() == 0) {
                    cover = absolute(covers.optString("2000", ""));
                }
                if (cover.length() > 0) {
                    track.coverUrl = cover;
                }
            }

            String titleUrl = absolute(config.optString("titleURL", ""));
            if (titleUrl.length() > 0) {
                track.pageUrl = titleUrl;
            }
        }
    }

    private static String absolute(String url) {
        if (url == null) {
            return "";
        }
        String value = url.trim();
        if (value.startsWith("//")) {
            return "https:" + value;
        }
        if (value.startsWith("/")) {
            return BASE + value;
        }
        return value;
    }

    private static String kindFromPath(String kindPath) {
        if ("tracks".equals(kindPath)) {
            return "track";
        }
        if ("remixes".equals(kindPath)) {
            return "remix";
        }
        if ("mixes".equals(kindPath)) {
            return "mix";
        }
        if ("podcasts".equals(kindPath)) {
            return "podcast";
        }
        if ("lives".equals(kindPath)) {
            return "live";
        }
        if ("radioshows".equals(kindPath) || "radio_shows".equals(kindPath)
                || "radios".equals(kindPath)) {
            return "radioshow";
        }
        return "audio";
    }

    private static String prettifySlug(String slug) {
        String text;
        try {
            text = URLDecoder.decode(slug, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            text = slug;
        }
        return text.replace('_', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String prettifyUser(String user) {
        String text = user.replace('-', ' ').replace('.', ' ').replace('_', ' ');
        text = text.replaceAll("\\s+", " ").trim();
        if (text.length() == 0) {
            return "";
        }
        StringBuilder result = new StringBuilder(text.length());
        boolean upper = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                result.append(c);
                upper = true;
            } else if (upper) {
                result.append(Character.toUpperCase(c));
                upper = false;
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }

    /** "Artist - Title" встречается очень часто; разделяем для аккуратной подписи. */
    private static void splitArtist(Track track) {
        String title = track.title == null ? "" : track.title.trim();
        int dash = title.indexOf(" - ");
        if (dash > 1 && dash < 60) {
            String artist = title.substring(0, dash).trim();
            String rest = title.substring(dash + 3).trim();
            if (artist.length() > 0 && rest.length() > 0 && !artist.endsWith("(")) {
                track.artist = artist;
                track.title = rest;
            }
        }
    }
}
