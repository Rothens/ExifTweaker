package me.rothens.gpsexif.map;

import me.rothens.gpsexif.util.FileUtil;
import org.jxmapviewer.cache.FileBasedLocalCache;
import org.jxmapviewer.cache.LocalCache;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

/**
 * Disk cache for map tiles, shared by all map layers.
 * <ul>
 *     <li>Tiles older than the maximum age are downloaded again.</li>
 *     <li>When the cache grows beyond its size limit, the oldest tiles are deleted until it's at 90% of the limit.</li>
 * </ul>
 * A tile file's modification time is its download time. The tile loader calls {@link #put} even for tiles it just
 * read from here, so fresh tiles are not rewritten - otherwise every read would reset their age.
 * <p>
 * Uses the same directory layout as JXMapViewer's {@link FileBasedLocalCache}, so tiles cached by older versions
 * are reused.
 */
public class TileDiskCache implements LocalCache {

    public static final Duration DEFAULT_MAX_AGE = Duration.ofDays(30);
    public static final long DEFAULT_MAX_BYTES = 500L * 1024 * 1024;

    private final Path dir;
    private final Duration maxAge;
    private final LongSupplier maxBytes;
    private final Clock clock;
    private final FileBasedLocalCache layout;
    private final AtomicLong size = new AtomicLong(-1);
    private final AtomicBoolean maintenanceScheduled = new AtomicBoolean();
    private final ExecutorService maintenance = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tile-cache-maintenance");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    /**
     * @param maxBytes read on every check, so a changed setting applies immediately
     */
    public TileDiskCache(Path dir, Duration maxAge, LongSupplier maxBytes, Clock clock) {
        this.dir = dir;
        this.maxAge = maxAge;
        this.maxBytes = maxBytes;
        this.clock = clock;
        this.layout = new FileBasedLocalCache(dir.toFile(), false);
    }

    public TileDiskCache(Path dir, LongSupplier maxBytes) {
        this(dir, DEFAULT_MAX_AGE, maxBytes, Clock.systemUTC());
    }

    public Path getDirectory() {
        return dir;
    }

    @Override
    public InputStream get(URL url) throws IOException {
        Path file = fileFor(url);
        if (!isFresh(file)) {
            return null;
        }
        try {
            return Files.newInputStream(file);
        } catch (java.nio.file.NoSuchFileException e) {
            return null; // deleted by eviction or "Clear" in the meantime
        }
    }

    @Override
    public void put(URL url, InputStream data) throws IOException {
        Path file = fileFor(url);
        if (isFresh(file)) {
            return; // it was served from this cache
        }
        long oldSize = Files.exists(file) ? Files.size(file) : 0;
        Files.createDirectories(file.getParent());
        // Write next to the target and move it into place, so a crash never leaves a half-written tile behind
        Path tmp = Files.createTempFile(file.getParent(), ".tile-", ".tmp");
        try {
            Files.copy(data, tmp, StandardCopyOption.REPLACE_EXISTING);
            long newSize = Files.size(tmp);
            FileUtil.replace(tmp, file);
            size.getAndUpdate(s -> s < 0 ? s : s - oldSize + newSize);
        } finally {
            Files.deleteIfExists(tmp);
        }
        scheduleMaintenance();
    }

    /** Current size in bytes, or -1 while it hasn't been measured yet. */
    public long getSize() {
        return size.get();
    }

    /** Measures the cache and enforces the size limit in the background. */
    public void scheduleMaintenance() {
        if (maintenanceScheduled.compareAndSet(false, true)) {
            maintenance.execute(() -> {
                maintenanceScheduled.set(false);
                try {
                    enforceLimit();
                } catch (IOException | UncheckedIOException e) {
                    System.err.println("Tile cache maintenance failed: " + e.getMessage());
                }
            });
        }
    }

    /** Deletes all cached tiles. */
    public synchronized void clear() throws IOException {
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.equals(dir)) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
        size.set(0);
    }

    /** Measures the cache and, if it's over the limit, deletes the oldest tiles until it's at 90% of the limit. */
    synchronized void enforceLimit() throws IOException {
        List<Tile> tiles = scan();
        long total = tiles.stream().mapToLong(Tile::size).sum();
        long limit = maxBytes.getAsLong();
        if (total > limit) {
            long target = limit / 10 * 9;
            tiles.sort(Comparator.comparingLong(Tile::modified));
            for (Tile tile : tiles) {
                if (total <= target) {
                    break;
                }
                if (Files.deleteIfExists(tile.path())) {
                    total -= tile.size();
                }
            }
        }
        size.set(total);
    }

    private record Tile(Path path, long size, long modified) {
    }

    private List<Tile> scan() throws IOException {
        List<Tile> tiles = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return tiles;
        }
        try (Stream<Path> files = Files.walk(dir)) {
            files.forEach(p -> {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                    if (attrs.isRegularFile()) {
                        tiles.add(new Tile(p, attrs.size(), attrs.lastModifiedTime().toMillis()));
                    }
                } catch (IOException ignored) {
                    // Deleted while scanning
                }
            });
        }
        return tiles;
    }

    private boolean isFresh(Path file) {
        try {
            long modified = Files.getLastModifiedTime(file).toMillis();
            return clock.millis() - modified < maxAge.toMillis();
        } catch (IOException e) {
            return false; // missing
        }
    }

    Path fileFor(URL url) {
        File file = layout.getLocalFile(url);
        return file.toPath();
    }
}
