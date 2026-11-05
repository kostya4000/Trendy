package com.promodj.trendy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Асинхронная загрузка обложек: память → диск → сеть.
 * Одинаковые запросы объединяются, устаревшие результаты не применяются
 * к переиспользованным view, а дисковый кэш ограничен по количеству файлов.
 */
public final class ImageLoader {

    public interface Callback {
        void onImage(Bitmap bitmap);
    }

    private static final long MAX_DOWNLOAD_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_DISK_FILES = 300;
    private static final int TARGET_SIZE = 640;

    private static volatile ImageLoader instance;

    private final LruCache<String, Bitmap> memory;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File diskDir;
    /** Один сетевой запрос на URL, сколько бы view его ни попросили. */
    private final Map<String, Pending> pending = new HashMap<>();

    private ImageLoader(Context context) {
        Context app = context.getApplicationContext();
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024L / 8L);
        memory = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        diskDir = new File(app.getCacheDir(), "images");
        if (!diskDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            diskDir.mkdirs();
        }
    }

    public static ImageLoader get(Context context) {
        ImageLoader local = instance;
        if (local == null) {
            synchronized (ImageLoader.class) {
                local = instance;
                if (local == null) {
                    local = new ImageLoader(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public void load(final String url, final ImageView view) {
        if (url == null || url.length() == 0) {
            view.setTag(null);
            view.setImageResource(R.drawable.ic_cover_placeholder);
            return;
        }
        view.setTag(url);
        Bitmap cached = memory.get(url);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }
        view.setImageResource(R.drawable.ic_cover_placeholder);
        request(url, new ViewCallback(view, url));
    }

    public void load(final String url, final Callback callback) {
        if (callback == null) {
            return;
        }
        if (url == null || url.length() == 0) {
            callback.onImage(null);
            return;
        }
        Bitmap cached = memory.get(url);
        if (cached != null) {
            callback.onImage(cached);
            return;
        }
        request(url, callback);
    }

    private void request(final String url, final Callback callback) {
        Pending entry;
        synchronized (pending) {
            entry = pending.get(url);
            if (entry != null) {
                entry.callbacks.add(callback);
                return;
            }
            entry = new Pending();
            entry.callbacks.add(callback);
            pending.put(url, entry);
        }
        final Pending target = entry;
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = loadBlocking(url);
                if (bitmap != null) {
                    memory.put(url, bitmap);
                }
                final List<Callback> callbacks;
                synchronized (pending) {
                    // Запрос мог быть отменён, пока файл качался.
                    if (pending.get(url) != target) {
                        return;
                    }
                    pending.remove(url);
                    callbacks = new ArrayList<>(target.callbacks);
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        for (Callback item : callbacks) {
                            try {
                                item.onImage(bitmap);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                });
            }
        });
    }

    /**
     * Отменяет доставку результата конкретному view. Сам запрос продолжается,
     * если его ждут другие элементы списка.
     */
    public void cancel(ImageView view, String url) {
        if (view == null || url == null) {
            return;
        }
        synchronized (pending) {
            Pending entry = pending.get(url);
            if (entry == null) {
                return;
            }
            for (int i = entry.callbacks.size() - 1; i >= 0; i--) {
                Callback callback = entry.callbacks.get(i);
                if (callback instanceof ViewCallback && ((ViewCallback) callback).view == view) {
                    entry.callbacks.remove(i);
                }
            }
            if (entry.callbacks.isEmpty()) {
                pending.remove(url);
            }
        }
    }

    private static final class Pending {
        final List<Callback> callbacks = new ArrayList<>();
    }

    private static final class ViewCallback implements Callback {
        final ImageView view;
        final String url;

        ViewCallback(ImageView view, String url) {
            this.view = view;
            this.url = url;
        }

        @Override
        public void onImage(Bitmap bitmap) {
            // View мог быть переиспользован под другую обложку.
            if (!url.equals(view.getTag())) {
                return;
            }
            if (bitmap != null) {
                view.setImageBitmap(bitmap);
            } else {
                view.setImageResource(R.drawable.ic_cover_placeholder);
            }
        }
    }

    private Bitmap loadBlocking(String url) {
        Bitmap bitmap = readDisk(url);
        if (bitmap != null) {
            return bitmap;
        }
        byte[] bytes = download(url);
        if (bytes == null) {
            return null;
        }
        bitmap = decode(bytes);
        if (bitmap != null) {
            writeDisk(url, bitmap);
        }
        return bitmap;
    }

    private byte[] download(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", Http.USER_AGENT);
            connection.setRequestProperty("Accept", "image/*,*/*");
            if (connection.getResponseCode() != 200) {
                return null;
            }
            String lengthHeader = connection.getHeaderField("Content-Length");
            if (lengthHeader != null) {
                try {
                    if (Long.parseLong(lengthHeader) > MAX_DOWNLOAD_BYTES) {
                        return null;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
            byte[] buffer = new byte[8192];
            int read;
            try {
                while ((read = input.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_DOWNLOAD_BYTES) {
                        // Не отдаём декодеру обрезанное изображение.
                        return null;
                    }
                }
            } finally {
                try {
                    input.close();
                } catch (Exception ignored) {
                }
            }
            return out.toByteArray();
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private Bitmap decode(byte[] bytes) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        int longest = Math.max(width, height);
        while (longest / sample > TARGET_SIZE * 2) {
            sample *= 2;
        }
        return sample;
    }

    private File diskFile(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(url.getBytes("UTF-8"));
            StringBuilder name = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                name.append(String.format("%02x", b));
            }
            return new File(diskDir, name.toString() + ".img");
        } catch (Exception ignored) {
            return new File(diskDir, Integer.toHexString(url.hashCode()) + ".img");
        }
    }

    private Bitmap readDisk(String url) {
        File file = diskFile(url);
        if (!file.isFile()) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getPath(), options);
            if (bitmap == null) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return null;
            }
            //noinspection ResultOfMethodCallIgnored
            file.setLastModified(System.currentTimeMillis());
            return bitmap;
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
    }

    private void writeDisk(String url, Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        File file = diskFile(url);
        File tmp = null;
        FileOutputStream out = null;
        try {
            // Уникальный tmp: одна и та же обложка может грузиться из двух view.
            tmp = File.createTempFile("img_", ".tmp", diskDir);
            out = new FileOutputStream(tmp);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
            out.close();
            out = null;
            if (file.exists()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        } catch (Exception ignored) {
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
            if (tmp != null && tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
        trimDisk();
    }

    /** Держим в дисковом кэше не больше MAX_DISK_FILES картинок. */
    private void trimDisk() {
        File[] files = diskDir.listFiles();
        if (files == null || files.length <= MAX_DISK_FILES) {
            return;
        }
        List<File> images = new ArrayList<>();
        for (File file : files) {
            if (file.getName().endsWith(".img")) {
                images.add(file);
            }
        }
        if (images.size() <= MAX_DISK_FILES) {
            return;
        }
        File[] sorted = images.toArray(new File[0]);
        Arrays.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                long a = left.lastModified();
                long b = right.lastModified();
                if (a == b) {
                    return 0;
                }
                return a < b ? -1 : 1;
            }
        });
        int remove = sorted.length - MAX_DISK_FILES;
        for (int i = 0; i < remove; i++) {
            //noinspection ResultOfMethodCallIgnored
            sorted[i].delete();
        }
    }
}
