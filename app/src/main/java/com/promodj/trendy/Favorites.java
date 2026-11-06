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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Избранные треки: список в filesDir/favorites.json и быстрый доступ по id
 * для отметок в ленте и на экране плеера.
 *
 * Список небольшой, поэтому держим его в памяти после первого чтения, а
 * запись идёт в отдельном потоке: переключение сердца не должно ждать диск.
 */
final class Favorites {

    private static final String TAG = "TrendyFavorites";
    private static final String FILE_NAME = "favorites.json";

    private static final Object lock = new Object();
    private static final ExecutorService writer = Executors.newSingleThreadExecutor();

    /** Новые треки сверху: так избранное выглядит как история добавлений. */
    private static final List<Track> items = new ArrayList<>();
    private static final Set<Long> ids = new HashSet<>();
    private static boolean loaded;
    private static Context appContext;
    private static String pendingJson;
    private static boolean writeScheduled;

    private static final Runnable writeTask = new Runnable() {
        @Override
        public void run() {
            while (true) {
                String json;
                Context context;
                synchronized (lock) {
                    json = pendingJson;
                    pendingJson = null;
                    if (json == null) {
                        writeScheduled = false;
                        return;
                    }
                    context = appContext;
                }
                if (context != null) {
                    write(context, json);
                }
            }
        }
    };

    private Favorites() {
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    static boolean isFavorite(Context context, long trackId) {
        if (trackId <= 0) {
            return false;
        }
        synchronized (lock) {
            ensureLoadedLocked(context);
            return ids.contains(trackId);
        }
    }

    static int count(Context context) {
        synchronized (lock) {
            ensureLoadedLocked(context);
            return items.size();
        }
    }

    /** Копия списка: вызывающий код не должен менять хранилище напрямую. */
    static List<Track> all(Context context) {
        synchronized (lock) {
            ensureLoadedLocked(context);
            return new ArrayList<>(items);
        }
    }

    /** @return true, если трек теперь в избранном. */
    static boolean toggle(Context context, Track track) {
        if (track == null || track.id <= 0) {
            return false;
        }
        synchronized (lock) {
            ensureLoadedLocked(context);
            boolean added;
            if (ids.remove(track.id)) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).id == track.id) {
                        items.remove(i);
                        break;
                    }
                }
                added = false;
            } else {
                ids.add(track.id);
                items.add(0, track);
                added = true;
            }
            scheduleWriteLocked();
            return added;
        }
    }

    /** @return true, если трек действительно был в избранном. */
    static boolean remove(Context context, long trackId) {
        if (trackId <= 0) {
            return false;
        }
        synchronized (lock) {
            ensureLoadedLocked(context);
            if (!ids.remove(trackId)) {
                return false;
            }
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).id == trackId) {
                    items.remove(i);
                    break;
                }
            }
            scheduleWriteLocked();
            return true;
        }
    }

    private static void ensureLoadedLocked(Context context) {
        if (loaded) {
            return;
        }
        loaded = true;
        appContext = context.getApplicationContext();
        File source = file(appContext);
        if (!source.isFile()) {
            return;
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
                return;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Track track = TrackJson.fromJson(item);
                if (track.id > 0 && ids.add(track.id)) {
                    items.add(track);
                }
            }
        } catch (Exception ignored) {
            // Битый файл не должен мешать запуску: начинаем с пустого списка.
            items.clear();
            ids.clear();
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void scheduleWriteLocked() {
        try {
            JSONArray array = new JSONArray();
            for (Track track : items) {
                array.put(TrackJson.toJson(track));
            }
            JSONObject root = new JSONObject();
            root.put("savedAt", System.currentTimeMillis());
            root.put("tracks", array);
            pendingJson = root.toString();
        } catch (Exception error) {
            Log.w(TAG, "favorites serialization failed", error);
            return;
        }
        if (!writeScheduled) {
            writeScheduled = true;
            writer.execute(writeTask);
        }
    }

    private static void write(Context context, String json) {
        Writer unit = null;
        File tmp = null;
        try {
            File dir = context.getFilesDir();
            if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                return;
            }
            tmp = File.createTempFile("favorites_", ".tmp", dir);
            unit = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            unit.write(json);
            unit.close();
            unit = null;

            File target = file(context);
            if (target.exists() && !target.delete()) {
                return;
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(target);
        } catch (Exception error) {
            Log.w(TAG, "favorites save failed", error);
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
