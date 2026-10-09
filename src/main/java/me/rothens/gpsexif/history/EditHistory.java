package me.rothens.gpsexif.history;

import static me.rothens.gpsexif.i18n.I18n.tr;
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
    /** How much disk space all snapshots together may use; older edits are dropped beyond that. */
    public static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;

    /** {@code copy} is {@code null} if the file didn't exist yet; undoing then deletes it. */
    private record Snapshot(Path original, Path copy, long size) {
    }

    /** An edit that's undone by running code rather than copying files back, e.g. renaming photos. */
    public interface Reversal {
        /** Undoes the edit; returns the files it changed. */
        List<Path> undo() throws IOException;
    }

    private record Edit(String description, List<Snapshot> snapshots, Reversal reversal) {
        long size() {
            return snapshots.stream().mapToLong(Snapshot::size).sum();
        }
    }

    private final Deque<Edit> edits = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final int maxEdits;
    private final long maxBytes;
    private long bytes;
    private Path snapshotDir;

    public EditHistory() {
        this(DEFAULT_MAX_EDITS, DEFAULT_MAX_BYTES);
    }

    public EditHistory(int maxEdits) {
        this(maxEdits, DEFAULT_MAX_BYTES);
    }

    public EditHistory(int maxEdits, long maxBytes) {
        this.maxEdits = maxEdits;
        this.maxBytes = maxBytes;
    }

    /** Whether an edit that modifies files of this total size can be made undoable. */
    public boolean canUndoEditOfSize(long totalBytes) {
        return totalBytes <= maxBytes;
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
        return new Transaction(description, true);
    }

    /**
     * Starts an edit that can't be undone, e.g. because it's too large to snapshot. Committing it clears the whole
     * history, as undoing older edits would otherwise silently revert this one for the files they share.
     */
    public Transaction beginWithoutUndo(String description) {
        return new Transaction(description, false);
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
        if (null != edit.reversal()) {
            try {
                return edit.reversal().undo();
            } finally {
                fireChanged();
            }
        }
        List<Path> restored = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (Snapshot s : edit.snapshots()) {
            try {
                if (null == s.copy()) {
                    Files.deleteIfExists(s.original());
                    restored.add(s.original());
                    continue;
                }
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
            throw new IOException(tr("Couldn't restore {0} file(s):", failures.size()) + "\n" + String.join("\n", failures));
        }
        return restored;
    }

    /** Records an edit that {@link #undo()} reverts by running {@code reversal}. */
    public void record(String description, Reversal reversal) {
        push(new Edit(description, List.of(), reversal));
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
        bytes += edit.size();
        while (edits.size() > maxEdits || (bytes > maxBytes && edits.size() > 1)) {
            delete(edits.removeLast());
        }
        fireChanged();
    }

    private void delete(Edit edit) {
        bytes -= edit.size();
        delete(edit.snapshots());
    }

    private static void delete(Collection<Snapshot> snapshots) {
        for (Snapshot s : snapshots) {
            if (null == s.copy()) {
                continue;
            }
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
        private final boolean undoable;
        private final Map<Path, Snapshot> snapshots = new LinkedHashMap<>();
        private boolean committed;
        private boolean changedFiles;

        private Transaction(String description, boolean undoable) {
            this.description = description;
            this.undoable = undoable;
        }

        /**
         * Keeps a copy of {@code file} as it is now (or remembers that it doesn't exist). Only the first snapshot
         * of a file per transaction counts.
         */
        public void snapshot(Path file) throws IOException {
            changedFiles = true;
            Path key = file.toAbsolutePath().normalize();
            if (!undoable || snapshots.containsKey(key)) {
                return;
            }
            if (!Files.exists(key)) {
                snapshots.put(key, new Snapshot(key, null, 0));
                return;
            }
            Path copy = Files.createTempFile(snapshotDir(), "snapshot-", ".bin");
            Files.copy(key, copy, StandardCopyOption.REPLACE_EXISTING);
            snapshots.put(key, new Snapshot(key, copy, Files.size(copy)));
        }

        /** Makes the edit undoable. Transactions without snapshots are dropped. */
        public void commit() {
            if (committed) {
                return;
            }
            committed = true;
            if (!undoable) {
                if (changedFiles) {
                    clear();
                }
            } else if (!snapshots.isEmpty()) {
                push(new Edit(description, List.copyOf(snapshots.values()), null));
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
