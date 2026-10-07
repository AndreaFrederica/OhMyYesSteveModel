package com.elfmcys.ysm.geckolib3.util;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.NoSuchElementException;

// FIXME
public class PoseStack {
    private final Deque<Pose> stack = new ArrayDeque<>(8);

    public PoseStack() {
        this.stack.add(new Pose());
    }

    public void translate(double x, double y, double z) {
        this.translate((float) x, (float) y, (float) z);
    }

    public void translate(float x, float y, float z) {
        this.last().pose().translate(x, y, z);
    }

    public void scale(float x, float y, float z) {
        Pose last = this.last();

        last.pose().scale(x, y, z);
        if (x == y && y == z) {
            if (x < 0.0F) {
                last.normal().scale(-1.0F);
            }
            return;
        }

        float invX = 1.0F / x;
        float invY = 1.0F / y;
        float invZ = 1.0F / z;
        float invCubeRoot = 1.0F / (float) Math.cbrt(invX * invY * invZ);
        last.normal().scale(invX * invCubeRoot, invY * invCubeRoot, invZ * invCubeRoot);
    }

    public void mulPose(Quaternionf quaternion) {
        Pose last = this.last();
        last.pose().rotate(quaternion);
        last.normal().rotate(quaternion);
    }

    public void mulPoseMatrix(Matrix4f matrix) {
        this.last().pose().mul(matrix);
    }

    public void pushPose() {
        this.stack.addLast(new Pose(this.last()));
    }

    public void popPose() {
        if (this.stack.size() == 1) {
            throw new NoSuchElementException("Cannot pop the root pose");
        }
        this.stack.removeLast();
    }

    public Pose last() {
        return this.stack.getLast();
    }

    public boolean clear() {
        return this.stack.size() == 1;
    }

    public void setIdentity() {
        Pose last = this.last();
        last.pose().identity();
        last.normal().identity();
    }

    public static final class Pose {
        private final Matrix4f pose;
        private final Matrix3f normal;

        private Pose() {
            this.pose = new Matrix4f();
            this.normal = new Matrix3f();
        }

        private Pose(Pose source) {
            this.pose = new Matrix4f(source.pose());
            this.normal = new Matrix3f(source.normal());
        }

        public Matrix4f pose() {
            return this.pose;
        }

        public Matrix3f normal() {
            return this.normal;
        }
    }
}
