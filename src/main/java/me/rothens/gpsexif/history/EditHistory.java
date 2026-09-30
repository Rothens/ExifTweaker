package me.rothens.gpsexif.history;

import me.rothens.gpsexif.util.FileUtil;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * In-session undo for file writes. Before a file is modified, a copy of it is kept in a temporary snapshot
 * directory; undoing an edit copies the snapshots back. All files written by one {@link Transaction} (e.g. a
 * batch operation) are undone together.
 */
public class EditHistory implements AutoCloseable {

    /** How many edits can be undone; older snapshots are deleted to bound disk usage. */
    public static final int DEFAULT_MAX_EDITS = 20;

    private record Snapshot(Path original, Path copy) {
    }

    private record Edit(String description, List<Snapshot> snapshots) {
    }

    private final Deque<Edit> edits = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final int maxEdits;
    private Path snapshotDir;

    public EditHistory() {
        this(DEFAULT_MAX_EDITS);
    }

    public EditHistory(int maxEdits) {
        this.maxEdits = maxEdits;
    }

    /**
     * Registers a listener that's called whenever {@link #canUndo()} or the undo description may have changed.
     * Listeners run on the thread that changed the history, which isn't necessarily the Swing event thread.
     */
    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    public synchronized boolean canUndo() {
        return !edits.isEmpty();
    }

    /** Description of the edit {@link #undo()} would revert, or {@code null}. */
    public synchronized String getUndoDescription() {
        return edits.isEmpty() ? null : edits.peek().description();
    }

    /** Starts recording an edit. Call {@link Transaction#snapshot(Path)} before modifying each file. */
    public Transaction begin(String description) {
        return new Transaction(description);
    }

    /**
     * Restores all files of the most recent edit.
     *
     * @return the restored files
     * @throws IOException if some files couldn't be restored (the others are restored regardless)
     */
    public List<Path> undo() throws IOException {
        Edit edit;
        synchronized (this) {
            edit = edits.poll();
        }
        if (null == edit) {
            return List.of();
        }
        List<Path> restored = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Snapshot s : edit.snapshots()) {
            try {
                Path tmp = FileUtil.createSiblingTempFile(s.original());
                try {
                    Files.copy(s.copy(), tmp, StandardCopyOption.REPLACE_EXISTING);
                    FileUtil.replace(tmp, s.original());
                } finally {
                    Files.deleteIfExists(tmp);
                }
                restored.add(s.original());
            } catch (IOException e) {
                failures.add(s.original().getFileName() + ": " + e.getMessage());
            }
        }
        delete(edit);
        fireChanged();
        if (!failures.isEmpty()) {
            throw new IOException("Couldn't restore " + failures.size() + " file(s):\n" + String.join("\n", failures));
        }
        return restored;
    }

    /** Forgets all edits and deletes their snapshots. */
    public synchronized void clear() {
        deleteAllEdits();
        fireChanged();
    }

    /**
     * Deletes all snapshots. Doesn't notify listeners, so it's safe to call while shutting down (e.g. from a
     * shutdown hook, where touching Swing components can deadlock).
     */
    @Override
    public synchronized void close() {
        deleteAllEdits();
        if (null != snapshotDir) {
            try (Stream<Path> files = Files.walk(snapshotDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException | UncheckedIOException ignored) {
                // Temp directory - the OS cleans it up eventually
            }
            snapshotDir = null;
        }
    }

    private void deleteAllEdits() {
        while (!edits.isEmpty()) {
            delete(edits.poll());
        }
    }

    private synchronized Path snapshotDir() throws IOException {
        if (null == snapshotDir) {
            snapshotDir = Files.createTempDirectory("exiftweaker-undo-");
        }
        return snapshotDir;
    }

    private synchronized void push(Edit edit) {
        edits.push(edit);
        while (edits.size() > maxEdits) {
            delete(edits.removeLast());
        }
        fireChanged();
    }

    private static void delete(Edit edit) {
        delete(edit.snapshots());
    }

    private static void delete(Collection<Snapshot> snapshots) {
        for (Snapshot s : snapshots) {
            try {
                Files.deleteIfExists(s.copy());
            } catch (IOException ignored) {
                // Removed with the snapshot directory on close
            }
        }
    }

    private void fireChanged() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }

    /** One undoable edit in progress. Closing it without {@link #commit()} discards its snapshots. */
    public final class Transaction implements AutoCloseable {
        private final String description;
        private final Map<Path, Snapshot> snapshots = new LinkedHashMap<>();
        private boolean committed;

        private Transaction(String description) {
            this.description = description;
        }

        /** Keeps a copy of {@code file} as it is now. Only the first snapshot of a file per transaction counts. */
        public void snapshot(Path file) throws IOException {
            Path key = file.toAbsolutePath().normalize();
            if (snapshots.containsKey(key)) {
                return;
            }
            Path copy = Files.createTempFile(snapshotDir(), "snapshot-", ".bin");
            Files.copy(key, copy, StandardCopyOption.REPLACE_EXISTING);
            snapshots.put(key, new Snapshot(key, copy));
        }

        /** Makes the edit undoable. Transactions without snapshots are dropped. */
        public void commit() {
            if (committed) {
                return;
            }
            committed = true;
            if (!snapshots.isEmpty()) {
                push(new Edit(description, List.copyOf(snapshots.values())));
            }
        }

        @Override
        public void close() {
            if (!committed) {
                delete(snapshots.values());
                snapshots.clear();
                committed = true;
            }
        }
    }
}
