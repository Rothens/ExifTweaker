package me.rothens.gpsexif.gpx;

import org.jxmapviewer.viewer.GeoPosition;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the recorded positions of a FIT activity file (Garmin, Wahoo, Strava exports): the "record" messages with
 * a time, a position and an altitude. Everything else in the file is skipped.
 */
final class FitParser {

    private static final int RECORD = 20;
    private static final int FIELD_TIMESTAMP = 253;
    private static final int FIELD_LAT = 0;
    private static final int FIELD_LON = 1;
    private static final int FIELD_ALTITUDE = 2;
    private static final int FIELD_ENHANCED_ALTITUDE = 78;
    /** FIT times count seconds from 1989-12-31 00:00 UTC. */
    private static final long FIT_EPOCH = 631_065_600L;

    private record Field(int number, int size) {
    }

    private record Definition(int global, ByteOrder order, List<Field> fields, int developerBytes) {
    }

    private FitParser() {
    }

    /** Whether {@code head} (the file's first bytes) look like a FIT file. */
    static boolean isFit(byte[] head) {
        return head.length >= 12 && head[8] == '.' && head[9] == 'F' && head[10] == 'I' && head[11] == 'T';
    }

    static List<TrackPoint> parse(byte[] data) throws IOException {
        if (!isFit(data)) {
            throw new IOException("not a FIT file");
        }
        int headerSize = data[0] & 0xFF;
        long dataSize = ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
        int end = (int) Math.min(data.length, headerSize + dataSize);
        Definition[] definitions = new Definition[16];
        List<TrackPoint> points = new ArrayList<>();
        long lastTimestamp = -1;
        int pos = headerSize;
        try {
            while (pos < end) {
                int header = data[pos++] & 0xFF;
                int local;
                long compressedTime = -1;
                if ((header & 0x80) != 0) {
                    // Compressed timestamp header: a data message with a 5-bit time offset
                    local = (header >> 5) & 0x03;
                    int offset = header & 0x1F;
                    if (lastTimestamp >= 0) {
                        long t = (lastTimestamp & ~0x1FL) + offset;
                        if (offset < (lastTimestamp & 0x1F)) {
                            t += 0x20;
                        }
                        compressedTime = t;
                        lastTimestamp = t;
                    }
                } else if ((header & 0x40) != 0) {
                    local = header & 0x0F;
                    boolean developer = (header & 0x20) != 0;
                    ByteOrder order = data[pos + 1] == 1 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
                    int global = ByteBuffer.wrap(data, pos + 2, 2).order(order).getShort() & 0xFFFF;
                    int count = data[pos + 4] & 0xFF;
                    pos += 5;
                    List<Field> fields = new ArrayList<>();
                    for (int i = 0; i < count; i++) {
                        fields.add(new Field(data[pos] & 0xFF, data[pos + 1] & 0xFF));
                        pos += 3;
                    }
                    int developerBytes = 0;
                    if (developer) {
                        int devCount = data[pos++] & 0xFF;
                        for (int i = 0; i < devCount; i++) {
                            developerBytes += data[pos + 1] & 0xFF;
                            pos += 3;
                        }
                    }
                    definitions[local] = new Definition(global, order, fields, developerBytes);
                    continue;
                } else {
                    local = header & 0x0F;
                }
                Definition def = definitions[local];
                if (null == def) {
                    throw new IOException("data before its definition");
                }
                long timestamp = compressedTime;
                Integer lat = null;
                Integer lon = null;
                Double altitude = null;
                for (Field f : def.fields()) {
                    if (f.number() == FIELD_TIMESTAMP && f.size() == 4) {
                        long t = unsigned32(data, pos, def.order());
                        if (t != 0xFFFFFFFFL) {
                            timestamp = t;
                            lastTimestamp = t;
                        }
                    } else if (def.global() == RECORD && f.size() == 4
                            && (f.number() == FIELD_LAT || f.number() == FIELD_LON)) {
                        int v = ByteBuffer.wrap(data, pos, 4).order(def.order()).getInt();
                        if (v != 0x7FFFFFFF) {
                            if (f.number() == FIELD_LAT) {
                                lat = v;
                            } else {
                                lon = v;
                            }
                        }
                    } else if (def.global() == RECORD && f.number() == FIELD_ENHANCED_ALTITUDE && f.size() == 4) {
                        long v = unsigned32(data, pos, def.order());
                        if (v != 0xFFFFFFFFL) {
                            altitude = v / 5.0 - 500;
                        }
                    } else if (def.global() == RECORD && f.number() == FIELD_ALTITUDE && f.size() == 2
                            && null == altitude) {
                        int v = ByteBuffer.wrap(data, pos, 2).order(def.order()).getShort() & 0xFFFF;
                        if (v != 0xFFFF) {
                            altitude = v / 5.0 - 500;
                        }
                    }
                    pos += f.size();
                }
                pos += def.developerBytes();
                if (def.global() == RECORD && null != lat && null != lon && timestamp >= 0) {
                    double latitude = lat * (180.0 / 2147483648.0);
                    double longitude = lon * (180.0 / 2147483648.0);
                    if (Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) {
                        points.add(new TrackPoint(Instant.ofEpochSecond(FIT_EPOCH + timestamp),
                                new GeoPosition(latitude, longitude), altitude));
                    }
                }
            }
        } catch (IndexOutOfBoundsException e) {
            if (points.isEmpty()) {
                throw new IOException("the FIT file is cut off or damaged");
            }
            // A cut-off file (e.g. a crashed recording): keep what was read
        }
        return points;
    }

    private static long unsigned32(byte[] data, int pos, ByteOrder order) {
        return ByteBuffer.wrap(data, pos, 4).order(order).getInt() & 0xFFFFFFFFL;
    }
}
