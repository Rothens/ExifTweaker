package me.rothens.gpsexif.util;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

/**
 * Guarantees that the JVM terminates once the app decided to exit. If exiting takes longer than the timeout
 * (e.g. a shutdown hook or native toolkit code is stuck), a thread dump is written to a file for diagnosis and
 * the JVM is halted without waiting any further.
 */
public final class ExitWatchdog {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final ExitWatchdog DEFAULT = new ExitWatchdog(DEFAULT_TIMEOUT,
            Path.of(System.getProperty("java.io.tmpdir"), "exiftweaker-exit-hang.txt"),
            status -> Runtime.getRuntime().halt(status));

    private final Duration timeout;
    private final Path dumpFile;
    private final IntConsumer halt;
    private final AtomicBoolean armed = new AtomicBoolean();

    ExitWatchdog(Duration timeout, Path dumpFile, IntConsumer halt) {
        this.timeout = timeout;
        this.dumpFile = dumpFile;
        this.halt = halt;
    }

    /** Arms the application-wide watchdog. Safe to call several times; only the first call counts. */
    public static void arm() {
        DEFAULT.start();
    }

    void start() {
        if (!armed.compareAndSet(false, true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(timeout.toMillis());
            } catch (InterruptedException e) {
                return;
            }
            String dump = threadDump();
            System.err.println("ExifTweaker didn't exit within " + timeout.toSeconds() + " s, forcing it. Thread dump: "
                    + dumpFile);
            try {
                Files.writeString(dumpFile, dump, StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.err.println(dump);
            }
            halt.accept(1);
        }, "exit-watchdog");
        // Daemon threads keep running while shutdown hooks run, so this still fires if a hook hangs
        t.setDaemon(true);
        t.start();
    }

    /** All threads with full stack traces and lock information, plus any detected deadlock. */
    static String threadDump() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        StringBuilder sb = new StringBuilder();
        sb.append(System.getProperty("java.vendor")).append(' ').append(System.getProperty("java.version"))
                .append(" on ").append(System.getProperty("os.name")).append(' ')
                .append(System.getProperty("os.version")).append("\n\n");
        long[] deadlocked = mx.findDeadlockedThreads();
        if (null != deadlocked) {
            sb.append("DEADLOCK between ").append(deadlocked.length).append(" threads\n\n");
        }
        for (ThreadInfo info : mx.dumpAllThreads(true, true)) {
            sb.append('"').append(info.getThreadName()).append("\" ").append(info.isDaemon() ? "daemon " : "")
                    .append(info.getThreadState());
            if (null != info.getLockName()) {
                sb.append(" on ").append(info.getLockName());
            }
            if (null != info.getLockOwnerName()) {
                sb.append(" owned by \"").append(info.getLockOwnerName()).append('"');
            }
            sb.append('\n');
            for (StackTraceElement element : info.getStackTrace()) {
                sb.append("    at ").append(element).append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }
}
