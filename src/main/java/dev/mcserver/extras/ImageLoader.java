package dev.mcserver.mapimage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Laedt Bilder sicher aus dem Netz und bereitet sie fuer Karten auf. Alle Methoden sind thread-safe (async nutzbar). */
final class ImageLoader {

    static final int TILE = 128;
    private static final int MAX_REDIRECTS = 4;

    enum Mode { FIT, FILL, STRETCH }

    private final HttpClient client;
    private final long maxBytes;
    private final long maxPixels;
    private final Duration timeout;
    private final List<String> allowedHosts;
    private final boolean allowPrivate;

    ImageLoader(long maxBytes, long maxPixels, int timeoutSeconds, List<String> allowedHosts, boolean allowPrivate) {
        this.maxBytes = maxBytes;
        this.maxPixels = maxPixels;
        this.timeout = Duration.ofSeconds(Math.max(3, timeoutSeconds));
        this.allowedHosts = allowedHosts;
        this.allowPrivate = allowPrivate;
        this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER) // Weiterleitungen werden einzeln geprueft
                .connectTimeout(this.timeout)
                .build();
    }

    // ------------------------------------------------------------ Download

    BufferedImage download(String url) throws IOException {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Das ist keine gültige Adresse.");
        }
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            checkUri(uri);
            HttpResponse<InputStream> resp;
            try {
                HttpRequest req = HttpRequest.newBuilder(uri)
                        .timeout(timeout)
                        .header("User-Agent", "Mozilla/5.0 (compatible; MapImage-Minecraft-Plugin)")
                        .header("Accept", "image/*")
                        .GET().build();
                resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download abgebrochen.");
            } catch (IOException e) {
                throw new IOException("Verbindung fehlgeschlagen.");
            }
            try (InputStream body = resp.body()) {
                int code = resp.statusCode();
                if (code >= 300 && code < 400) {
                    String loc = resp.headers().firstValue("Location").orElse(null);
                    if (loc == null) {
                        throw new IOException("Ungültige Weiterleitung.");
                    }
                    uri = uri.resolve(loc);
                    continue;
                }
                if (code != 200) {
                    throw new IOException("Der Server antwortete mit HTTP " + code + ".");
                }
                long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > maxBytes) {
                    throw new IOException("Die Datei ist zu groß (max. " + maxBytes / 1024 / 1024 + " MB).");
                }
                return decode(readLimited(body));
            }
        }
        throw new IOException("Zu viele Weiterleitungen.");
    }

    private byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("Die Datei ist zu groß (max. " + maxBytes / 1024 / 1024 + " MB).");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private BufferedImage decode(byte[] data) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new IOException("Unbekanntes Bildformat (erlaubt: PNG, JPG, GIF, BMP).");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long w = reader.getWidth(0), h = reader.getHeight(0);
                if (w * h > maxPixels) {
                    throw new IOException("Das Bild ist zu groß (" + w + "x" + h + " Pixel).");
                }
                BufferedImage img = reader.read(0);
                if (img == null) {
                    throw new IOException("Das Bild konnte nicht gelesen werden.");
                }
                return img;
            } finally {
                reader.dispose();
            }
        }
    }

    // ------------------------------------------------------------ Sicherheit (Adresse pruefen)

    private void checkUri(URI uri) throws IOException {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("Nur http:// und https:// Adressen sind erlaubt.");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("Die Adresse enthält keinen Host.");
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (!allowedHosts.isEmpty()) {
            boolean ok = false;
            for (String a : allowedHosts) {
                String x = a.toLowerCase(Locale.ROOT).trim();
                if (!x.isEmpty() && (h.equals(x) || h.endsWith("." + x))) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new IOException("Dieser Host ist nicht erlaubt: " + host);
            }
        }
        if (allowPrivate) {
            return;
        }
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(host);
        } catch (IOException e) {
            throw new IOException("Host nicht gefunden: " + host);
        }
        for (InetAddress a : addrs) {
            if (isInternal(a)) {
                throw new IOException("Adressen im lokalen Netzwerk sind nicht erlaubt.");
            }
        }
    }

    private static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) { // 100.64.0.0/10 (Carrier-grade NAT)
            return (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
        }
        return b.length == 16 && (b[0] & 0xFE) == 0xFC; // fc00::/7 (IPv6 unique local)
    }

    // ------------------------------------------------------------ Aufbereitung

    /** Skaliert das Bild auf cols*rows Karten und liefert die Kacheln zeilenweise (links->rechts, oben->unten). */
    static List<BufferedImage> toTiles(BufferedImage src, int cols, int rows, Mode mode) {
        int w = cols * TILE, h = rows * TILE;
        int sw = src.getWidth(), sh = src.getHeight();
        int dw, dh;
        switch (mode) {
            case STRETCH -> { dw = w; dh = h; }
            case FILL -> {
                double s = Math.max((double) w / sw, (double) h / sh);
                dw = Math.max(1, (int) Math.round(sw * s));
                dh = Math.max(1, (int) Math.round(sh * s));
            }
            default -> {
                double s = Math.min((double) w / sw, (double) h / sh);
                dw = Math.max(1, (int) Math.round(sw * s));
                dh = Math.max(1, (int) Math.round(sh * s));
            }
        }
        BufferedImage scaled = resize(src, dw, dh);
        BufferedImage full = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = full.createGraphics();
        g.setColor(java.awt.Color.BLACK);
        g.fillRect(0, 0, w, h);
        g.drawImage(scaled, (w - dw) / 2, (h - dh) / 2, null); // Transparenz wird auf Schwarz gemischt
        g.dispose();

        List<BufferedImage> tiles = new ArrayList<>(cols * rows);
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                BufferedImage tile = new BufferedImage(TILE, TILE, BufferedImage.TYPE_INT_RGB);
                Graphics2D tg = tile.createGraphics();
                tg.drawImage(full.getSubimage(c * TILE, r * TILE, TILE, TILE), 0, 0, null);
                tg.dispose();
                tiles.add(tile);
            }
        }
        return tiles;
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage cur = src;
        while (cur.getWidth() / 2 >= w && cur.getHeight() / 2 >= h) { // schrittweise halbieren = bessere Qualitaet
            cur = scale(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        }
        return scale(cur, w, h);
    }

    private static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }
}
