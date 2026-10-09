package me.rothens.gpsexif.i18n;

import me.rothens.gpsexif.map.MapLayer;
import me.rothens.gpsexif.metadata.Place;
import me.rothens.gpsexif.metadata.TextTag;
import me.rothens.gpsexif.share.ShareExport;
import me.rothens.gpsexif.ui.Theme;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Every language file has a translation for every text of the program, and nothing left over. */
class TranslationTest {

    private static final Pattern TR = Pattern.compile("\\btr\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @AfterEach
    void english() {
        I18n.use("en");
    }

    /** All texts the program translates: tr("...") in the sources, plus labels and Swing texts. */
    static Set<String> programTexts() throws IOException {
        Set<String> texts = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Matcher m = TR.matcher(Files.readString(file));
                while (m.find()) {
                    texts.add(unescape(m.group(1)));
                }
            }
        }
        I18n.use("en");
        for (TextTag t : TextTag.values()) {
            texts.add(t.label());
        }
        for (Place.Part p : Place.Part.values()) {
            texts.add(p.label());
        }
        for (Theme t : Theme.values()) {
            texts.add(t.toString());
        }
        for (MapLayer l : MapLayer.values()) {
            texts.add(l.toString());
        }
        for (ShareExport.Privacy p : ShareExport.Privacy.values()) {
            texts.add(p.toString());
        }
        for (String[] s : I18n.SWING_TEXTS) {
            texts.add(s[1]);
        }
        return texts;
    }

    private static String unescape(String java) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < java.length(); i++) {
            char c = java.charAt(i);
            if (c == '\\' && i + 1 < java.length()) {
                char e = java.charAt(++i);
                sb.append(switch (e) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> e;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    @Test
    void hungarianIsComplete() throws IOException {
        String file = new String(I18n.class.getResourceAsStream("hu.txt").readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> hu = I18n.parse(new StringReader(file));
        Set<String> program = programTexts();

        List<String> missing = new ArrayList<>();
        for (String text : program) {
            if (!hu.containsKey(text)) {
                missing.add(text.replace("\n", "\\n"));
            }
        }
        assertEquals(List.of(), missing, "texts without a Hungarian translation");

        List<String> unused = hu.keySet().stream().filter(k -> !program.contains(k)).sorted().toList();
        assertEquals(List.of(), unused, "translations of texts the program doesn't have (any more)");

        List<String> placeholders = new ArrayList<>();
        for (Map.Entry<String, String> e : hu.entrySet()) {
            if (!placeholders(e.getKey()).equals(placeholders(e.getValue()))) {
                placeholders.add(e.getKey() + " -> " + e.getValue());
            }
        }
        assertEquals(List.of(), placeholders, "{0}, {1} ... must stay");

        // The same English text twice is a mistake: the second one would win silently
        List<String> lines = file.lines().toList();
        Set<String> seen = new LinkedHashSet<>();
        List<String> duplicates = new ArrayList<>();
        boolean english = true;
        for (String line : lines) {
            if (line.startsWith("#")) {
                continue;
            }
            if (line.isBlank()) {
                english = true;
                continue;
            }
            if (english && !seen.add(line)) {
                duplicates.add(line);
            }
            english = !english;
        }
        assertEquals(List.of(), duplicates, "duplicate English texts");
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            found.add(m.group());
        }
        return found;
    }

    @Test
    void replacesPlaceholdersInAnyOrder() throws IOException {
        Map<String, String> texts = I18n.parse(new StringReader("""
                # comment
                {0} of {1}
                {1}-ből {0}

                Line\\nbreak
                Sor\\ntörés
                """));
        assertEquals("{1}-ből {0}", texts.get("{0} of {1}"));
        assertEquals("Sor\ntörés", texts.get("Line\nbreak"));
        assertEquals("3 of 5", I18n.tr("{0} of {1}", 3, 5), "English without a translation");
        assertEquals("{date} 7", I18n.tr("{date} {0}", 7), "other braces stay");
    }

    @Test
    void hungarianIsUsedWhenChosen() {
        I18n.use("hu");
        assertEquals("Mentés", I18n.tr("Save"));
        assertEquals("12 fénykép", I18n.photos(12));
        assertEquals("hu", I18n.locale().getLanguage());
        I18n.use("xx");
        assertEquals("Save", I18n.tr("Save"), "unknown languages are English");
    }
}
