package cc.sirrus.ysmlib.cpm;

import java.util.*;

/**
 * Static, format-neutral CPM model IR. The ordinary compiler preserves authored
 * source coordinates; a canonical player compiler may normalize host skeleton
 * pivots and legacy local coordinates. No animation or script is run here.
 */
public record CpmCompiledModel(List<Bone> bones, List<Primitive> primitives,
                               Map<String, Texture> textures, CpmDocument source,
                               int textureWidth, int textureHeight, double defaultScale,
                               List<Locator> locators) {
    public CpmCompiledModel {
        bones = List.copyOf(bones); primitives = List.copyOf(primitives);
        textures = Map.copyOf(textures); Objects.requireNonNull(source);
        if (textureWidth <= 0 || textureHeight <= 0) throw new IllegalArgumentException("Invalid CPM texture dimensions");
        if (!Double.isFinite(defaultScale) || defaultScale <= 0) throw new IllegalArgumentException("Invalid CPM default scale");
        locators = List.copyOf(locators);
    }
    /** Compatibility constructor for callers interested only in the original static IR. */
    public CpmCompiledModel(List<Bone> bones, List<Primitive> primitives,
                            Map<String, Texture> textures, CpmDocument source) {
        this(bones, primitives, textures, source, 64, 64, 1d, List.of());
    }
    public record Bone(int index, String name, int parent, Vec3 pivot, Vec3 rotation) {
        public Bone { Objects.requireNonNull(name); Objects.requireNonNull(pivot); Objects.requireNonNull(rotation); }
    }
    public enum Kind { CUBE, QUAD }
    public record Primitive(Kind kind, int bone, Vec3 origin, Vec3 size, Vec3 pivot,
                            Vec3 rotation, List<Double> authoredCoordinates,
                            List<Double> textureOffset, double inflate, boolean mirror,
                            CpmValue uv, String texture, Object source) {
        public Primitive {
            authoredCoordinates=List.copyOf(authoredCoordinates); textureOffset=List.copyOf(textureOffset);
            Objects.requireNonNull(kind); Objects.requireNonNull(origin); Objects.requireNonNull(size);
            Objects.requireNonNull(pivot); Objects.requireNonNull(rotation); Objects.requireNonNull(uv);
        }
        /** Compatibility constructor retained for downstream Lib users. */
        public Primitive(Kind kind, int bone, Vec3 origin, Vec3 size, Vec3 pivot,
                         Vec3 rotation, List<Double> authoredCoordinates,
                         List<Double> textureOffset, double inflate, boolean mirror,
                         String texture, Object source) {
            this(kind, bone, origin, size, pivot, rotation, authoredCoordinates, textureOffset,
                    inflate, mirror, CpmValue.NullValue.INSTANCE, texture, source);
        }
    }
    public record Texture(String symbol, String packagePath) {}
    /** A source-independent attachment or first-person visibility declaration. */
    public record Locator(String bone, String group) {
        public Locator { Objects.requireNonNull(bone); Objects.requireNonNull(group); }
    }
    public record Vec3(double x, double y, double z) {
        public Vec3 {
            // Keep canonical zero stable across JSON signed-zero spellings;
            // this also makes structural IR comparisons deterministic.
            x = x == 0d ? 0d : x;
            y = y == 0d ? 0d : y;
            z = z == 0d ? 0d : z;
        }
        public static final Vec3 ZERO = new Vec3(0, 0, 0);
        public Vec3 add(Vec3 other) { return new Vec3(x + other.x, y + other.y, z + other.z); }
    }
    /** Explicit conversion keeps coordinate policy out of source parsing. */
    public record CoordinateTransform(double scale, boolean flipX, boolean flipY, boolean flipZ) {
        public CoordinateTransform { if (!Double.isFinite(scale) || scale == 0) throw new IllegalArgumentException("Invalid scale"); }
        public Vec3 apply(Vec3 v) { return new Vec3(v.x() * scale * (flipX ? -1 : 1), v.y() * scale * (flipY ? -1 : 1), v.z() * scale * (flipZ ? -1 : 1)); }
        public static CoordinateTransform authoredPixels() { return new CoordinateTransform(1, false, false, false); }
    }
}
