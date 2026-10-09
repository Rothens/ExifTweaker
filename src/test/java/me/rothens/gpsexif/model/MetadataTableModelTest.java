package me.rothens.gpsexif.model;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import me.rothens.gpsexif.metadata.MetadataChanges;
import me.rothens.gpsexif.metadata.TextTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.GeoPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MetadataTableModelTest {

    @TempDir
    Path dir;

    private final List<String> edits = new ArrayList<>();
    private final List<String> invalid = new ArrayList<>();
    private final List<MetadataChanges> changes = new ArrayList<>();

    private ImageFile photo(String name, MetadataChanges initial) throws IOException {
        Path f = dir.resolve(name);
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", f.toFile());
        ImageFile image = new ImageFile(f.toFile(), new CommonsImagingBackend());
        image.apply(initial);
        return image;
    }

    private MetadataTableModel model(List<ImageFile> photos) {
        MetadataTableModel model = new MetadataTableModel();
        model.setEditHandler(new MetadataTableModel.EditHandler() {
            @Override
            public void edit(String label, String value, List<ImageFile> p, MetadataChanges c) {
                edits.add(label + "=" + value + " x" + p.size());
                changes.add(c);
            }

            @Override
            public void invalid(String message) {
                invalid.add(message);
            }
        });
        model.setPhotos(photos, photos.get(0));
        return model;
    }

    private static int row(String label) {
        return MetadataTableModel.editableLabels().indexOf(label);
    }

    @Test
    void showsSharedValuesAndMarksDifferentOnes() throws IOException {
        ImageFile a = photo("a.jpg", new MetadataChanges().text(TextTag.ARTIST, "Máté")
                .text(TextTag.COPYRIGHT, "CC-BY").position(new GeoPosition(1, 2)).altitude(123.456).direction(90.0));
        ImageFile b = photo("b.jpg", new MetadataChanges().text(TextTag.ARTIST, "Máté")
                .text(TextTag.COPYRIGHT, "All rights reserved"));
        MetadataTableModel model = model(List.of(a, b));

        assertEquals("Máté", model.getValueAt(row("Artist"), 1));
        assertEquals(MetadataTableModel.MULTIPLE, model.getValueAt(row("Copyright"), 1));
        assertTrue(model.isMultiple(row("Copyright")));
        assertEquals("", model.getValueAt(row("Description"), 1));
        assertEquals(MetadataTableModel.MULTIPLE, model.getValueAt(row("Altitude (m)"), 1));

        MetadataTableModel single = model(List.of(a));
        assertEquals("123.5", single.getValueAt(row("Altitude (m)"), 1));
        assertEquals("90", single.getValueAt(row("Direction (°)"), 1));
        assertTrue(single.isCellEditable(row("Artist"), 1));
        assertFalse(single.isCellEditable(row("Artist"), 0));
    }

    @Test
    void unchangedValuesAreNotWritten() throws IOException {
        ImageFile a = photo("a.jpg", new MetadataChanges().text(TextTag.ARTIST, "X"));
        ImageFile b = photo("b.jpg", new MetadataChanges().text(TextTag.ARTIST, "Y"));
        MetadataTableModel model = model(List.of(a, b));
        model.setValueAt(MetadataTableModel.MULTIPLE, row("Artist"), 1);
        MetadataTableModel single = model(List.of(a));
        single.setValueAt(" X ", row("Artist"), 1);
        assertTrue(edits.isEmpty(), edits.toString());

        model.setValueAt("Z", row("Artist"), 1);
        assertEquals(List.of("Artist=Z x2"), edits);
        model.setValueAt("tomorrow", row("Date taken"), 1);
        assertEquals(1, edits.size(), "invalid input isn't passed on");
        assertTrue(invalid.get(0).contains("isn't a date"), invalid.toString());
        assertEquals("Z", changes.get(0).getText().get(TextTag.ARTIST));
    }

    @Test
    void parsesAndValidatesInput() {
        assertEquals(LocalDateTime.of(2026, 9, 30, 14, 5), MetadataTableModel.parseDateTime("2026-09-30 14:05"));
        assertEquals(LocalDateTime.of(2026, 9, 30, 9, 5, 7), MetadataTableModel.parseDateTime("2026:09:30 9:05:07"));
        assertThrows(IllegalArgumentException.class, () -> MetadataTableModel.parseDateTime("yesterday"));

        assertEquals(-12.5, MetadataTableModel.parse(MetadataTableModel.Editable.ALTITUDE, "-12,5 m").getAltitude());
        assertTrue(MetadataTableModel.parse(MetadataTableModel.Editable.ALTITUDE, "").isRemoveAltitude());
        assertEquals(270.0, MetadataTableModel.parse(MetadataTableModel.Editable.DIRECTION, "-90°").getDirection());
        assertThrows(IllegalArgumentException.class,
                () -> MetadataTableModel.parse(MetadataTableModel.Editable.DIRECTION, "north"));
        assertThrows(IllegalArgumentException.class,
                () -> MetadataTableModel.parse(MetadataTableModel.Editable.ALTITUDE, "1e9"));
        assertThrows(IllegalArgumentException.class,
                () -> MetadataTableModel.parse(MetadataTableModel.Editable.TAKEN, ""));
        assertEquals("", MetadataTableModel.parse(MetadataTableModel.Editable.DESCRIPTION, "  ")
                .getText().get(TextTag.DESCRIPTION), "blank removes a text field");
    }

    @Test
    void placePartsAreSeparateRowsAndOnlyTheEditedPartChanges() throws IOException {
        ImageFile a = photo("a.jpg", new MetadataChanges().position(new GeoPosition(1, 2))
                .place(new me.rothens.gpsexif.metadata.Place("Apátság", "Tihny", "Veszprém", "Magyarország", "HU")));
        ImageFile b = photo("b.jpg", new MetadataChanges().position(new GeoPosition(1, 2))
                .place(new me.rothens.gpsexif.metadata.Place(null, "Tihany", null, "Hungary", "HU", "Óvár")));
        MetadataTableModel model = model(List.of(a, b));
        assertEquals(MetadataTableModel.MULTIPLE, model.getValueAt(row("City"), 1));
        assertEquals("HU", model.getValueAt(row("Country code"), 1));
        assertTrue(model.isCellEditable(row("Landmark"), 1));

        model.setValueAt("Tihany", row("City"), 1);
        assertEquals(1, changes.size());
        for (ImageFile photo : List.of(a, b)) {
            photo.apply(changes.get(0));
        }
        assertEquals(new me.rothens.gpsexif.metadata.Place("Apátság", "Tihany", "Veszprém", "Magyarország", "HU"),
                a.getPlace(), "only the city changed");
        assertEquals(new me.rothens.gpsexif.metadata.Place(null, "Tihany", null, "Hungary", "HU", "Óvár"), b.getPlace());

        model.setValueAt("Hungary!", row("Country code"), 1);
        assertEquals(1, invalid.size(), "a country code is 2 letters");
        model.setValueAt("jp", row("Country code"), 1);
        a.apply(changes.get(1));
        assertEquals("JP", a.getPlace().countryCode());

        // A photo without a place gets one from a single part
        ImageFile c = photo("c.jpg", new MetadataChanges().position(new GeoPosition(1, 2)));
        c.apply(new MetadataChanges().placePart(me.rothens.gpsexif.metadata.Place.Part.CITY, "Szeged"));
        assertEquals("Szeged", c.getPlace().city());
        c.apply(new MetadataChanges().placePart(me.rothens.gpsexif.metadata.Place.Part.CITY, ""));
        assertNull(c.getPlace(), "removing the only part removes the place");
    }
}
