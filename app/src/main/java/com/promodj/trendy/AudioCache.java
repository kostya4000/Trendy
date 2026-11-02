package com.promodj.trendy;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Локальный кэш аудиофайлов: скачивает трек в cacheDir/audio и отдаёт его плееру
 * без обращения к сети.
 */
final class AudioCache {

    interface Listener {
        void onCacheFinished(long trackId, boolean success);
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

    static File file(Context context, long trackId) {
        return new File(new File(context.getApplicationContext().getCacheDir(), DIR_NAME),
                trackId + ".mp3");
    }

    static boolean isCached(Context context, long trackId) {
        File target = file(context, trackId);
        return target.isFile() && target.length() >= MIN_VALID_SIZE;
    }

    static boolean isDownloading(long trackId) {
        return downloading.contains(trackId);
    }

    static boolean download(Context context, Track track, Listener listener) {
        if (track == null || isCached(context, track.id)) {
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
                    downloadTrack(app, target);
                    success = true;
                } catch (Exception error) {
                    Log.w(TAG, "cache download failed for " + target.id, error);
                } finally {
                    downloading.remove(target.id);
                    notifyFinished(listener, target.id, success);
                }
            }
        });
        return true;
    }

    static void delete(Context context, long trackId) {
        File target = file(context, trackId);
        if (target.isFile()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
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
        File temp = null;
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

            File dir = new File(context.getCacheDir(), DIR_NAME);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Не удалось создать каталог кэша");
            }
            temp = File.createTempFile("trendy_", ".part", dir);

            InputStream input = connection.getInputStream();
            FileOutputStream output = new FileOutputStream(temp);
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

            if (temp.length() < MIN_VALID_SIZE) {
                throw new IOException("Пустой аудиофайл");
            }

            File target = file(context, trackId);
            if (target.exists() && !target.delete()) {
                throw new IOException("Не удалось заменить файл кэша");
            }
            if (!temp.renameTo(target)) {
                throw new IOException("Не удалось сохранить файл кэша");
            }
            temp = null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (temp != null && temp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
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
