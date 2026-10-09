package me.rothens.gpsexif.rename;

import static me.rothens.gpsexif.i18n.I18n.tr;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * What renaming photos with a pattern does: the new name of each photo, and the files that go along with it (the
 * {@code .bak} backup, an XMP sidecar and its backup). Nothing is ever overwritten: a name that is taken gets
 * " (2)", " (3)", ... A RAW and a JPEG of the same shot ({@code IMG_1.CR2}, {@code IMG_1.JPG}) keep sharing a name.
 */
public final class RenamePlan {

    /** A photo to rename. {@code sidecar} is its XMP sidecar (RAW files), or {@code null}. */
    public record Source(Path path, LocalDateTime taken, RenamePattern.Values values, Path sidecar) {
    }

    /** One file move. */
    public record Move(Path from, Path to) {
    }

    /**
     * The outcome for one photo.
     *
     * @param moves   the photo's move first, then its companions'; empty if the name stays
     * @param warning e.g. tr("no date taken"), or {@code null}
     */
    public record Item(Source source, String newName, List<Move> moves, String warning) {
        public boolean isChanged() {
            return !moves.isEmpty();
        }
    }

    private final List<Item> items;

    private RenamePlan(List<Item> items) {
        this.items = List.copyOf(items);
    }

    public List<Item> items() {
        return items;
    }

    /** All moves, photos and companions. */
    public List<Move> moves() {
        return items.stream().flatMap(i -> i.moves().stream()).toList();
    }

    public long changedCount() {
        return items.stream().filter(Item::isChanged).count();
    }

    /** Plans renaming {@code sources} (in their folders) with {@code pattern}. */
    public static RenamePlan of(List<Source> sources, RenamePattern pattern) {
        return of(sources, pattern, Files::exists);
    }

    static RenamePlan of(List<Source> sources, RenamePattern pattern, Predicate<Path> exists) {
        // Shots: photos with the same name apart from the extension, in the same folder
        Map<String, List<Source>> shots = new LinkedHashMap<>();
        sources.stream()
                .sorted(Comparator.comparing((Source s) -> null == s.taken() ? LocalDateTime.MAX : s.taken())
                        .thenComparing(s -> s.path().getFileName().toString().toLowerCase(Locale.ROOT)))
                .forEach(s -> shots.computeIfAbsent(key(s.path().resolveSibling(base(s.path()))), k -> new ArrayList<>())
                        .add(s));

        Set<Path> movingAway = new HashSet<>();
        for (Source s : sources) {
            for (Path p : companions(s)) {
                movingAway.add(normalize(p));
            }
        }
        Set<String> taken = new HashSet<>(); // keys of the names given out so far
        Map<Source, Item> result = new LinkedHashMap<>();
        int number = 0;
        for (List<Source> shot : shots.values()) {
            number++;
            Source first = shot.get(0);
            String applied = pattern.apply(first.values(), number);
            String warning = null;
            if (null == first.taken() && pattern.pattern().contains("{date")) {
                warning = tr("no date taken");
            }
            if (applied.isEmpty()) {
                warning = tr("nothing to name it after: stays");
            }
            String name = applied.isEmpty() ? base(first.path()) : applied;
            String candidate = name;
            Path folder = first.path().toAbsolutePath().getParent();
            boolean unchanged = shot.stream().allMatch(s -> base(s.path()).equals(name));
            for (int i = 2; !unchanged && !isFree(shot, folder, candidate, taken, movingAway, exists); i++) {
                candidate = name + " (" + i + ")";
                final String c = candidate;
                unchanged = shot.stream().allMatch(s -> base(s.path()).equals(c));
            }
            for (Source s : shot) {
                List<Move> moves = new ArrayList<>();
                if (!base(s.path()).equals(candidate)) {
                    for (Path from : companions(s)) {
                        moves.add(new Move(from, renamed(from, s.path(), candidate)));
                    }
                }
                for (Move m : moves) {
                    taken.add(key(m.to()));
                }
                if (moves.isEmpty()) {
                    companions(s).forEach(p -> taken.add(key(p)));
                }
                result.put(s, new Item(s, candidate + extension(s.path()), moves, warning));
            }
        }
        // In the order they were given
        List<Item> items = new ArrayList<>();
        for (Source s : sources) {
            items.add(result.get(s));
        }
        return new RenamePlan(items);
    }

    /** Whether every file of the shot can get {@code name} without overwriting anything. */
    private static boolean isFree(List<Source> shot, Path folder, String name, Set<String> taken,
                                  Set<Path> movingAway, Predicate<Path> exists) {
        for (Source s : shot) {
            for (Path from : companions(s)) {
                Path to = renamed(from, s.path(), name);
                if (taken.contains(key(to))) {
                    return false;
                }
                boolean isItself = key(to).equals(key(from));
                if (!isItself && exists.test(to) && !movingAway.contains(normalize(to))) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The photo, its backup, its sidecar and the sidecar's backup, as far as they exist (the photo always). */
    static List<Path> companions(Source s) {
        List<Path> files = new ArrayList<>();
        files.add(s.path());
        Path backup = s.path().resolveSibling(s.path().getFileName() + ".bak");
        if (Files.exists(backup)) {
            files.add(backup);
        }
        if (null != s.sidecar() && Files.exists(s.sidecar())) {
            files.add(s.sidecar());
            Path sidecarBackup = s.sidecar().resolveSibling(s.sidecar().getFileName() + ".bak");
            if (Files.exists(sidecarBackup)) {
                files.add(sidecarBackup);
            }
        }
        return files;
    }

    /** {@code file} (the photo or a companion of it) with the photo's base name replaced by {@code name}. */
    static Path renamed(Path file, Path photo, String name) {
        String photoBase = base(photo);
        String fileName = file.getFileName().toString();
        // Companions start with the photo's name: IMG_1.CR2.bak, IMG_1.xmp, IMG_1.CR2.xmp
        return file.resolveSibling(name + fileName.substring(photoBase.length()));
    }

    static String base(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot);
    }

    /** Names compare without case: on Windows and macOS "a.jpg" and "A.JPG" are the same file. */
    private static String key(Path p) {
        return normalize(p).toString().toLowerCase(Locale.ROOT);
    }

    private static Path normalize(Path p) {
        return p.toAbsolutePath().normalize();
    }

    /**
     * Performs the moves: first everything to temporary names, then to the new names, so photos can swap names.
     * If a move fails, the ones done are moved back.
     */
    public static void execute(List<Move> moves) throws IOException {
        List<Move> done = new ArrayList<>();
        List<Move> temps = new ArrayList<>();
        try {
            for (Move m : moves) {
                Path temp = m.from().resolveSibling(".exiftweaker-rename-" + temps.size() + "-"
                        + Long.toHexString(System.nanoTime()) + ".tmp");
                Files.move(m.from(), temp);
                done.add(new Move(m.from(), temp));
                temps.add(new Move(temp, m.to()));
            }
            for (Move m : temps) {
                if (Files.exists(m.to()) && !Files.isSameFile(m.from(), m.to())) {
                    throw new IOException(m.to().getFileName() + " exists already");
                }
                Files.move(m.from(), m.to());
                done.add(m);
            }
        } catch (IOException e) {
            for (int i = done.size() - 1; i >= 0; i--) {
                try {
                    Files.move(done.get(i).to(), done.get(i).from());
                } catch (IOException ignored) {
                    // reported below with the original failure
                }
            }
            throw new IOException(tr("Couldn't rename: {0}. Nothing was renamed.", e.getMessage()), e);
        }
    }

    /** The moves that undo {@code moves}. */
    public static List<Move> reverse(List<Move> moves) {
        List<Move> back = new ArrayList<>();
        for (int i = moves.size() - 1; i >= 0; i--) {
            back.add(new Move(moves.get(i).to(), moves.get(i).from()));
        }
        return back;
    }
}
