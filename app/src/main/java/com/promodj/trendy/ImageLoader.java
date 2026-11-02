package com.promodj.trendy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Асинхронная загрузка обложек: память → диск → сеть.
 */
public final class ImageLoader {

    public interface Callback {
        void onImage(Bitmap bitmap);
    }

    private static volatile ImageLoader instance;

    private final LruCache<String, Bitmap> memory;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File diskDir;

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
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = loadBlocking(url);
                if (bitmap == null) {
                    return;
                }
                memory.put(url, bitmap);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(view.getTag())) {
                            view.setImageBitmap(bitmap);
                        }
                    }
                });
            }
        });
    }

    public void load(final String url, final Callback callback) {
        if (url == null || url.length() == 0) {
            callback.onImage(null);
            return;
        }
        Bitmap cached = memory.get(url);
        if (cached != null) {
            callback.onImage(cached);
            return;
        }
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = loadBlocking(url);
                if (bitmap != null) {
                    memory.put(url, bitmap);
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onImage(bitmap);
                    }
                });
            }
        });
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
        writeDisk(url, bitmap);
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
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                if (out.size() > 8 * 1024 * 1024) {
                    break;
                }
            }
            input.close();
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
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / sample > 640) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (Exception ignored) {
            return null;
        }
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
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(file.getPath(), options);
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
    }
}
