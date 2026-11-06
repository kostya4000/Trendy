package com.promodj.trendy;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

/**
 * Кэш последней успешной ленты, чтобы список открывался мгновенно и без сети.
 */
final class TrackCache {

    static final class Cached {
        final List<Track> tracks;
        final long savedAt;

        Cached(List<Track> tracks, long savedAt) {
            this.tracks = tracks;
            this.savedAt = savedAt;
        }
    }

    private TrackCache() {
    }

    private static File file(Context context, String genre, int filter) {
        File dir = new File(context.getCacheDir(), "trendy");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return new File(dir, genre + "_" + filter + ".json");
    }

    static void save(Context context, String genre, int filter, List<Track> tracks) {
        Writer writer = null;
        File tmp = null;
        try {
            JSONArray array = new JSONArray();
            for (Track track : tracks) {
                array.put(TrackJson.toJson(track));
            }
            JSONObject root = new JSONObject();
            root.put("savedAt", System.currentTimeMillis());
            root.put("tracks", array);

            File target = file(context, genre, filter);
            // Уникальный tmp: параллельные сохранения не должны писать в один файл.
            tmp = File.createTempFile("trendy_", ".tmp", target.getParentFile());
            writer = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            writer.write(root.toString());
            writer.close();
            writer = null;
            if (target.exists() && !target.delete()) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(target);
        } catch (Exception ignored) {
            // Кэш не критичен.
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
            if (tmp != null && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    static Cached load(Context context, String genre, int filter) {
        File source = file(context, genre, filter);
        if (!source.isFile()) {
            return null;
        }
        Reader reader = null;
        try {
            StringBuilder text = new StringBuilder();
            reader = new InputStreamReader(new FileInputStream(source), "UTF-8");
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                text.append(buffer, 0, read);
            }
            JSONObject root = new JSONObject(text.toString());
            JSONArray array = root.optJSONArray("tracks");
            if (array == null) {
                return null;
            }
            List<Track> tracks = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Track track = TrackJson.fromJson(item);
                tracks.add(track);
            }
            return new Cached(tracks, root.optLong("savedAt"));
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
