package cc.sirrus.ysmlib.render.natives;

import cc.sirrus.ysmlib.render.*;
import cc.sirrus.ysmlib.render.java.JavaRenderer;
import java.nio.*;
import java.nio.file.Path;
import java.util.List;

/** Optional whole-draw C++ accelerator. State extraction remains the Java implementation. */
public final class NativeRenderProvider implements RenderProvider {
    @FunctionalInterface
    interface NativeDraw {
        int render(ByteBuffer geometry, ByteBuffer frame, int stride, int midOffset, ByteBuffer output);
    }
    private final NativeDraw draw;
    private final JavaRenderer baseline = new JavaRenderer();
    private volatile boolean available = true;
    private final java.util.concurrent.atomic.AtomicLong successfulDraws = new java.util.concurrent.atomic.AtomicLong();
    private final Renderer renderer = new Renderer() {
        // Compatibility consumers still receive the portable object representation.
        public List<Vertex> render(ModelState state, Parameters parameters) {
            return baseline.render(state, parameters);
        }
        public void write(List<Vertex> vertices, Layout layout, ByteBuffer destination) {
            baseline.write(vertices, layout, destination);
        }
        public void renderInto(ModelState state, Parameters parameters, Layout layout,
                               ByteBuffer destination) {
            int bytes = Math.multiplyExact(state.vertices(), layout.stride);
            if (destination.isReadOnly() || destination.remaining() < bytes)
                throw new IllegalArgumentException("Invalid vertex destination");
            if (available && destination.isDirect() && state instanceof PackedModelState packed) {
                try {
                    int status = draw.render(packed.geometry(), packed.frame(parameters),
                            layout.stride, layout.midOffset, destination.slice());
                    if (status == 0) {
                        successfulDraws.incrementAndGet();
                        return;
                    }
                    // The ABI guarantees no output on rejection. Retry this draw in Java.
                    disable("Native draw rejected (status " + status + "); "
                            + rejectedFrame(packed.frame(parameters), parameters), null);
                } catch (LinkageError unavailable) {
                    disable("Native draw linkage failed", unavailable);
                }
            }
            baseline.renderInto(state, parameters, layout, destination);
        }
    };

    NativeRenderProvider(NativeDraw draw) { this.draw = draw; }

    public NativeRenderProvider(Path library) {
        this(NativeRenderProvider::nRender);
        System.load(library.toAbsolutePath().normalize().toString());
        if (nAbiVersion() != 1) throw new UnsatisfiedLinkError("ysmlib render ABI mismatch");
        // Bind the draw symbol during construction, before publishing the provider.
        var geometry = ByteBuffer.allocateDirect(20).order(ByteOrder.LITTLE_ENDIAN);
        geometry.putInt(0x52534d59).putInt(1).putInt(0).putInt(0).putInt(0).flip();
        var frame = ByteBuffer.allocateDirect(260).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset : new int[]{0, 100, 164})
            new org.joml.Matrix4f().get(offset, frame);
        new org.joml.Matrix3f().get(64, frame);
        if (nRender(geometry, frame, 36, 0, ByteBuffer.allocateDirect(0)) != 0)
            throw new UnsatisfiedLinkError("ysmlib renderer self-check failed");
    }
    public String id() { return available ? "native-cpp-render-v1 (packed)" : "java-render"; }
    public String stateId() { return "java-model-state"; }
    public ModelState createState() { return new PackedModelState(); }
    public Renderer renderer() { return renderer; }
    /** Completed packed draws, excluding startup self-check and Java fallback. */
    public long successfulNativeDraws() { return successfulDraws.get(); }
    private static String rejectedFrame(ByteBuffer frame, Renderer.Parameters p) {
        var invalid = new java.util.ArrayList<Integer>();
        for (int offset = 0; offset < 228; offset += 4)
            if (!Float.isFinite(frame.getFloat(offset))) invalid.add(offset);
        return "frame bytes=" + frame.remaining() + ", non-finite matrix offsets=" + invalid
                + ", source matrices finite=" + (p.model().isFinite() && p.normal().isFinite()
                && p.view().isFinite() && p.projection().isFinite());
    }
    private synchronized void disable(String reason, Throwable cause) {
        if (!available) return;
        available = false;
        System.getLogger(NativeRenderProvider.class.getName()).log(System.Logger.Level.WARNING,
                reason + "; using Java renderer for this session", cause);
    }
    private static native int nAbiVersion();
    static native int nRender(ByteBuffer geometry, ByteBuffer frame, int stride, int midOffset,
                              ByteBuffer destination);
}
