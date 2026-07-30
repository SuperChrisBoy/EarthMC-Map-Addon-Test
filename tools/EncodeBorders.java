import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Build-time encoder: turns the two Natural-Earth-derived border JSONs into one compact binary blob.
 *
 * <p>The JSON pair costs ~11.3 MB and is wasteful three times over: coordinates are stored as decimal text
 * (~20 bytes for a number worth two), 86% of the country polylines appear verbatim in the states file as
 * well, and the mod then re-derives that country/state split on every startup.
 *
 * <p>This bakes all three away. Each polyline is stored once with a layer tag, coordinates are quantised to
 * a fraction of a block and delta-encoded against the previous point, and each delta is written as a zigzag
 * varint — border points sit close together, so a delta almost always fits in one or two bytes. The result
 * is roughly 0.9 MB, and the runtime just reads the layer tag instead of fingerprinting 21k polylines.
 *
 * <p>Format (little-endian where it matters; all integers are varints unless stated):
 * <pre>
 *   magic   "TMBL"        4 bytes
 *   version 1             1 byte
 *   scale   e.g. 4        1 byte   — coordinates were multiplied by this before rounding
 *   lineCount             varint
 *   per line:
 *     pointCount          varint
 *     layer               1 byte   — 1 = country outline, 2 = state-only
 *     pointCount pairs of (dx, dz) zigzag varints, each relative to the previous point (first is from 0,0)
 * </pre>
 */
public final class EncodeBorders {

    /** Coordinates are stored as round(blocks * SCALE); 4 = quarter-block precision. */
    private static final int SCALE = 4;
    private static final byte VERSION = 1;
    private static final byte LAYER_COUNTRY = 1;
    private static final byte LAYER_STATE_ONLY = 2;

    private static final Pattern LINE = Pattern.compile(
            "\"x\"\\s*:\\s*\\[(.*?)]\\s*,\\s*\"z\"\\s*:\\s*\\[(.*?)]",
            Pattern.DOTALL);

    private EncodeBorders() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: EncodeBorders <countries.json> <states-and-countries.json> <outputDir>");
        }
        Path countriesPath = Path.of(args[0]);
        Path statesPath = Path.of(args[1]);
        Path out = Path.of(args[2]).resolve("assets/townymapaddon/borders");
        Files.createDirectories(out);

        List<double[][]> countries = load(countriesPath);
        List<double[][]> states = load(statesPath);

        // Split exactly the way the runtime used to: a states-file line that also appears in the countries
        // file IS that country outline, so it belongs to the country layer and must not be stored twice.
        Set<String> countryKeys = new HashSet<>();
        for (double[][] line : countries) countryKeys.add(key(line));

        List<Entry> entries = new ArrayList<>(countries.size() + states.size());
        for (double[][] line : countries) entries.add(new Entry(line, LAYER_COUNTRY));
        int stateOnly = 0;
        for (double[][] line : states) {
            if (countryKeys.contains(key(line))) continue;   // already stored as a country outline
            entries.add(new Entry(line, LAYER_STATE_ONLY));
            stateOnly++;
        }

        byte[] blob = encode(entries);
        Path file = out.resolve("borders.bin");
        Files.write(file, blob);

        long jsonBytes = Files.size(countriesPath) + Files.size(statesPath);
        int points = 0;
        for (Entry e : entries) points += e.line[0].length;
        System.out.printf(
                "EncodeBorders: %d country + %d state-only lines (%,d points) -> %,d bytes (%.2f MB), "
                + "from %.2f MB of JSON (%.1fx smaller)%n",
                countries.size(), stateOnly, points, blob.length, blob.length / 1e6,
                jsonBytes / 1e6, jsonBytes / (double) blob.length);
    }

    private record Entry(double[][] line, byte layer) {}

    /** Identity of a polyline, used only to spot the country outlines duplicated into the states file. */
    private static String key(double[][] line) {
        return Arrays.toString(line[0]) + '|' + Arrays.toString(line[1]);
    }

    private static byte[] encode(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
        out.writeBytes("TMBL".getBytes(StandardCharsets.US_ASCII));
        out.write(VERSION);
        out.write(SCALE);
        writeVarInt(out, entries.size());
        for (Entry e : entries) {
            double[] xs = e.line[0];
            double[] zs = e.line[1];
            writeVarInt(out, xs.length);
            out.write(e.layer);
            long px = 0;
            long pz = 0;
            for (int i = 0; i < xs.length; i++) {
                long qx = Math.round(xs[i] * SCALE);
                long qz = Math.round(zs[i] * SCALE);
                writeVarInt(out, qx - px);
                writeVarInt(out, qz - pz);
                px = qx;
                pz = qz;
            }
        }
        return out.toByteArray();
    }

    /** Zigzag so negatives stay small, then 7 bits per byte with the high bit as the continuation flag. */
    private static void writeVarInt(ByteArrayOutputStream out, long value) {
        long v = (value << 1) ^ (value >> 63);
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                out.write(b | 0x80);
            } else {
                out.write(b);
                return;
            }
        }
    }

    /** Same dependency-free reader the tile prebuilder uses: the files are a flat map of x[]/z[] pairs. */
    private static List<double[][]> load(Path path) throws IOException {
        String json = Files.readString(path, StandardCharsets.UTF_8);
        Matcher matcher = LINE.matcher(json);
        List<double[][]> lines = new ArrayList<>();
        while (matcher.find()) {
            double[] x = doubles(matcher.group(1));
            double[] z = doubles(matcher.group(2));
            int n = Math.min(x.length, z.length);
            if (n < 2) continue;
            if (x.length != n) x = Arrays.copyOf(x, n);
            if (z.length != n) z = Arrays.copyOf(z, n);
            lines.add(new double[][]{x, z});
        }
        return lines;
    }

    private static double[] doubles(String csv) {
        String[] parts = csv.split(",");
        double[] values = new double[parts.length];
        int count = 0;
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) values[count++] = Double.parseDouble(trimmed);
        }
        return count == values.length ? values : Arrays.copyOf(values, count);
    }
}
