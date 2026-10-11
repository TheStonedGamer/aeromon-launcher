package cc.aeromon.launcher;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.io.*;

final class Net {
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(25)).build();
    static HttpRequest.Builder request(String url) {
        URI uri = URI.create(url);
        if (!uri.getScheme().equals("https") && !(uri.getScheme().equals("http") && "127.0.0.1".equals(uri.getHost()))) throw new IllegalArgumentException("HTTPS required");
        return HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).header("User-Agent", "AeromonLauncher/0.1 (https://aeromon.cc)");
    }
    static byte[] bytes(String url) throws Exception {
        var response = HTTP.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if(response.statusCode()!=200) throw new IOException("Download failed (HTTP " + response.statusCode() + "): " + URI.create(url).getHost());
        return response.body();
    }
    static JsonObject json(String url) throws Exception { return JsonParser.parseString(new String(bytes(url), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject(); }
    static void download(String url, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        var response = HTTP.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try(var body = response.body()) {
            if(response.statusCode()!=200) throw new IOException("Download failed (HTTP " + response.statusCode() + "): " + URI.create(url).getHost());
            Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
