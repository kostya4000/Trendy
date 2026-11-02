package com.promodj.trendy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Небольшой HTTP-клиент без сторонних библиотек.
 */
final class Http {

    static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;

    private Http() {
    }

    static String get(String url) throws IOException {
        return request(url, "GET", null);
    }

    static String postForm(String url, String body) throws IOException {
        return request(url, "POST", body);
    }

    private static String request(String url, String method, String body) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Referer", "https://promodj.com/trendy");

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty(
                        "Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                connection.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                connection.setRequestProperty("Accept", "application/json, text/javascript, */*");
                byte[] bytes = body.getBytes("UTF-8");
                connection.setFixedLengthStreamingMode(bytes.length);
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(bytes);
                    out.flush();
                } finally {
                    out.close();
                }
            } else {
                connection.setRequestProperty(
                        "Accept", "text/html,application/xhtml+xml,*/*;q=0.8");
            }

            int code = connection.getResponseCode();
            InputStream stream = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            String text = stream == null ? "" : readFully(stream);
            if (code < 200 || code >= 400) {
                throw new IOException("HTTP " + code);
            }
            return text;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readFully(InputStream stream) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(16384);
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } finally {
            stream.close();
        }
    }

    /**
     * PromoDJ отдаёт 302 на подписанный URL CDN. Разворачиваем его заранее,
     * чтобы MediaPlayer не зависел от поддержки редиректов.
     */
    static String resolveStream(String url) {
        if (url == null || url.length() == 0) {
            return url;
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("HEAD");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "audio/*,*/*");
            int code = connection.getResponseCode();
            if (code >= 200 && code < 400) {
                String resolved = connection.getURL().toString();
                if (resolved.startsWith("http")) {
                    return resolved;
                }
            }
        } catch (Exception ignored) {
            // Если HEAD не прошёл, пробуем оригинальный URL.
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return url;
    }
}
