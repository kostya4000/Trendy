package com.promodj.trendy;

import org.json.JSONObject;

/**
 * Общая (де)сериализация Track: кэш ленты, сохранённая музыка и последняя
 * сессия прослушивания используют один и тот же набор полей.
 */
final class TrackJson {

    private TrackJson() {
    }

    static JSONObject toJson(Track track) throws org.json.JSONException {
        JSONObject item = new JSONObject();
        item.put("id", track.id);
        item.put("title", track.title);
        item.put("artist", track.artist);
        item.put("user", track.user);
        item.put("kind", track.kind);
        item.put("page", track.pageUrl);
        item.put("stream", track.streamUrl);
        item.put("download", track.downloadUrl);
        item.put("cover", track.coverUrl);
        item.put("coverLarge", track.coverLargeUrl);
        item.put("duration", track.durationSec);
        return item;
    }

    static Track fromJson(JSONObject item) {
        Track track = new Track();
        track.id = item.optLong("id");
        track.title = item.optString("title");
        track.artist = item.optString("artist");
        track.user = item.optString("user");
        track.kind = item.optString("kind");
        track.pageUrl = item.optString("page");
        track.streamUrl = item.optString("stream");
        track.downloadUrl = item.optString("download");
        track.coverUrl = item.optString("cover");
        track.coverLargeUrl = item.optString("coverLarge");
        track.durationSec = item.optInt("duration");
        return track;
    }
}
