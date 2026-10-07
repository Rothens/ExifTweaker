package me.rothens.gpsexif.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds FFmpeg, which encodes videos much faster and smaller than the built-in encoder. Not bundled. */
public final class Ffmpeg {

    public static final String DOWNLOAD_URL = "https://ffmpeg.org/download.html";
    private static final Pattern VERSION = Pattern.compile("^ffmpeg version (\\S+)");

    private Ffmpeg() {
    }

    /** The FFmpeg version at {@code executable}, or {@code null} if it can't be run. */
    public static String version(String executable) {
        String out = run(executable, "-hide_banner", "-version");
        if (null == out) {
            return null;
        }
        Matcher m = VERSION.matcher(out);
        return m.find() ? m.group(1) : null;
    }

    /** Finds FFmpeg: the configured path if it works, otherwise {@code ffmpeg} on the PATH; {@code null} if none. */
    public static String locate(String configured) {
        if (null != configured && !configured.isBlank() && null != version(configured.strip())) {
            return configured.strip();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        for (String candidate : windows ? new String[]{"ffmpeg.exe", "ffmpeg"} : new String[]{"ffmpeg"}) {
            if (null != version(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** Whether this FFmpeg build has the x264 encoder (most do; it gives the best H.264 files). */
    public static boolean hasX264(String executable) {
        String out = run(executable, "-hide_banner", "-encoders");
        return null != out && out.contains("libx264");
    }

    private static String run(String executable, String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = executable;
            System.arraycopy(args, 0, command, 1, args.length);
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? new String(out, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
