package me.rothens.gpsexif.history;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class EditHistoryTest {

    @TempDir
    Path dir;

    private final EditHistory history = new EditHistory(3);

    @AfterEach
    void tearDown() {
        history.close();
    }

    private Path file(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

    private void edit(String description, Path file, String newContent) throws IOException {
        try (EditHistory.Transaction tx = history.begin(description)) {
            tx.snapshot(file);
            Files.writeString(file, newContent);
            tx.commit();
        }
    }

    @Test
    void undoRestoresPreviousContent() throws IOException {
        Path f = file("a.jpg", "original");
        edit("first", f, "changed");

        assertTrue(history.canUndo());
        assertEquals("first", history.getUndoDescription());
        assertEquals(List.of(f.toAbsolutePath().normalize()), history.undo());
        assertEquals("original", Files.readString(f));
        assertFalse(history.canUndo());
    }

    @Test
    void undoesInReverseOrder() throws IOException {
        Path f = file("a.jpg", "v1");
        edit("to v2", f, "v2");
        edit("to v3", f, "v3");

        history.undo();
        assertEquals("v2", Files.readString(f));
        history.undo();
        assertEquals("v1", Files.readString(f));
    }

    @Test
    void transactionUndoesAllFilesTogether() throws IOException {
        Path a = file("a.jpg", "a");
        Path b = file("b.jpg", "b");
        try (EditHistory.Transaction tx = history.begin("batch")) {
            tx.snapshot(a);
            Files.writeString(a, "A");
            tx.snapshot(b);
            Files.writeString(b, "B");
            tx.snapshot(a); // second snapshot of the same file is ignored
            Files.writeString(a, "AA");
            tx.commit();
        }

        assertEquals(2, history.undo().size());
        assertEquals("a", Files.readString(a));
        assertEquals("b", Files.readString(b));
    }

    @Test
    void uncommittedTransactionIsDiscarded() throws IOException {
        Path f = file("a.jpg", "original");
        try (EditHistory.Transaction tx = history.begin("failed")) {
            tx.snapshot(f);
        }
        assertFalse(history.canUndo());
    }

    @Test
    void oldestEditsAreDroppedBeyondLimit() throws IOException {
        Path f = file("a.jpg", "v0");
        for (int i = 1; i <= 5; i++) {
            edit("v" + i, f, "v" + i);
        }
        int undos = 0;
        while (history.canUndo()) {
            history.undo();
            undos++;
        }
        assertEquals(3, undos);
        assertEquals("v2", Files.readString(f));
    }

    @Test
    void notifiesListeners() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        history.addChangeListener(calls::incrementAndGet);
        Path f = file("a.jpg", "x");
        edit("e", f, "y");
        history.undo();
        assertEquals(2, calls.get());
    }

    @Test
    void closeDeletesSnapshotsWithoutNotifyingListeners() throws IOException {
        Path f = file("a.jpg", "x");
        edit("e", f, "y");
        AtomicInteger calls = new AtomicInteger();
        history.addChangeListener(calls::incrementAndGet);

        history.close();

        assertFalse(history.canUndo());
        assertEquals(0, calls.get(), "close() runs during shutdown and must not call back into the UI");
    }

    @Test
    void oldestEditsAreDroppedBeyondByteBudget() throws IOException {
        EditHistory budget = new EditHistory(20, 25);
        try {
            Path f = file("b.jpg", "0123456789"); // 10 bytes per snapshot
            for (int i = 0; i < 4; i++) {
                try (EditHistory.Transaction tx = budget.begin("e" + i)) {
                    tx.snapshot(f);
                    Files.writeString(f, "abcdefghi" + i);
                    tx.commit();
                }
            }
            int undos = 0;
            while (budget.canUndo()) {
                budget.undo();
                undos++;
            }
            assertEquals(2, undos, "only 2 x 10 bytes fit into 25 bytes");
        } finally {
            budget.close();
        }
    }
    @Test
    void recordedReversalsUndoInOrderWithFileEdits() throws IOException {
        Path f = file("a.jpg", "v1");
        edit("to v2", f, "v2");
        java.util.List<String> log = new java.util.ArrayList<>();
        history.record("rename", () -> {
            log.add("renamed back");
            return java.util.List.of();
        });
        assertEquals("rename", history.getUndoDescription());
        history.undo();
        assertEquals(java.util.List.of("renamed back"), log);
        assertEquals("v2", Files.readString(f));
        history.undo();
        assertEquals("v1", Files.readString(f));
        assertFalse(history.canUndo());
    }
}
