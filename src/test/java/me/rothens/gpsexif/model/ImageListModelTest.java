package me.rothens.gpsexif.model;

import me.rothens.gpsexif.metadata.CommonsImagingBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.jxmapviewer.viewer.GeoPosition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ImageListModelTest {

    @TempDir
    Path dir;

    private ImageFile image(String name, boolean withLocation) throws IOException {
        Path f = dir.resolve(name);
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", f.toFile());
        ImageFile image = new ImageFile(f.toFile(), new CommonsImagingBackend());
        if (withLocation) {
            image.savePosition(new GeoPosition(1, 2));
        }
        return image;
    }

    @Test
    void filtersPhotosWithLocation() throws IOException {
        ImageFile a = image("a.jpg", true);
        ImageFile b = image("b.jpg", false);
        ImageFile c = image("c.jpg", false);
        ImageListModel model = new ImageListModel();
        model.setAll(List.of(a, b, c));
        assertEquals(3, model.getSize());

        model.setOnlyWithoutLocation(true);
        assertEquals(2, model.getSize());
        assertSame(b, model.getElementAt(0));
        assertEquals(-1, model.indexOf(a));
        assertEquals(3, model.getAll().size(), "hidden photos stay opened");

        b.savePosition(new GeoPosition(3, 4));
        model.refresh();
        assertEquals(1, model.getSize());
        assertSame(c, model.getElementAt(0));

        model.setOnlyWithoutLocation(false);
        assertEquals(3, model.getSize());
    }
}
