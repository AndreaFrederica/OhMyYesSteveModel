package cc.sirrus.ysmlib.render.natives;

import cc.sirrus.ysmlib.render.*;
import cc.sirrus.ysmlib.render.java.JavaModelState;
import java.nio.*;
import java.util.List;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Entity-owned Java state and immutable packed geometry. No native pointer or global model cache. */
final class PackedModelState implements ModelState {
    private final JavaModelState delegate = new JavaModelState();
    private BakedModel packedModel;
    private ByteBuffer geometry;
    private ByteBuffer frame;

    ByteBuffer geometry() {
        BakedModel current = model();
        if (packedModel != current) {
            geometry = pack(current);
            packedModel = current;
        }
        return geometry;
    }

    ByteBuffer frame(Renderer.Parameters p) {
        int count = renderBones().size();
        int bytes = Math.addExact(260, Math.multiplyExact(count, 120));
        if (frame == null || frame.capacity() < bytes) frame = allocate(bytes);
        frame.clear().limit(bytes);
        p.model().get(0, frame);
        p.normal().get(64, frame);
        p.view().get(100, frame);
        p.projection().get(164, frame);
        frame.position(228);
        frame.putInt(p.rgba()).putInt(p.light()).putInt(p.overlay()).putLong(p.entity());
        frame.putInt(p.shadow() ? 1 : 0).putInt(count).putInt(vertices());
        var pose = new Matrix4f();
        var normal = new Matrix3f();
        for (int bone : renderBones()) {
            frame.putInt(bone);
            pose(bone, pose).get(frame.position(), frame);
            frame.position(frame.position() + 64);
            normal(bone, normal).get(frame.position(), frame);
            frame.position(frame.position() + 36);
            frame.putInt(color(bone)).putInt(glow(bone)).putInt(uniform(bone) ? 1 : 0)
                    .putFloat(orientation(bone));
        }
        // JNI observes capacity, not limit. Hide unused space when fewer bones are visible.
        return frame.flip().slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    static ByteBuffer pack(BakedModel model) {
        long bytes = 20;
        int cubes = 0;
        for (var bone : model.bones()) for (var partition : bone.partitions())
            for (var cube : partition) {
                bytes += 16L + 128L * cube.quads().size();
                cubes = Math.addExact(cubes, 1);
            }
        if (bytes > 256L * 1024 * 1024) throw new IllegalArgumentException("Packed geometry budget exceeded");
        var out = allocate((int) bytes);
        out.putInt(0x52534d59).putInt(1).putInt(model.bones().size()).putInt(cubes)
                .putInt(model.hasPbr() ? 1 : 0);
        // Preserve Java's partition/bone/cube order, including stable depth ties.
        for (int partition = 0; partition < 4; partition++)
            for (int b = 0; b < model.bones().size(); b++)
                for (var cube : model.bones().get(b).partitions().get(partition)) {
                    boolean cull = partition == 0 || partition == 3;
                    out.putInt(b).putInt(partition).putInt(cube.quads().size())
                            .putInt(cull ? cube.cullingQuads() : cube.quads().size());
                    for (var q : cube.quads()) {
                        if (q.indices().size() != 4 || q.uv().size() != 4)
                            throw new IllegalArgumentException("Quad requires four vertices");
                        for (int index : q.indices()) put(out, cube.positions().get(index));
                        for (var uv : q.uv()) out.putFloat(uv.x()).putFloat(uv.y());
                        put(out, q.normal());
                        var t = q.tangent();
                        out.putFloat(t.x()).putFloat(t.y()).putFloat(t.z()).putFloat(t.w());
                        put(out, q.center());
                        out.putFloat(q.planeD()).putFloat(q.windingSign());
                    }
                }
        return out.flip().asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer allocate(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
    private static void put(ByteBuffer out, Geometry.V3 v) {
        out.putFloat(v.x()).putFloat(v.y()).putFloat(v.z());
    }
    public boolean extract(BakedModel model, float[] attributes) {
        if (model != packedModel) { packedModel = null; geometry = null; }
        return delegate.extract(model, attributes);
    }
    public void invalidate() { delegate.invalidate(); }
    public long generation() { return delegate.generation(); }
    public boolean valid() { return delegate.valid(); }
    public BakedModel model() { return delegate.model(); }
    public List<Integer> renderBones() { return delegate.renderBones(); }
    public List<Integer> locators() { return delegate.locators(); }
    public int vertices() { return delegate.vertices(); }
    public int translucentVertices() { return delegate.translucentVertices(); }
    public Matrix4f pose(int bone, Matrix4f destination) { return delegate.pose(bone, destination); }
    public Matrix3f normal(int bone, Matrix3f destination) { return delegate.normal(bone, destination); }
    public int color(int bone) { return delegate.color(bone); }
    public int glow(int bone) { return delegate.glow(bone); }
    public boolean uniform(int bone) { return delegate.uniform(bone); }
    public float orientation(int bone) { return delegate.orientation(bone); }
    public float normalScale(int bone) { return delegate.normalScale(bone); }
    public void close() { delegate.close(); packedModel = null; geometry = null; frame = null; }
}
