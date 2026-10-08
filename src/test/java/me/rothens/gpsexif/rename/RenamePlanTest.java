package me.rothens.gpsexif.rename;

import me.rothens.gpsexif.metadata.Place;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RenamePlanTest {

    private static final Place TIHANY = new Place(null, "Tihany", "Veszprém", "Magyarország", "HU");
    private static final LocalDateTime DAY = LocalDateTime.of(2026, 7, 11, 10, 0);

    @TempDir
    Path dir;

    private RenamePlan.Source photo(String name, LocalDateTime taken, Place place) throws Exception {
        Path p = Files.writeString(dir.resolve(name), name);
        String base = name.substring(0, name.lastIndexOf('.'));
        return new RenamePlan.Source(p, taken, new RenamePattern.Values(taken, place, base, "X100V"), null);
    }

    private static List<String> names(RenamePlan plan) {
        return plan.items().stream().map(RenamePlan.Item::newName).toList();
    }

    @Test
    void patterns() {
        RenamePattern.Values v = new RenamePattern.Values(DAY, TIHANY, "IMG_0001", "X100V");
        assertEquals("2026-07-11 Tihany 007", new RenamePattern("{date} {place} {n:000}").apply(v, 7));
        assertEquals("20260711_100000 Magyarország", new RenamePattern("{date:yyyyMMdd_HHmmss} {country}").apply(v, 1));
        assertEquals("IMG_0001 - X100V - 12", new RenamePattern("{name} - {camera} - {n}").apply(v, 12));
        // Missing parts go together with their separators
        RenamePattern.Values bare = new RenamePattern.Values(DAY, null, "IMG_0001", null);
        assertEquals("2026-07-11 001", new RenamePattern("{date} {place} {n:000}").apply(bare, 1));
        assertEquals("2026-07-11", new RenamePattern("{place} - {date}").apply(bare, 1));
        assertEquals("2026-07-11_IMG_0001", new RenamePattern("{date}_{city}_{name}").apply(bare, 1));
        // Characters Windows doesn't allow
        RenamePattern.Values odd = new RenamePattern.Values(DAY, new Place(null, "A/B: C?", null, null, null), "x", null);
        assertEquals("A B C", new RenamePattern("{place}").apply(odd, 1));
    }

    @Test
    void badPatternsSayWhy() {
        assertThrows(IllegalArgumentException.class, () -> new RenamePattern(" "));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> new RenamePattern("{town}")).getMessage()
                .contains("{place}"));
        assertThrows(IllegalArgumentException.class, () -> new RenamePattern("{date:qqqqq-zz-Q}x{"));
        assertThrows(IllegalArgumentException.class, () -> new RenamePattern("{n:abc}"));
        assertThrows(IllegalArgumentException.class, () -> new RenamePattern("{date"));
    }

    @Test
    void numbersInDateOrderAndKeepsExtensions() throws Exception {
        List<RenamePlan.Source> sources = List.of(photo("b.JPG", DAY.plusHours(2), TIHANY),
                photo("a.jpg", DAY, TIHANY), photo("c.heic", DAY.plusHours(1), null));
        RenamePlan plan = RenamePlan.of(sources, new RenamePattern("{date} {place} {n:00}"));
        assertEquals(List.of("2026-07-11 Tihany 03.JPG", "2026-07-11 Tihany 01.jpg", "2026-07-11 02.heic"),
                names(plan));
        assertEquals(3, plan.changedCount());
    }

    @Test
    void neverOverwritesAndNumbersCollisions() throws Exception {
        Files.writeString(dir.resolve("2026-07-11 Tihany.jpg"), "someone else");
        List<RenamePlan.Source> sources = List.of(photo("a.jpg", DAY, TIHANY), photo("b.jpg", DAY, TIHANY),
                photo("c.jpg", DAY, TIHANY));
        RenamePlan plan = RenamePlan.of(sources, new RenamePattern("{date} {place}"));
        assertEquals(List.of("2026-07-11 Tihany (2).jpg", "2026-07-11 Tihany (3).jpg", "2026-07-11 Tihany (4).jpg"),
                names(plan));
    }

    @Test
    void namesThatStayAndSwaps() throws Exception {
        RenamePlan.Source a = photo("1.jpg", DAY, null);
        RenamePlan.Source b = photo("2.jpg", DAY.plusHours(1), null);
        // Already named like this: nothing to do
        RenamePlan same = RenamePlan.of(List.of(a, b), new RenamePattern("{n}"));
        assertEquals(0, same.changedCount());
        assertEquals(List.of("1.jpg", "2.jpg"), names(same));

        // Swapped order: 1 <-> 2, done through temporary names
        RenamePlan.Source a2 = new RenamePlan.Source(a.path(), DAY.plusHours(2), a.values(), null);
        RenamePlan swap = RenamePlan.of(List.of(a2, b), new RenamePattern("{n}"));
        assertEquals(List.of("2.jpg", "1.jpg"), names(swap));
        RenamePlan.execute(swap.moves());
        assertEquals("1.jpg", Files.readString(dir.resolve("2.jpg")));
        assertEquals("2.jpg", Files.readString(dir.resolve("1.jpg")));
        RenamePlan.execute(RenamePlan.reverse(swap.moves()));
        assertEquals("1.jpg", Files.readString(dir.resolve("1.jpg")));
    }

    @Test
    void companionsGoAlongAndRawJpegPairsStayTogether() throws Exception {
        RenamePlan.Source raw = photo("IMG_1.CR2", DAY, TIHANY);
        Path sidecar = Files.writeString(dir.resolve("IMG_1.xmp"), "xmp");
        Files.writeString(dir.resolve("IMG_1.xmp.bak"), "xmp backup");
        raw = new RenamePlan.Source(raw.path(), raw.taken(), raw.values(), sidecar);
        RenamePlan.Source jpeg = photo("IMG_1.JPG", DAY, TIHANY);
        Files.writeString(dir.resolve("IMG_1.JPG.bak"), "jpeg backup");
        RenamePlan.Source other = photo("IMG_2.JPG", DAY.plusMinutes(1), TIHANY);

        RenamePlan plan = RenamePlan.of(List.of(raw, jpeg, other), new RenamePattern("{place} {n}"));
        assertEquals(List.of("Tihany 1.CR2", "Tihany 1.JPG", "Tihany 2.JPG"), names(plan));
        RenamePlan.execute(plan.moves());
        List<String> files = new ArrayList<>();
        try (var list = Files.list(dir)) {
            list.forEach(p -> files.add(p.getFileName().toString()));
        }
        files.sort(null);
        assertEquals(List.of("Tihany 1.CR2", "Tihany 1.JPG", "Tihany 1.JPG.bak", "Tihany 1.xmp", "Tihany 1.xmp.bak",
                "Tihany 2.JPG"), files);
        assertEquals("jpeg backup", Files.readString(dir.resolve("Tihany 1.JPG.bak")));
    }

    @Test
    void aFailedRenameUndoesItself() throws Exception {
        Path a = Files.writeString(dir.resolve("a.jpg"), "a");
        Path b = Files.writeString(dir.resolve("b.jpg"), "b");
        Files.writeString(dir.resolve("taken.jpg"), "someone else");
        List<RenamePlan.Move> moves = List.of(new RenamePlan.Move(a, dir.resolve("x.jpg")),
                new RenamePlan.Move(b, dir.resolve("taken.jpg")));
        assertThrows(java.io.IOException.class, () -> RenamePlan.execute(moves));
        assertEquals("a", Files.readString(a));
        assertEquals("b", Files.readString(b));
        assertEquals("someone else", Files.readString(dir.resolve("taken.jpg")));
        assertFalse(Files.exists(dir.resolve("x.jpg")));
    }
}
