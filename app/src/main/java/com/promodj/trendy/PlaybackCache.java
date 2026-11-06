package com.promodj.trendy;

import android.content.Context;
import android.util.Log;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Последняя сессия прослушивания: очередь, текущий трек и позиция.
 * Файл лежит в filesDir, а не в кэше, поэтому переживает перезапуск и
 * очистку кэша системой.
 */
final class PlaybackCache {

    static final class Snapshot {
        final ArrayList<Track> queue;
        final int index;
        final int positionMs;
        /** Версия на момент сохранения: после clear() старые записи не нужны. */
        final long version;

        Snapshot(List<Track> queue, int index, int positionMs, long version) {
            this.queue = new ArrayList<>(queue);
            this.index = index;
            this.positionMs = positionMs;
            this.version = version;
        }
    }

    private static final String TAG = "TrendySession";
    private static final String FILE_NAME = "playback.json";

    private static final Object lock = new Object();
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();
    /** Актуальный снимок: при частых вызовах пишем только последний. */
    private static Snapshot pending;
    private static boolean scheduled;
    private static long version;

    private PlaybackCache() {
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    /**
     * Очередь меняется часто, а позиция — раз в несколько секунд, поэтому
     * запись идёт в отдельном потоке и без накопления задач.
     */
    static void saveAsync(Context context, List<Track> queue, int index, int positionMs) {
        final Context app = context.getApplicationContext();
        final Snapshot snapshot;
        synchronized (lock) {
            snapshot = new Snapshot(queue, index, positionMs, version);
            pending = snapshot;
            if (scheduled) {
                return;
            }
            scheduled = true;
        }
        writer.execute(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    Snapshot next;
                    synchronized (lock) {
                        next = pending;
                        pending = null;
                        if (next == null) {
                            scheduled = false;
                            return;
                        }
                    }
                    write(app, next);
                }
            }
        });
    }

    /** Синхронная запись: нужна, когда сервис завершается и поток может не успеть. */
    static void saveNow(Context context, List<Track> queue, int index, int positionMs) {
        Snapshot snapshot;
        synchronized (lock) {
            snapshot = new Snapshot(queue, index, positionMs, version);
        }
        write(context.getApplicationContext(), snapshot);
    }

    static Snapshot load(Context context) {
        File source = file(context);
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
            if (array == null || array.length() == 0) {
                return null;
            }
            ArrayList<Track> queue = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    queue.add(TrackJson.fromJson(item));
                }
            }
            if (queue.isEmpty()) {
                return null;
            }
            int index = root.optInt("index", 0);
            if (index < 0 || index >= queue.size()) {
                index = 0;
            }
            long savedVersion;
            synchronized (lock) {
                savedVersion = version;
            }
            return new Snapshot(queue, index,
                    Math.max(0, root.optInt("position", 0)), savedVersion);
        } catch (Exception ignored) {
            // Битая сессия не должна мешать запуску приложения.
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

    /** Забыть сессию: пользователь явно остановил воспроизведение. */
    static void clear(Context context) {
        synchronized (lock) {
            version++;
            pending = null;
        }
        File source = file(context);
        if (source.exists()) {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
    }

    private static void write(Context context, Snapshot snapshot) {
        Writer unit = null;
        File tmp = null;
        try {
            synchronized (lock) {
                if (snapshot.version != version) {
                    // Пока писали, пользователь нажал «Стоп» — файл не нужен.
                    return;
                }
            }
            JSONArray array = new JSONArray();
            for (Track track : snapshot.queue) {
                array.put(TrackJson.toJson(track));
            }
            JSONObject root = new JSONObject();
            root.put("savedAt", System.currentTimeMillis());
            root.put("index", snapshot.index);
            root.put("position", snapshot.positionMs);
            root.put("tracks", array);

            File dir = context.getFilesDir();
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                return;
            }
            tmp = File.createTempFile("playback_", ".tmp", dir);
            unit = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            unit.write(root.toString());
            unit.close();
            unit = null;

            File target = file(context);
            synchronized (lock) {
                if (snapshot.version != version) {
                    return;
                }
            }
            if (target.exists() && !target.delete()) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(target);
        } catch (Exception error) {
            Log.w(TAG, "playback session save failed", error);
        } finally {
            if (unit != null) {
                try {
                    unit.close();
                } catch (Exception ignored) {
                }
            }
            if (tmp != null && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }
}
