package me.rothens.gpsexif.i18n;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The user interface's language. Texts are written in English in the code, wrapped in {@link #tr}; a language is
 * one text file next to this class ({@code hu.txt}) that pairs each English text with its translation:
 * <pre>
 * # a comment
 * Open folder
 * Mappa megnyitása
 *
 * {0} photos placed - Save to keep
 * {0} fénykép a helyén – mentéssel marad meg
 * </pre>
 * Pairs are separated by an empty line; {@code \n} stands for a line break. {@code {0}}, {@code {1}}, ... are
 * filled in with the values given to {@link #tr}, in any order the language needs. A text without a translation
 * stays English.
 */
public final class I18n {

    /** The languages there's a translation for, besides English: code and name in that language. */
    public static final Map<String, String> LANGUAGES = Map.of("hu", "Magyar");

    private static volatile Locale locale = Locale.ENGLISH;
    private static volatile Map<String, String> texts = Map.of();

    private I18n() {
    }

    /**
     * Picks the language: {@code code} ("hu", "en"), or for an empty code the system's language if there's a
     * translation for it, else English. Call before the user interface is built.
     */
    public static void use(String code) {
        String lang = null == code || code.isBlank() ? Locale.getDefault().getLanguage() : code.strip();
        if (!LANGUAGES.containsKey(lang)) {
            locale = Locale.ENGLISH;
            texts = Map.of();
            return;
        }
        try (InputStream in = I18n.class.getResourceAsStream(lang + ".txt")) {
            texts = null == in ? Map.of() : parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            locale = Locale.forLanguageTag(lang);
        } catch (IOException e) {
            System.err.println("Couldn't load the " + lang + " translation: " + e.getMessage());
            texts = Map.of();
            locale = Locale.ENGLISH;
        }
    }

    /** The language in use, for formatting dates and numbers. */
    public static Locale locale() {
        return locale;
    }

    /** The translation of {@code english}, with {0}, {1}, ... replaced by {@code args}. */
    public static String tr(String english, Object... args) {
        String text = texts.getOrDefault(english, english);
        if (args.length == 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{' && i + 2 < text.length() && Character.isDigit(text.charAt(i + 1))) {
                int end = text.indexOf('}', i);
                if (end > 0) {
                    try {
                        int n = Integer.parseInt(text.substring(i + 1, end));
                        if (n < args.length) {
                            sb.append(args[n]);
                            i = end;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                        // not a placeholder
                    }
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Swing's own texts: UI key and English text. */
    static final String[][] SWING_TEXTS = {
            {"OptionPane.okButtonText", "OK"}, {"OptionPane.cancelButtonText", "Cancel"},
            {"OptionPane.yesButtonText", "Yes"}, {"OptionPane.noButtonText", "No"},
            {"OptionPane.titleText", "Select an Option"}, {"OptionPane.messageDialogTitle", "Message"},
            {"OptionPane.inputDialogTitle", "Input"},
            {"FileChooser.openButtonText", "Open"}, {"FileChooser.saveButtonText", "Save"},
            {"FileChooser.cancelButtonText", "Cancel"}, {"FileChooser.openDialogTitleText", "Open"},
            {"FileChooser.saveDialogTitleText", "Save"}, {"FileChooser.lookInLabelText", "Look in:"},
            {"FileChooser.saveInLabelText", "Save in:"}, {"FileChooser.fileNameLabelText", "File name:"},
            {"FileChooser.folderNameLabelText", "Folder name:"},
            {"FileChooser.filesOfTypeLabelText", "Files of type:"},
            {"FileChooser.upFolderToolTipText", "Up one level"}, {"FileChooser.homeFolderToolTipText", "Home"},
            {"FileChooser.newFolderToolTipText", "Create new folder"},
            {"FileChooser.listViewButtonToolTipText", "List"},
            {"FileChooser.detailsViewButtonToolTipText", "Details"},
            {"FileChooser.acceptAllFileFilterText", "All files"},
            {"FileChooser.openButtonToolTipText", "Open selected file"},
            {"FileChooser.saveButtonToolTipText", "Save selected file"},
            {"FileChooser.cancelButtonToolTipText", "Close the dialog"},
            {"FileChooser.directoryOpenButtonText", "Open"},
            {"FileChooser.fileNameHeaderText", "Name"}, {"FileChooser.fileSizeHeaderText", "Size"},
            {"FileChooser.fileDateHeaderText", "Modified"}, {"FileChooser.fileTypeHeaderText", "Type"},
            {"FileChooser.newFolderButtonText", "New Folder"}, {"FileChooser.refreshActionLabelText", "Refresh"},
            {"FileChooser.viewMenuLabelText", "View"}, {"FileChooser.newFolderActionLabelText", "New Folder"},
            {"FileChooser.listViewActionLabelText", "List"},
            {"FileChooser.detailsViewActionLabelText", "Details"},
            {"FileChooser.chooseButtonText", "Choose"}};

    /**
     * Translates Swing's own texts (dialog buttons, the file chooser), which Java only has for a few languages.
     * Call after the look and feel is installed.
     */
    public static void installSwingTexts() {
        if (texts.isEmpty()) {
            return;
        }
        javax.swing.UIManager.getDefaults().setDefaultLocale(locale);
        javax.swing.JComponent.setDefaultLocale(locale);
        for (String[] k : SWING_TEXTS) {
            javax.swing.UIManager.put(k[0], tr(k[1]));
        }
    }

    /** "1 photo" / "12 photos", in the language in use. */
    public static String photos(int count) {
        return count == 1 ? tr("1 photo") : tr("{0} photos", count);
    }

    /** Reads a language file: pairs of lines (English, translation) separated by empty lines. */
    public static Map<String, String> parse(Reader reader) throws IOException {
        Map<String, String> map = new HashMap<>();
        BufferedReader in = new BufferedReader(reader);
        String english = null;
        String line;
        int number = 0;
        while (null != (line = in.readLine())) {
            number++;
            if (number == 1 && line.startsWith("﻿")) {
                line = line.substring(1);
            }
            if (line.startsWith("#")) {
                continue;
            }
            if (line.isBlank()) {
                english = null;
                continue;
            }
            String text = line.replace("\\n", "\n");
            if (null == english) {
                english = text;
            } else {
                map.put(english, text);
                english = null;
            }
        }
        return map;
    }

}
