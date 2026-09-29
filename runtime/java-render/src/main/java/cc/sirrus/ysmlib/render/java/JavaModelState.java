package cc.sirrus.ysmlib.render.java;

import cc.sirrus.ysmlib.render.BakedModel;
import java.util.*;
import org.joml.Matrix4f;
import org.joml.Matrix3f;

/** Per-entity state; extraction is transactional and never retains the caller's attributes. */
public final class JavaModelState implements cc.sirrus.ysmlib.render.ModelState {
  public static final int ATTRIBUTE_COUNT = 14;
  private BakedModel model;
  private Pose[] poses = new Pose[0];
  private List<Integer> renderBones = List.of(), locators = List.of();
  private int vertices, translucentVertices;
  private boolean valid, closed;
  private long generation;

  private static final class Pose {
    final Matrix4f matrix = new Matrix4f();
    final Matrix3f normal = new Matrix3f();
    boolean uniform = true;
    float orientation = 1, normalScale = 1;
    int color, glow;
  }

  public boolean extract(BakedModel next, float[] attributes) {
    ensureOpen();
    invalidate();
    if (next == null || attributes.length != next.bones().size() * ATTRIBUTE_COUNT) return false;
    var nextPoses = new Pose[next.bones().size()];
    var render = new ArrayList<Integer>();
    var locator = new ArrayList<Integer>();
    int total = 0, transparent = 0;
    for (int i = 0; i < nextPoses.length; ) {
      var bone = next.bones().get(i);
      int o = i * ATTRIBUTE_COUNT;
      float packedColor = attributes[o + 12], packedGlow = attributes[o + 13];
      if (!packed(packedColor, 0xffffff) || !packed(packedGlow, 0xffff)) return false;
      int glow = ((int) packedGlow) >>> 8;
      if (glow != 255 && glow > 15) return false;
      boolean finite = true;
      for (int k = 0; k < 12; k++) finite &= Float.isFinite(attributes[o + k]);
      float sx = attributes[o + 6], sy = attributes[o + 7], sz = attributes[o + 8];
      if (!finite || sx == 0 || sy == 0 || sz == 0) {
        i = bone.subtreeEnd();
        continue;
      }
      if (!packed(attributes[o + 11], 255)) return false;
      var pose = new Pose();
      if (bone.parent() >= 0) {
        var parent = nextPoses[bone.parent()];
        if (parent == null) return false;
        pose.matrix.set(parent.matrix);
        pose.normal.set(parent.normal);
        pose.uniform = parent.uniform;
        pose.orientation = parent.orientation;
        pose.normalScale = parent.normalScale;
      }
      var pivot = bone.pivot();
      pose.matrix
          .translate(
              (pivot.x() - attributes[o + 3]) / 16,
              (pivot.y() + attributes[o + 4]) / 16,
              (pivot.z() + attributes[o + 5]) / 16)
          .rotateZYX(attributes[o + 2], attributes[o + 1], attributes[o])
          .scale(sx, sy, sz)
          .translate(-pivot.x() / 16, -pivot.y() / 16, -pivot.z() / 16);
      pose.normal.rotateZYX(attributes[o + 2], attributes[o + 1], attributes[o]);
      boolean uniform = Math.abs(sx) == Math.abs(sy) && Math.abs(sy) == Math.abs(sz);
      pose.uniform &= uniform;
      if (uniform) pose.normalScale *= Math.abs(sx);
      pose.normal.scale(
          uniform ? Math.signum(sx) : 1 / sx,
          uniform ? Math.signum(sy) : 1 / sy,
          uniform ? Math.signum(sz) : 1 / sz);
      if ((sx < 0) ^ (sy < 0) ^ (sz < 0)) pose.orientation = -pose.orientation;
      if (!pose.matrix.isFinite() || !pose.normal.isFinite()) {
        i = bone.subtreeEnd();
        continue;
      }
      pose.color = (int) packedColor | (((int) packedGlow & 255) << 24);
      pose.glow = glow;
      nextPoses[i] = pose;
      if (attributes[o + 9] == 0) {
        boolean geometry = false;
        for (int p = 0; p < 4; p++) {
          int v = bone.vertices(p);
          geometry |= v != 0;
          total = Math.addExact(total, v);
          if (p >= 2) transparent = Math.addExact(transparent, v);
        }
        if (geometry) render.add(i);
        if (attributes[o + 11] != 0) locator.add(i);
      }
      i = attributes[o + 10] != 0 ? bone.subtreeEnd() : i + 1;
    }
    model = next;
    poses = nextPoses;
    renderBones = List.copyOf(render);
    locators = List.copyOf(locator);
    vertices = total;
    translucentVertices = transparent;
    valid = true;
    return true;
  }

  private static boolean packed(float v, int max) {
    return Float.isFinite(v) && v >= 0 && v <= max && v == (int) v;
  }

  public void invalidate() {
    valid = false;
    model = null;
    poses = new Pose[0];
    renderBones = List.of();
    locators = List.of();
    vertices = translucentVertices = 0;
    generation++;
  }

  public long generation() {
    return generation;
  }

  public boolean valid() {
    return valid && !closed;
  }

  public BakedModel model() {
    check();
    return model;
  }

  public List<Integer> renderBones() {
    check();
    return renderBones;
  }

  public List<Integer> locators() {
    check();
    return locators;
  }

  public int vertices() {
    check();
    return vertices;
  }

  public int translucentVertices() {
    check();
    return translucentVertices;
  }

  public Matrix4f pose(int bone, Matrix4f dst) {
    return dst.set(poseAt(bone).matrix);
  }

  public Matrix3f normal(int bone, Matrix3f dst) {
    return dst.set(poseAt(bone).normal);
  }

  public int color(int bone) {
    return poseAt(bone).color;
  }

  public int glow(int bone) {
    return poseAt(bone).glow;
  }

  public boolean uniform(int bone) {
    return poseAt(bone).uniform;
  }

  public float orientation(int bone) {
    return poseAt(bone).orientation;
  }

  public float normalScale(int bone) {
    return poseAt(bone).normalScale;
  }

  private Pose poseAt(int bone) {
    check();
    var p = poses[bone];
    if (p == null) throw new IllegalStateException("Inactive bone pose");
    return p;
  }

  private void check() {
    ensureOpen();
    if (!valid) throw new IllegalStateException("Model state is invalid");
  }

  private void ensureOpen() {
    if (closed) throw new IllegalStateException("Model state is closed");
  }

  @Override
  public void close() {
    invalidate();
    closed = true;
  }
}
