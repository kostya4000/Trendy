package com.promodj.trendy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Сохранённая музыка. Треки, которые пользователь сохранил сам, лежат в
 * filesDir/audio: Android не вычищает этот каталог при нехватке места.
 * cacheDir/audio остаётся только для старых загрузок и временных файлов.
 */
final class AudioCache {

    interface Listener {
        void onCacheFinished(long trackId, boolean success);
    }

    /** Один сохранённый трек для экрана настроек. */
    static final class Saved {
        final Track track;
        final long savedAt;
        final long bytes;

        Saved(Track track, long savedAt, long bytes) {
            this.track = track;
            this.savedAt = savedAt;
            this.bytes = bytes;
        }
    }

    private static final String TAG = "TrendyCache";
    private static final String DIR_NAME = "audio";
    private static final long MIN_VALID_SIZE = 1024L;

    private static final Set<Long> downloading =
            Collections.synchronizedSet(new HashSet<Long>());
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());

    private AudioCache() {
    }

    static File savedDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), DIR_NAME);
    }

    static File tempDir(Context context) {
        return new File(context.getApplicationContext().getCacheDir(), DIR_NAME);
    }

    static File savedFile(Context context, long trackId) {
        return new File(savedDir(context), trackId + ".mp3");
    }

    static File tempFile(Context context, long trackId) {
        return new File(tempDir(context), trackId + ".mp3");
    }

    private static File metaFile(Context context, long trackId) {
        return new File(savedDir(context), trackId + ".json");
    }

    /**
     * Файл для воспроизведения: сохранённый трек важнее временного кэша,
     * оставшегося от прошлых версий приложения.
     */
    static File file(Context context, long trackId) {
        File saved = savedFile(context, trackId);
        if (isValid(saved)) {
            return saved;
        }
        File temp = tempFile(context, trackId);
        if (isValid(temp)) {
            return temp;
        }
        return saved;
    }

    static boolean isSaved(Context context, long trackId) {
        return isValid(savedFile(context, trackId));
    }

    static boolean isCached(Context context, long trackId) {
        return isSaved(context, trackId) || isValid(tempFile(context, trackId));
    }

    static boolean isDownloading(long trackId) {
        return downloading.contains(trackId);
    }

    static boolean download(Context context, Track track, Listener listener) {
        if (track == null || isSaved(context, track.id)) {
            return false;
        }
        synchronized (downloading) {
            if (downloading.contains(track.id)) {
                return false;
            }
            downloading.add(track.id);
        }
        final Context app = context.getApplicationContext();
        final Track target = track;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    File saved = savedFile(app, target.id);
                    File temp = tempFile(app, target.id);
                    // Старая версия качала сразу в cacheDir. Переносим файл,
                    // не тратя трафик повторно.
                    if (isValid(temp) && move(temp, saved) && isValid(saved)) {
                        writeMeta(app, target);
                    } else {
                        downloadTrack(app, target);
                        writeMeta(app, target);
                    }
                    success = isSaved(app, target.id);
                } catch (Exception error) {
                    Log.w(TAG, "cache download failed for " + target.id, error);
                } finally {
                    synchronized (downloading) {
                        downloading.remove(target.id);
                    }
                    notifyFinished(listener, target.id, success);
                }
            }
        });
        return true;
    }

    /** Удаляет сохранённый трек, метаданные и временную копию. */
    static void delete(Context context, long trackId) {
        deleteQuietly(savedFile(context, trackId));
        deleteQuietly(metaFile(context, trackId));
        deleteQuietly(tempFile(context, trackId));
    }

    /** Временный файл можно удалять автоматически, сохранённый — нельзя. */
    static void deleteTemp(Context context, long trackId) {
        deleteQuietly(tempFile(context, trackId));
    }

    static List<Saved> listSaved(Context context) {
        List<Saved> result = new ArrayList<>();
        File[] files = savedDir(context).listFiles();
        if (files == null) {
            return result;
        }
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".mp3")) {
                continue;
            }
            long id = parseId(name);
            if (id <= 0) {
                continue;
            }
            if (!isValid(file)) {
                deleteQuietly(file);
                continue;
            }
            result.add(readSaved(context, id, file));
        }
        Collections.sort(result, new Comparator<Saved>() {
            @Override
            public int compare(Saved left, Saved right) {
                if (left.savedAt == right.savedAt) {
                    return 0;
                }
                return left.savedAt > right.savedAt ? -1 : 1;
            }
        });
        return result;
    }

    static long totalSavedBytes(Context context) {
        long total = 0L;
        File[] files = savedDir(context).listFiles();
        if (files == null) {
            return total;
        }
        for (File file : files) {
            if (file.getName().endsWith(".mp3")) {
                total += file.length();
            }
        }
        return total;
    }

    static long tempBytes(Context context) {
        long total = 0L;
        File[] files = tempDir(context).listFiles();
        if (files == null) {
            return total;
        }
        for (File file : files) {
            total += file.length();
        }
        return total;
    }

    static void clearTemp(Context context) {
        File[] files = tempDir(context).listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            deleteQuietly(file);
        }
    }

    /** Удаляет всю сохранённую музыку и метаданные. Возвращает число треков. */
    static int clearSaved(Context context) {
        int count = 0;
        File[] files = savedDir(context).listFiles();
        if (files == null) {
            return count;
        }
        for (File file : files) {
            if (file.getName().endsWith(".mp3")) {
                count++;
            }
            deleteQuietly(file);
        }
        return count;
    }

    private static void downloadTrack(Context context, Track track) throws IOException {
        String primary = track.downloadUrl == null || track.downloadUrl.length() == 0
                ? track.streamUrl : track.downloadUrl;
        if (primary == null || primary.length() == 0) {
            throw new IOException("Нет ссылки для загрузки");
        }
        String fallback = track.streamUrl;
        try {
            fetch(context, track.id, primary);
        } catch (IOException first) {
            if (fallback == null || fallback.length() == 0 || fallback.equals(primary)) {
                throw first;
            }
            // downloadURL может быть недоступен; prelisten-ссылка обычно работает.
            fetch(context, track.id, fallback);
        }
    }

    private static void fetch(Context context, long trackId, String url) throws IOException {
        HttpURLConnection connection = null;
        File part = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", Http.USER_AGENT);
            connection.setRequestProperty("Referer", "https://promodj.com/trendy");
            connection.setRequestProperty("Accept", "audio/*,*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }
            String contentType = connection.getContentType();
            if (contentType != null
                    && contentType.toLowerCase(Locale.US).startsWith("text/")) {
                throw new IOException("Не аудио: " + contentType);
            }

            File dir = savedDir(context);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Не удалось создать каталог сохранённой музыки");
            }
            part = new File(dir, trackId + ".part");
            deleteQuietly(part);

            InputStream input = connection.getInputStream();
            FileOutputStream output = new FileOutputStream(part);
            try {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                output.flush();
            } finally {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }

            if (!isValid(part)) {
                throw new IOException("Пустой аудиофайл");
            }

            File target = savedFile(context, trackId);
            if (target.exists() && !target.delete()) {
                throw new IOException("Не удалось заменить сохранённый файл");
            }
            if (!part.renameTo(target)) {
                throw new IOException("Не удалось сохранить файл");
            }
            part = null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (part != null) {
                deleteQuietly(part);
            }
        }
    }

    private static void writeMeta(Context context, Track track) {
        File audio = savedFile(context, track.id);
        if (!isValid(audio)) {
            return;
        }
        File part = new File(savedDir(context), track.id + ".json.part");
        FileOutputStream output = null;
        try {
            JSONObject root = TrackJson.toJson(track);
            root.put("savedAt", System.currentTimeMillis());

            output = new FileOutputStream(part);
            output.write(root.toString().getBytes("UTF-8"));
            output.close();
            output = null;

            File target = metaFile(context, track.id);
            deleteQuietly(target);
            //noinspection ResultOfMethodCallIgnored
            part.renameTo(target);
        } catch (Exception error) {
            Log.w(TAG, "metadata write failed for " + track.id, error);
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
            }
            deleteQuietly(part);
        }
    }

    private static Saved readSaved(Context context, long trackId, File audio) {
        Track track = null;
        long savedAt = audio.lastModified();
        File meta = metaFile(context, trackId);
        Reader reader = null;
        try {
            if (meta.isFile()) {
                StringBuilder text = new StringBuilder();
                reader = new InputStreamReader(new FileInputStream(meta), "UTF-8");
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    text.append(buffer, 0, read);
                }
                JSONObject item = new JSONObject(text.toString());
                track = TrackJson.fromJson(item);
                if (track.id <= 0) {
                    track.id = trackId;
                }
                savedAt = item.optLong("savedAt", savedAt);
            }
        } catch (Exception ignored) {
            // Битые метаданные не мешают слушать сам файл.
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (track == null) {
            track = new Track();
            track.id = trackId;
            track.title = context.getString(R.string.untitled_track);
        }
        return new Saved(track, savedAt, audio.length());
    }

    private static long parseId(String name) {
        try {
            return Long.parseLong(name.substring(0, name.length() - 4));
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static boolean isValid(File file) {
        return file != null && file.isFile() && file.length() >= MIN_VALID_SIZE;
    }

    private static boolean move(File from, File to) {
        if (!from.isFile()) {
            return false;
        }
        File dir = to.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return false;
        }
        if (to.exists()) {
            deleteQuietly(to);
        }
        if (from.renameTo(to)) {
            return true;
        }
        InputStream input = null;
        OutputStream output = null;
        try {
            input = new FileInputStream(from);
            output = new FileOutputStream(to);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
            deleteQuietly(from);
            return true;
        } catch (Exception error) {
            deleteQuietly(to);
            return false;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
            if (output != null) {
                try {
                    output.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private static void notifyFinished(final Listener listener, final long trackId,
                                       final boolean success) {
        if (listener == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onCacheFinished(trackId, success);
            }
        });
    }
}
