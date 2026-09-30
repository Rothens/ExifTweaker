package me.rothens.gpsexif.map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TileDiskCacheTest {

    @TempDir
    Path dir;

    private final Instant now = Instant.parse("2026-09-30T12:00:00Z");
    private final AtomicLong maxBytes = new AtomicLong(1_000_000);
    private TileDiskCache cache;

    @BeforeEach
    void setUp() {
        cache = new TileDiskCache(dir, Duration.ofDays(30), maxBytes::get, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static URL tile(int x) throws IOException {
        return new URL("https://tile.openstreetmap.org/13/" + x + "/2887.png");
    }

    private void put(URL url, int bytes) throws IOException {
        cache.put(url, new ByteArrayInputStream(new byte[bytes]));
    }

    private void age(URL url, Duration age) throws IOException {
        Files.setLastModifiedTime(cache.fileFor(url), FileTime.from(now.minus(age)));
    }

    @Test
    void storesAndReturnsTiles() throws IOException {
        assertNull(cache.get(tile(1)));
        cache.put(tile(1), new ByteArrayInputStream(new byte[]{1, 2, 3}));
        try (InputStream in = cache.get(tile(1))) {
            assertArrayEquals(new byte[]{1, 2, 3}, in.readAllBytes());
        }
        assertEquals(dir.resolve("tile.openstreetmap.org/13/1/2887.png"), cache.fileFor(tile(1)),
                "same layout as JXMapViewer's FileBasedLocalCache, so existing tiles are reused");
    }

    @Test
    void expiredTilesAreDownloadedAgain() throws IOException {
        put(tile(1), 10);
        age(tile(1), Duration.ofDays(31));
        assertNull(cache.get(tile(1)));

        cache.put(tile(1), new ByteArrayInputStream(new byte[]{9}));
        try (InputStream in = cache.get(tile(1))) {
            assertArrayEquals(new byte[]{9}, in.readAllBytes());
        }
    }

    @Test
    void freshTilesAreNotRewrittenWhenPutAgain() throws IOException {
        put(tile(1), 10);
        age(tile(1), Duration.ofDays(5));
        FileTime before = Files.getLastModifiedTime(cache.fileFor(tile(1)));

        put(tile(1), 10); // the tile loader does this after reading from the cache

        assertEquals(before, Files.getLastModifiedTime(cache.fileFor(tile(1))), "age must not be reset");
    }

    @Test
    void oldestTilesAreEvictedDownTo90PercentOfLimit() throws IOException {
        for (int i = 0; i < 10; i++) {
            put(tile(i), 100);
            age(tile(i), Duration.ofDays(10 - i)); // tile 0 is the oldest
        }
        maxBytes.set(750);

        cache.enforceLimit();

        assertEquals(600, cache.getSize()); // 90% of 750 = 675 -> six 100-byte tiles fit
        for (int i = 0; i < 4; i++) {
            assertFalse(Files.exists(cache.fileFor(tile(i))), "tile " + i + " should be evicted");
        }
        for (int i = 4; i < 10; i++) {
            assertTrue(Files.exists(cache.fileFor(tile(i))), "tile " + i + " should be kept");
        }
    }

    @Test
    void clearDeletesEverything() throws IOException {
        put(tile(1), 10);
        put(tile(2), 10);
        cache.clear();
        assertEquals(0, cache.getSize());
        assertNull(cache.get(tile(1)));
        try (var files = Files.list(dir)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void sizeIsUnknownUntilMeasured() throws IOException {
        assertEquals(-1, cache.getSize());
        put(tile(1), 10);
        cache.enforceLimit();
        assertEquals(10, cache.getSize());
        put(tile(2), 5);
        assertEquals(15, cache.getSize());
    }
}
