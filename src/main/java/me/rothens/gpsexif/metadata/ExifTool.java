package me.rothens.gpsexif.metadata;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A long-running {@code exiftool -stay_open True -@ -} process. Commands are sent through stdin, one argument
 * per line; values go through ExifTool's {@code #[CSTR]} syntax, so line breaks and tabs survive. One process
 * serves all commands, which is much faster than starting ExifTool per file.
 */
public class ExifTool implements AutoCloseable {

    private static final long TIMEOUT_SECONDS = 60;

    private final String executable;
    private Process process;
    private Writer stdin;
    private BufferedReader stdout;
    private BufferedReader stderr;
    private int commandCounter;

    public ExifTool(String executable) {
        this.executable = executable;
    }

    public String getExecutable() {
        return executable;
    }

    /** The ExifTool version at {@code executable}, or {@code null} if it can't be run. Doesn't keep a process. */
    public static String version(String executable) {
        try {
            Process p = new ProcessBuilder(executable, "-ver").redirectErrorStream(true).start();
            p.getOutputStream().close();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.exitValue() == 0 && out.matches("\\d+\\.\\d+.*") ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Finds ExifTool: the configured path if it works, otherwise {@code exiftool} on the PATH. Returns the
     * executable to use, or {@code null}.
     */
    public static String locate(String configured) {
        if (null != configured && !configured.isBlank() && null != version(configured.strip())) {
            return configured.strip();
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        for (String candidate : windows ? new String[]{"exiftool.exe", "exiftool"} : new String[]{"exiftool"}) {
            if (null != version(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Runs one command and returns its standard output.
     *
     * @throws IOException if ExifTool reports an error, doesn't answer in time, or can't be started
     */
    public synchronized String execute(List<String> args) throws IOException {
        ensureStarted();
        int id = ++commandCounter;
        StringBuilder command = new StringBuilder();
        command.append("-charset\nfilename=UTF8\n-charset\nUTF8\n");
        for (String arg : args) {
            command.append(encode(arg)).append('\n');
        }
        // -echo4 prints a marker to stderr once the command is done, so all of its errors can be collected
        String errorMarker = "{done" + id + "}";
        command.append("-echo4\n").append(errorMarker).append('\n');
        command.append("-execute").append(id).append('\n');
        String marker = "{ready" + id + "}";
        CompletableFuture<String[]> result = CompletableFuture.supplyAsync(() -> {
            try {
                return new String[]{readUntil(stdout, marker, false), readUntil(stderr, errorMarker, true)};
            } catch (IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
        try {
            stdin.write(command.toString());
            stdin.flush();
            String[] outAndErrors = result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            String out = outAndErrors[0];
            String errors = outAndErrors[1];
            if (!errors.isEmpty() || out.contains("files weren't updated due to errors")) {
                throw new IOException("ExifTool: " + (errors.isEmpty() ? out.strip() : errors));
            }
            return out;
        } catch (TimeoutException e) {
            close();
            throw new IOException("ExifTool didn't answer within " + TIMEOUT_SECONDS + " s");
        } catch (ExecutionException e) {
            close();
            throw new IOException(null != e.getCause() ? e.getCause().getMessage() : e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Interrupted", e);
        }
    }

    /** Arguments with line breaks, tabs or backslashes use {@code #[CSTR]} escaping; others are passed as is. */
    static String encode(String arg) {
        if (arg.indexOf('\n') < 0 && arg.indexOf('\r') < 0 && arg.indexOf('\t') < 0 && arg.indexOf('\\') < 0
                && !arg.startsWith("#")) {
            return arg;
        }
        StringBuilder sb = new StringBuilder("#[CSTR]");
        for (char c : arg.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '"' -> sb.append("\\\"");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Reads lines up to {@code marker}; with {@code errorsOnly}, keeps only lines starting with "Error". */
    private static String readUntil(BufferedReader reader, String marker, boolean errorsOnly) throws IOException {
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.equals(marker)) {
                return sb.toString().strip();
            }
            if (!errorsOnly || line.startsWith("Error")) {
                sb.append(line).append('\n');
            }
        }
        throw new IllegalStateException("ExifTool exited unexpectedly");
    }

    private void ensureStarted() throws IOException {
        if (null != process && process.isAlive()) {
            return;
        }
        process = new ProcessBuilder(executable, "-config", config().toString(), "-stay_open", "True", "-@", "-")
                .start();
        stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        stdout = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        stderr = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
    }

    /**
     * ExifTweaker's ExifTool configuration: its own XMP namespace, for the district (XMP-exiftweaker:District).
     * It takes the place of a ~/.ExifTool_config, which doesn't affect ExifTweaker's writes this way.
     */
    private static synchronized Path config() throws IOException {
        if (null == config || !Files.exists(config)) {
            config = Files.createTempFile("exiftweaker-", ".config");
            config.toFile().deleteOnExit();
            Files.writeString(config, CONFIG, StandardCharsets.UTF_8);
        }
        return config;
    }

    private static Path config;

    static final String CONFIG = """
            # ExifTweaker's own XMP namespace
            %Image::ExifTool::UserDefined = (
                'Image::ExifTool::XMP::Main' => {
                    exiftweaker => {
                        SubDirectory => { TagTable => 'Image::ExifTool::UserDefined::exiftweaker' },
                    },
                },
            );
            %Image::ExifTool::UserDefined::exiftweaker = (
                GROUPS => { 0 => 'XMP', 1 => 'XMP-exiftweaker', 2 => 'Location' },
                NAMESPACE => { 'exiftweaker' => 'NAMESPACE_URI' },
                WRITABLE => 'string',
                District => { },
            );
            1;
            """.replace("NAMESPACE_URI", XmpPlace.EXIFTWEAKER);

    /** Stops the process; the next command starts a new one. */
    @Override
    public synchronized void close() {
        if (null == process) {
            return;
        }
        try {
            stdin.write("-stay_open\nFalse\n");
            stdin.flush();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException e) {
            process.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        process = null;
    }

    /** Whether {@code path} is an existing file that looks like a program. Used to validate the setting. */
    public static boolean looksRunnable(Path path) {
        return Files.isRegularFile(path) && (Files.isExecutable(path)
                || path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe"));
    }
}
