import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/** Минимальный HTTP-сервер для раздачи одного APK по локальной сети. */
public final class FileServer {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path file = Paths.get(args.length > 1 ? args[1] : "/home/Trendy-1.0.apk")
                .toAbsolutePath();
        if (!Files.isRegularFile(file)) {
            System.err.println("Файл не найден: " + file);
            System.exit(1);
        }
        final byte[] data = Files.readAllBytes(file);
        final String name = file.getFileName().toString();

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/" + name, exchange -> serve(exchange, data, name));
        server.createContext("/", exchange -> {
            byte[] body = ("Trendy APK: /" + name + "\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        });
        server.setExecutor(null);
        server.start();
        System.out.println("Serving " + file + " on port " + port);
        System.out.println("http://127.0.0.1:" + port + "/" + name);
    }

    private static void serve(HttpExchange exchange, byte[] data, String name)
            throws java.io.IOException {
        Headers headers = exchange.getResponseHeaders();
        String lower = name.toLowerCase(Locale.US);
        String mime = lower.endsWith(".zip") ? "application/zip"
                : lower.endsWith(".apk") ? "application/vnd.android.package-archive"
                : "application/octet-stream";
        headers.set("Content-Type", mime);
        headers.set("Content-Disposition", "attachment; filename=\"" + name + "\"");
        headers.set("Accept-Ranges", "bytes");
        headers.set("Cache-Control", "no-store");

        String method = exchange.getRequestMethod();
        String range = exchange.getRequestHeaders().getFirst("Range");
        long start = 0;
        long end = data.length - 1L;
        boolean partial = false;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring(6).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash));
                    String tail = spec.substring(dash + 1);
                    if (tail.length() > 0) {
                        end = Long.parseLong(tail);
                    }
                    partial = true;
                } else if (dash == 0 && spec.length() > 1) {
                    long suffix = Long.parseLong(spec.substring(1));
                    start = Math.max(0, data.length - suffix);
                    end = data.length - 1L;
                    partial = true;
                }
            } catch (NumberFormatException ignored) {
                partial = false;
            }
        }
        if (start < 0) {
            start = 0;
        }
        if (end >= data.length) {
            end = data.length - 1L;
        }
        if (start > end) {
            partial = false;
        }

        int length = (int) (end - start + 1L);
        if ("HEAD".equals(method)) {
            if (partial) {
                headers.set("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            }
            exchange.sendResponseHeaders(partial ? 206 : 200, -1);
            exchange.close();
            return;
        }
        if (partial) {
            headers.set("Content-Range", "bytes " + start + "-" + end + "/" + data.length);
            exchange.sendResponseHeaders(206, length);
        } else {
            exchange.sendResponseHeaders(200, length);
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data, (int) start, length);
        }
        exchange.close();
    }
}
