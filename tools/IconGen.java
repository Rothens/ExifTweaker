// Draws the ExifTweaker icon and writes src/main/packaging/ExifTweaker.{png,ico,icns} and the window icons.
// Run from the repository root: java tools/IconGen.java src/main/packaging src/main/resources/me/rothens/gpsexif/icons icon-preview.png
import java.awt.*; import java.awt.geom.*; import java.awt.image.*; import javax.imageio.*; import java.io.*; import java.nio.*; import java.util.*;
public class IconGen {
  static BufferedImage draw(int size) {
    BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    g.scale(size / 1024.0, size / 1024.0);
    if (size <= 64) { // small icons: less margin, so the picture stays recognisable
      g.translate(512, 512); g.scale(1.2, 1.2); g.translate(-512, -512);
    }
    // Background tile (macOS-like margins: content 824 of 1024)
    RoundRectangle2D tile = new RoundRectangle2D.Double(100, 100, 824, 824, 185, 185);
    g.setPaint(new GradientPaint(0, 100, new Color(0x2F, 0x80, 0xED), 0, 924, new Color(0x1B, 0x4F, 0xA8)));
    g.fill(tile);
    // Photo: white frame, slightly tilted
    AffineTransform old = g.getTransform();
    g.rotate(Math.toRadians(-8), 470, 520);
    RoundRectangle2D frame = new RoundRectangle2D.Double(230, 290, 480, 420, 36, 36);
    g.setColor(new Color(0, 0, 0, 60));
    g.fill(new RoundRectangle2D.Double(238, 304, 480, 420, 36, 36));
    g.setColor(Color.WHITE);
    g.fill(frame);
    Shape picture = new Rectangle2D.Double(262, 322, 416, 300);
    g.setPaint(new GradientPaint(0, 322, new Color(0x8E, 0xD1, 0xFC), 0, 622, new Color(0xD8, 0xF0, 0xFF)));
    g.fill(picture);
    g.setClip(picture);
    // sun
    g.setColor(new Color(0xFF, 0xC8, 0x3D));
    g.fill(new Ellipse2D.Double(560, 350, 80, 80));
    // mountains
    Path2D m = new Path2D.Double();
    m.moveTo(262, 622); m.lineTo(390, 450); m.lineTo(460, 530); m.lineTo(540, 420); m.lineTo(678, 622); m.closePath();
    g.setColor(new Color(0x2E, 0x9E, 0x6A));
    g.fill(m);
    g.setClip(null);
    g.setTransform(old);
    // Map pin, bottom right
    double cx = 690, top = 470, r = 125;
    Path2D pin = new Path2D.Double();
    pin.append(new Arc2D.Double(cx - r, top, 2 * r, 2 * r, -30, 240, Arc2D.OPEN), false);
    pin.lineTo(cx, top + 2 * r + 150);
    pin.closePath();
    g.setColor(new Color(0, 0, 0, 70));
    g.translate(8, 12); g.fill(pin); g.translate(-8, -12);
    g.setPaint(new GradientPaint(0, (float) top, new Color(0xFF, 0x5A, 0x4E), 0, (float) (top + 2 * r + 150), new Color(0xD3, 0x2F, 0x2F)));
    g.fill(pin);
    g.setColor(Color.WHITE);
    g.fill(new Ellipse2D.Double(cx - 50, top + r - 50, 100, 100));
    g.dispose();
    return img;
  }
  static byte[] png(BufferedImage img) throws IOException { ByteArrayOutputStream o = new ByteArrayOutputStream(); ImageIO.write(img, "png", o); return o.toByteArray(); }
  public static void main(String[] a) throws Exception {
    File out = new File(a[0]); File res = new File(a[1]); out.mkdirs(); res.mkdirs();
    Map<Integer, byte[]> pngs = new TreeMap<>();
    for (int s : new int[]{16, 24, 32, 48, 64, 128, 256, 512, 1024}) pngs.put(s, png(draw(s)));
    try (FileOutputStream f = new FileOutputStream(new File(out, "ExifTweaker.png"))) { f.write(pngs.get(512)); }
    for (int s : new int[]{16, 32, 48, 64, 128, 256}) try (FileOutputStream f = new FileOutputStream(new File(res, "icon-" + s + ".png"))) { f.write(pngs.get(s)); }
    // ICO with PNG entries
    int[] ico = {16, 24, 32, 48, 64, 128, 256};
    ByteArrayOutputStream o = new ByteArrayOutputStream();
    ByteBuffer h = ByteBuffer.allocate(6 + 16 * ico.length).order(ByteOrder.LITTLE_ENDIAN);
    h.putShort((short) 0).putShort((short) 1).putShort((short) ico.length);
    int offset = 6 + 16 * ico.length;
    for (int s : ico) { byte[] p = pngs.get(s); h.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32).putInt(p.length).putInt(offset); offset += p.length; }
    o.write(h.array()); for (int s : ico) o.write(pngs.get(s));
    try (FileOutputStream f = new FileOutputStream(new File(out, "ExifTweaker.ico"))) { f.write(o.toByteArray()); }
    // ICNS with PNG entries
    String[][] icns = {{"icp4", "16"}, {"icp5", "32"}, {"icp6", "64"}, {"ic07", "128"}, {"ic08", "256"}, {"ic09", "512"}, {"ic10", "1024"}, {"ic11", "32"}, {"ic12", "64"}, {"ic13", "256"}, {"ic14", "512"}};
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    for (String[] e : icns) { byte[] p = pngs.get(Integer.parseInt(e[1])); ByteBuffer b = ByteBuffer.allocate(8); b.put(e[0].getBytes("US-ASCII")).putInt(8 + p.length); body.write(b.array()); body.write(p); }
    ByteBuffer hh = ByteBuffer.allocate(8); hh.put("icns".getBytes("US-ASCII")).putInt(8 + body.size());
    try (FileOutputStream f = new FileOutputStream(new File(out, "ExifTweaker.icns"))) { f.write(hh.array()); f.write(body.toByteArray()); }
    // preview sheet
    BufferedImage sheet = new BufferedImage(1400, 560, BufferedImage.TYPE_INT_RGB); Graphics2D g = sheet.createGraphics(); g.setColor(new Color(240,240,240)); g.fillRect(0,0,700,560); g.setColor(new Color(40,40,44)); g.fillRect(700,0,700,560);
    for (int side = 0; side < 2; side++) { int x = side * 700 + 20; g.drawImage(draw(256), x, 20, null); int xx = x + 280; for (int s : new int[]{128, 64, 48, 32, 24, 16}) { g.drawImage(draw(s), xx, 20, null); xx += s + 12; } }
    g.dispose(); ImageIO.write(sheet, "png", new File(a[2]));
  }
}
