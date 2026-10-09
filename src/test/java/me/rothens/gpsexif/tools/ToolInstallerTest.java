package me.rothens.gpsexif.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ToolInstallerTest {

    @TempDir
    Path dir;

    private final Map<String, byte[]> site = new HashMap<>();
    private final List<String> requested = new ArrayList<>();

    private ToolInstaller installer(boolean windows) {
        return new ToolInstaller(uri -> {
            requested.add(uri.toString());
            byte[] body = site.get(uri.toString());
            if (null == body) {
                throw new IOException(uri.getHost() + " answered HTTP 404");
            }
            return new ToolInstaller.Response(new ByteArrayInputStream(body), body.length);
        }, dir.resolve("tools"), "https://exiftool.test/", "https://ffmpeg.test/essentials.zip", windows);
    }

    static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toByteArray();
    }

    /** A minimal ustar archive, gzipped. */
    static byte[] tarGz(Map<String, String> files, String longName) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        for (Map.Entry<String, String> f : files.entrySet()) {
            entry(tar, f.getKey(), f.getValue().getBytes(StandardCharsets.UTF_8), f.getKey().endsWith("exiftool"));
        }
        if (null != longName) {
            byte[] name = (longName + "\0").getBytes(StandardCharsets.UTF_8);
            header(tar, "././@LongLink", name.length, 'L', false);
            tar.writeBytes(name);
            tar.writeBytes(new byte[(512 - name.length % 512) % 512]);
            entry(tar, "ignored", "deep".getBytes(StandardCharsets.UTF_8), false);
        }
        tar.writeBytes(new byte[1024]);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void entry(ByteArrayOutputStream tar, String name, byte[] data, boolean executable) {
        header(tar, name, data.length, '0', executable);
        tar.writeBytes(data);
        tar.writeBytes(new byte[(512 - data.length % 512) % 512]);
    }

    private static void header(ByteArrayOutputStream tar, String name, int size, char type, boolean executable) {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(n, 0, h, 0, Math.min(100, n.length));
        System.arraycopy(String.format("%07o\0", executable ? 0755 : 0644).getBytes(StandardCharsets.US_ASCII), 0, h, 100, 8);
        System.arraycopy(String.format("%011o\0", size).getBytes(StandardCharsets.US_ASCII), 0, h, 124, 12);
        h[156] = (byte) type;
        System.arraycopy("ustar\0".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
        tar.writeBytes(h);
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @Test
    void exifToolOnWindows() throws Exception {
        byte[] zip = zip(Map.of("exiftool-13.10_64/exiftool(-k).exe", "MZ exe",
                "exiftool-13.10_64/exiftool_files/perl.dll", "dll", "exiftool-13.10_64/exiftool_files/lib/x.pm", "pm"));
        site.put("https://exiftool.test/ver.txt", "13.10\n".getBytes());
        site.put("https://exiftool.test/checksums.txt", ("SHA1(exiftool-13.10_64.zip)= abc\nSHA256(exiftool-13.10_64.zip)= "
                + sha256(zip) + "\nSHA256(Image-ExifTool-13.10.tar.gz)= " + "0".repeat(64) + "\n").getBytes());
        site.put("https://exiftool.test/exiftool-13.10_64.zip", zip);
        List<Long> progress = new ArrayList<>();
        boolean[] stopped = {false};
        Path exe = installer(true).installExifTool((what, done, total) -> progress.add(done), () -> stopped[0] = true);
        assertEquals(dir.resolve("tools/exiftool/exiftool.exe"), exe);
        assertEquals("MZ exe", Files.readString(exe));
        assertTrue(Files.exists(dir.resolve("tools/exiftool/exiftool_files/lib/x.pm")), "the files folder stays next to it");
        assertTrue(stopped[0], "the running ExifTool is stopped before it's replaced");
        assertEquals((long) zip.length, progress.get(progress.size() - 1));
        assertTrue(installer(true).isDownloaded(exe.toString()));
        assertFalse(installer(true).isDownloaded("/usr/bin/exiftool"));
        try (var files = Files.list(dir.resolve("tools"))) {
            assertEquals(List.of("exiftool"), files.map(p -> p.getFileName().toString()).toList(), "nothing left over");
        }

        // An update replaces it
        byte[] newer = zip(Map.of("exiftool-13.11_64/exiftool(-k).exe", "MZ newer"));
        site.put("https://exiftool.test/ver.txt", "13.11".getBytes());
        site.put("https://exiftool.test/checksums.txt", ("SHA256(exiftool-13.11_64.zip)= " + sha256(newer)).getBytes());
        site.put("https://exiftool.test/exiftool-13.11_64.zip", newer);
        installer(true).installExifTool((w, d, t) -> { }, () -> { });
        assertEquals("MZ newer", Files.readString(exe));
        assertFalse(Files.exists(dir.resolve("tools/exiftool/exiftool_files")), "the old version is gone");
    }

    @Test
    void exifToolElsewhereIsThePerlDistribution() throws Exception {
        byte[] tgz = tarGz(Map.of("Image-ExifTool-13.10/exiftool", "#!/usr/bin/perl\n",
                "Image-ExifTool-13.10/lib/Image/ExifTool.pm", "package"), "Image-ExifTool-13.10/lib/" + "x".repeat(120) + ".pm");
        site.put("https://exiftool.test/ver.txt", "13.10".getBytes());
        site.put("https://exiftool.test/checksums.txt", ("SHA256(Image-ExifTool-13.10.tar.gz)= " + sha256(tgz)).getBytes());
        site.put("https://exiftool.test/Image-ExifTool-13.10.tar.gz", tgz);
        Path exe = installer(false).installExifTool((w, d, t) -> { }, () -> { });
        assertEquals(dir.resolve("tools/exiftool/exiftool"), exe);
        assertTrue(Files.isExecutable(exe));
        assertEquals("package", Files.readString(dir.resolve("tools/exiftool/lib/Image/ExifTool.pm")));
        assertEquals("deep", Files.readString(dir.resolve("tools/exiftool/lib/" + "x".repeat(120) + ".pm")), "GNU long name");
    }

    @Test
    void ffmpegOnWindowsOnly() throws Exception {
        byte[] zip = zip(Map.of("ffmpeg-7.1-essentials_build/bin/ffmpeg.exe", "MZ ffmpeg",
                "ffmpeg-7.1-essentials_build/bin/ffplay.exe", "MZ ffplay", "ffmpeg-7.1-essentials_build/README.txt", "x"));
        site.put("https://ffmpeg.test/essentials.zip", zip);
        site.put("https://ffmpeg.test/essentials.zip.sha256", (sha256(zip) + "  ffmpeg-release-essentials.zip").getBytes());
        Path exe = installer(true).installFfmpeg((w, d, t) -> { });
        assertEquals(dir.resolve("tools/ffmpeg/ffmpeg.exe"), exe);
        assertEquals("MZ ffmpeg", Files.readString(exe));
        assertFalse(Files.exists(dir.resolve("tools/ffmpeg/ffplay.exe")), "only ffmpeg itself");
        assertFalse(installer(false).canInstallFfmpeg());
        assertThrows(IOException.class, () -> installer(false).installFfmpeg((w, d, t) -> { }));
    }

    @Test
    void refusesWhatDoesntMatchItsChecksum() throws Exception {
        byte[] zip = zip(Map.of("exiftool-13.10_64/exiftool(-k).exe", "MZ exe"));
        site.put("https://exiftool.test/ver.txt", "13.10".getBytes());
        site.put("https://exiftool.test/checksums.txt", ("SHA256(exiftool-13.10_64.zip)= " + "ab".repeat(32)).getBytes());
        site.put("https://exiftool.test/exiftool-13.10_64.zip", zip);
        IOException e = assertThrows(IOException.class, () -> installer(true).installExifTool((w, d, t) -> { }, () -> { }));
        assertTrue(e.getMessage().contains("checksum"), e.getMessage());
        assertFalse(Files.exists(dir.resolve("tools/exiftool")));

        site.put("https://exiftool.test/checksums.txt", "nothing for it".getBytes());
        assertThrows(IOException.class, () -> installer(true).installExifTool((w, d, t) -> { }, () -> { }));
        site.put("https://exiftool.test/ver.txt", "<html>".getBytes());
        assertThrows(IOException.class, () -> installer(true).latestExifToolVersion());
    }

    @Test
    void cancelling() throws Exception {
        byte[] zip = zip(Map.of("exiftool-13.10_64/exiftool(-k).exe", "MZ".repeat(100_000)));
        site.put("https://exiftool.test/ver.txt", "13.10".getBytes());
        site.put("https://exiftool.test/checksums.txt", ("SHA256(exiftool-13.10_64.zip)= " + sha256(zip)).getBytes());
        site.put("https://exiftool.test/exiftool-13.10_64.zip", zip);
        IOException e = assertThrows(IOException.class, () -> installer(true).installExifTool(
                new ToolInstaller.Progress() {
                    @Override
                    public void update(String what, long done, long total) {
                    }

                    @Override
                    public boolean isCancelled() {
                        return true;
                    }
                }, () -> { }));
        assertEquals("Cancelled", e.getMessage());
        assertFalse(Files.exists(dir.resolve("tools/exiftool")));
    }

    @Test
    void archivesCantWriteOutsideTheirFolder() throws Exception {
        Path evil = Files.write(dir.resolve("evil.zip"), zip(Map.of("x/../../escaped.txt", "boo")));
        assertThrows(IOException.class, () -> ToolInstaller.Archives.unzip(evil, dir.resolve("out"), 1, n -> true,
                () -> false));
        assertFalse(Files.exists(dir.resolve("escaped.txt")));
        assertEquals("b/c", ToolInstaller.Archives.strip("./a/b/c", 1));
        assertNull(ToolInstaller.Archives.strip("a/", 1));
    }

    @Test
    void checksumLines() {
        String text = "MD5(a.zip)= x\nSHA1(a.zip)= y\nSHA256(a.zip)= " + "AB".repeat(32) + "\nSHA256(b.zip)= " + "cd".repeat(32);
        assertEquals("ab".repeat(32), ToolInstaller.checksum(text, "a.zip"));
        assertEquals("cd".repeat(32), ToolInstaller.checksum(text, "b.zip"));
        assertNull(ToolInstaller.checksum(text, "c.zip"));
        assertNull(ToolInstaller.checksum(text, "a.zi"), "no partial names");
    }
}
