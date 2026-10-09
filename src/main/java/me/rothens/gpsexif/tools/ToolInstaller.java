package me.rothens.gpsexif.tools;

import static me.rothens.gpsexif.i18n.I18n.tr;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads ExifTool and FFmpeg into ExifTweaker's own folder, so they don't have to be installed by hand (they
 * aren't bundled: ExifTool is a separate project, and FFmpeg builds come with their own licence). Every download is
 * checked against the SHA-256 checksum its publisher gives, and nothing outside the tools folder is touched.
 * <ul>
 *     <li>ExifTool from exiftool.org: the Windows package, or the Perl distribution elsewhere (macOS and Linux have
 *         Perl)</li>
 *     <li>FFmpeg on Windows: the release "essentials" build from gyan.dev, which ffmpeg.org links to. On macOS and
 *         Linux a package manager is the better way.</li>
 * </ul>
 */
public class ToolInstaller {

    /** Reports download progress and can stop it. */
    public interface Progress {
        /** @param total bytes to download, or -1 if unknown */
        void update(String what, long done, long total);

        default boolean isCancelled() {
            return false;
        }
    }

    /** Fetches URLs; replaceable for tests. */
    public interface Http {
        /** The response body; the long is its length, or -1. */
        Response get(URI uri) throws IOException;
    }

    public record Response(InputStream body, long length) {
    }

    public static final String EXIFTOOL_SITE = "https://exiftool.org/";
    public static final String USER_AGENT = "ExifTweaker (https://github.com/rothens/ExifTweaker)";
    public static final String FFMPEG_ZIP = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";
    private static final Pattern VERSION = Pattern.compile("\\d{1,3}\\.\\d{1,3}");

    private final Http http;
    private final Path toolsDir;
    private final String exifToolSite;
    private final String ffmpegZip;
    private final boolean windows;

    public ToolInstaller(String userAgent) {
        this(httpClient(userAgent), defaultToolsDir(), EXIFTOOL_SITE, FFMPEG_ZIP, isWindows());
    }

    ToolInstaller(Http http, Path toolsDir, String exifToolSite, String ffmpegZip, boolean windows) {
        this.http = http;
        this.toolsDir = toolsDir;
        this.exifToolSite = exifToolSite.endsWith("/") ? exifToolSite : exifToolSite + "/";
        this.ffmpegZip = ffmpegZip;
        this.windows = windows;
    }

    /**
     * Where downloaded tools go: {@code %LOCALAPPDATA%\ExifTweaker\tools} on Windows,
     * {@code ~/Library/Application Support/ExifTweaker/tools} on macOS, {@code ~/.local/share/exiftweaker/tools}
     * elsewhere.
     */
    public static Path defaultToolsDir() {
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            String local = System.getenv("LOCALAPPDATA");
            return Path.of(null != local && !local.isBlank() ? local : home + "\\AppData\\Local", "ExifTweaker",
                    "tools");
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", "ExifTweaker", "tools");
        }
        String data = System.getenv("XDG_DATA_HOME");
        return Path.of(null != data && !data.isBlank() ? data : home + "/.local/share", "exiftweaker", "tools");
    }

    public Path getToolsDir() {
        return toolsDir;
    }

    /** Whether {@code executable} is one that was downloaded here (so it can be updated here too). */
    public boolean isDownloaded(String executable) {
        if (null == executable || executable.isBlank()) {
            return false;
        }
        return Path.of(executable).toAbsolutePath().normalize().startsWith(toolsDir.toAbsolutePath().normalize());
    }

    /** Whether FFmpeg can be downloaded on this system (Windows); elsewhere a package manager is the way. */
    public boolean canInstallFfmpeg() {
        return windows;
    }

    /** The latest ExifTool version, e.g. "13.10". */
    public String latestExifToolVersion() throws IOException {
        String text = text(URI.create(exifToolSite + "ver.txt")).strip();
        if (!VERSION.matcher(text).matches()) {
            throw new IOException("Unexpected ExifTool version \"" + text + "\"");
        }
        return text;
    }

    /**
     * Downloads, checks and unpacks the latest ExifTool, replacing an earlier download.
     *
     * @param beforeReplace called before the old copy is replaced, e.g. to stop it if it's running (Windows locks
     *                      files in use)
     * @return the executable to use
     */
    public Path installExifTool(Progress progress, Runnable beforeReplace) throws IOException {
        String version = latestExifToolVersion();
        String archive = windows ? "exiftool-" + version + "_64.zip" : "Image-ExifTool-" + version + ".tar.gz";
        String checksums = text(URI.create(exifToolSite + "checksums.txt"));
        String expected = checksum(checksums, archive);
        if (null == expected) {
            throw new IOException(tr("exiftool.org doesn't list a checksum for {0}, so it wasn't installed", archive));
        }
        Files.createDirectories(toolsDir);
        Path download = Files.createTempFile(toolsDir, "exiftool-", ".download");
        Path unpacked = toolsDir.resolve("exiftool.new");
        try {
            download(URI.create(exifToolSite + archive), download, expected, "ExifTool " + version, progress);
            deleteTree(unpacked);
            if (windows) {
                Archives.unzip(download, unpacked, 1, name -> true, progress::isCancelled);
                // The package's exe asks to press a key when run by double-click; renamed, it doesn't
                Path exe = unpacked.resolve("exiftool(-k).exe");
                if (Files.exists(exe)) {
                    Files.move(exe, unpacked.resolve("exiftool.exe"), StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Archives.untarGz(download, unpacked, 1, progress::isCancelled);
                unpacked.resolve("exiftool").toFile().setExecutable(true);
            }
            Path target = toolsDir.resolve("exiftool");
            Path executable = target.resolve(windows ? "exiftool.exe" : "exiftool");
            if (!Files.exists(unpacked.resolve(executable.getFileName()))) {
                throw new IOException(tr("The ExifTool download doesn't contain {0}", executable.getFileName()));
            }
            beforeReplace.run();
            replace(unpacked, target);
            return executable;
        } finally {
            Files.deleteIfExists(download);
            deleteTree(unpacked);
        }
    }

    /** Downloads, checks and unpacks FFmpeg (Windows only), replacing an earlier download; returns ffmpeg.exe. */
    public Path installFfmpeg(Progress progress) throws IOException {
        if (!windows) {
            throw new IOException(tr("FFmpeg is only downloaded on Windows; use your package manager"));
        }
        String sum = text(URI.create(ffmpegZip + ".sha256")).strip();
        Matcher m = Pattern.compile("^([0-9a-fA-F]{64})\\b").matcher(sum);
        if (!m.find()) {
            throw new IOException(tr("No checksum for the FFmpeg download, so it wasn't installed"));
        }
        Files.createDirectories(toolsDir);
        Path download = Files.createTempFile(toolsDir, "ffmpeg-", ".download");
        Path unpacked = toolsDir.resolve("ffmpeg.new");
        try {
            download(URI.create(ffmpegZip), download, m.group(1).toLowerCase(Locale.ROOT), "FFmpeg", progress);
            deleteTree(unpacked);
            // Only the program itself: ffmpeg-7.1-essentials_build/bin/ffmpeg.exe
            Archives.unzip(download, unpacked, 1, name -> name.equals("bin/ffmpeg.exe"), progress::isCancelled);
            Path exe = unpacked.resolve("bin").resolve("ffmpeg.exe");
            if (!Files.exists(exe)) {
                throw new IOException(tr("The FFmpeg download doesn't contain bin/ffmpeg.exe"));
            }
            Path target = toolsDir.resolve("ffmpeg");
            replace(unpacked.resolve("bin"), target);
            return target.resolve("ffmpeg.exe");
        } finally {
            Files.deleteIfExists(download);
            deleteTree(unpacked);
        }
    }

    /** The SHA-256 that a "SHA256(name)= hex" line of exiftool.org's checksums.txt gives for {@code name}. */
    static String checksum(String checksums, String name) {
        Matcher m = Pattern.compile("(?m)^SHA2?-?256\\(" + Pattern.quote(name) + "\\)\\s*=\\s*([0-9a-fA-F]{64})\\s*$")
                .matcher(checksums);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private void download(URI uri, Path target, String sha256, String what, Progress progress) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        Response response = http.get(uri);
        try (InputStream in = new DigestInputStream(new BufferedInputStream(response.body()), digest);
             OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long done = 0;
            int n;
            progress.update(what, 0, response.length());
            while ((n = in.read(buffer)) >= 0) {
                if (progress.isCancelled()) {
                    throw new IOException(tr("Cancelled"));
                }
                out.write(buffer, 0, n);
                done += n;
                progress.update(what, done, response.length());
            }
        }
        String actual = HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(sha256)) {
            throw new IOException(tr("The {0} download is damaged or not the published file (checksum mismatch), so it wasn't installed", what));
        }
    }

    private String text(URI uri) throws IOException {
        Response response = http.get(uri);
        try (InputStream in = response.body()) {
            return new String(in.readNBytes(1 << 20), StandardCharsets.UTF_8);
        }
    }

    /** Puts {@code from} in the place of {@code to}, keeping the old one until the new one is there. */
    private static void replace(Path from, Path to) throws IOException {
        Path old = to.resolveSibling(to.getFileName() + ".old");
        deleteTree(old);
        if (Files.exists(to)) {
            Files.move(to, old);
        }
        try {
            Files.move(from, to);
        } catch (IOException e) {
            if (Files.exists(old) && !Files.exists(to)) {
                Files.move(old, to);
            }
            throw e;
        }
        deleteTree(old);
    }

    static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static Http httpClient(String userAgent) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        return uri -> {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
                    .header("User-Agent", userAgent).GET().build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    response.body().close();
                    throw new IOException(uri.getHost() + " answered HTTP " + response.statusCode());
                }
                return new Response(response.body(), response.headers().firstValueAsLong("Content-Length")
                        .orElse(-1));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            } catch (IOException e) {
                if (null != e.getMessage() && e.getMessage().contains(" answered HTTP ")) {
                    throw e;
                }
                throw new IOException(tr("Couldn't reach {0} (offline, or blocked by a firewall or proxy). Please try again later, or install it yourself (see the download page).", uri.getHost()), e);
            }
        };
    }

    /** Unpacking zip and tar.gz archives, never writing outside the target folder. */
    static final class Archives {
        private Archives() {
        }

        /**
         * @param strip how many leading folders of each entry to drop (e.g. 1 for "exiftool-13.10_64/...")
         * @param keep  which entries (after stripping) to unpack
         */
        static void unzip(Path zip, Path target, int strip, java.util.function.Predicate<String> keep,
                          BooleanSupplier cancelled) throws IOException {
            Files.createDirectories(target);
            try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
                for (ZipEntry e = in.getNextEntry(); null != e; e = in.getNextEntry()) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException(tr("Cancelled"));
                    }
                    String name = strip(e.getName(), strip);
                    if (null == name || !keep.test(name)) {
                        continue;
                    }
                    Path out = safe(target, name);
                    if (e.isDirectory()) {
                        Files.createDirectories(out);
                    } else {
                        Files.createDirectories(out.getParent());
                        Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }

        /** A plain ustar reader: files, folders, long GNU and pax names; links and devices are skipped. */
        static void untarGz(Path tgz, Path target, int strip, BooleanSupplier cancelled) throws IOException {
            Files.createDirectories(target);
            try (InputStream in = new BufferedInputStream(new GZIPInputStream(Files.newInputStream(tgz)))) {
                byte[] header = new byte[512];
                String longName = null;
                while (true) {
                    if (cancelled.getAsBoolean()) {
                        throw new IOException(tr("Cancelled"));
                    }
                    if (in.readNBytes(header, 0, 512) < 512 || isZero(header)) {
                        return;
                    }
                    String name = field(header, 0, 100);
                    String prefix = field(header, 345, 155);
                    if (!prefix.isEmpty() && header[257] == 'u') { // "ustar"
                        name = prefix + "/" + name;
                    }
                    long size = Long.parseLong(field(header, 124, 12).strip().isEmpty() ? "0"
                            : field(header, 124, 12).strip(), 8);
                    char type = (char) header[156];
                    long padded = (size + 511) / 512 * 512;
                    if (type == 'L' || type == 'x') {
                        byte[] data = in.readNBytes((int) size);
                        in.skipNBytes(padded - size);
                        String text = new String(data, StandardCharsets.UTF_8);
                        if (type == 'L') {
                            longName = text.replace("\0", "");
                        } else {
                            Matcher m = Pattern.compile("\\d+ path=([^\n]*)\n").matcher(text);
                            if (m.find()) {
                                longName = m.group(1);
                            }
                        }
                        continue;
                    }
                    if (null != longName) {
                        name = longName;
                        longName = null;
                    }
                    String stripped = strip(name, strip);
                    if (null != stripped && (type == '0' || type == '\0' || type == '5')) {
                        Path out = safe(target, stripped);
                        if (type == '5') {
                            Files.createDirectories(out);
                        } else {
                            Files.createDirectories(out.getParent());
                            try (OutputStream os = Files.newOutputStream(out)) {
                                copy(in, os, size);
                            }
                            in.skipNBytes(padded - size);
                            if ((Integer.parseInt(field(header, 100, 8).strip().isEmpty() ? "0"
                                    : field(header, 100, 8).strip(), 8) & 0100) != 0) {
                                out.toFile().setExecutable(true);
                            }
                            continue;
                        }
                    }
                    in.skipNBytes(type == '5' ? 0 : padded);
                }
            }
        }

        private static void copy(InputStream in, OutputStream out, long size) throws IOException {
            byte[] buffer = new byte[64 * 1024];
            long left = size;
            while (left > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (n < 0) {
                    throw new IOException(tr("The archive is cut off"));
                }
                out.write(buffer, 0, n);
                left -= n;
            }
        }

        private static String field(byte[] header, int offset, int length) {
            int end = offset;
            while (end < offset + length && header[end] != 0) {
                end++;
            }
            return new String(header, offset, end - offset, StandardCharsets.UTF_8);
        }

        private static boolean isZero(byte[] block) {
            for (byte b : block) {
                if (b != 0) {
                    return false;
                }
            }
            return true;
        }

        /** The entry name without its first {@code strip} folders, or {@code null} if nothing is left. */
        static String strip(String name, int strip) {
            String n = name.replace('\\', '/');
            while (n.startsWith("./")) {
                n = n.substring(2);
            }
            for (int i = 0; i < strip; i++) {
                int slash = n.indexOf('/');
                if (slash < 0) {
                    return null;
                }
                n = n.substring(slash + 1);
            }
            n = n.endsWith("/") ? n.substring(0, n.length() - 1) : n;
            return n.isEmpty() ? null : n;
        }

        /** {@code target/name}, refusing names that would end up outside {@code target} ("../", absolute). */
        static Path safe(Path target, String name) throws IOException {
            Path root = target.toAbsolutePath().normalize();
            Path out = root.resolve(name).normalize();
            if (!out.startsWith(root) || out.equals(root)) {
                throw new IOException(tr("The archive has a bad entry: {0}", name));
            }
            return out;
        }
    }
}
