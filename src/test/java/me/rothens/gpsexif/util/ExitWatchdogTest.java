package me.rothens.gpsexif.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ExitWatchdogTest {

    @TempDir
    Path dir;

    @Test
    void writesThreadDumpAndHaltsAfterTimeout() throws Exception {
        Path dump = dir.resolve("hang.txt");
        CountDownLatch halted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        ExitWatchdog watchdog = new ExitWatchdog(Duration.ofMillis(50), dump, status -> {
            calls.incrementAndGet();
            halted.countDown();
        });

        watchdog.start();
        watchdog.start(); // arming twice must not start a second timer

        assertTrue(halted.await(5, TimeUnit.SECONDS));
        Thread.sleep(200);
        assertEquals(1, calls.get());
        String text = Files.readString(dump);
        assertTrue(text.contains("\"exit-watchdog\""), text);
        assertTrue(text.contains("me.rothens.gpsexif.util.ExitWatchdog.threadDump"), text);
    }
}
