package com.elfmcys.ysm.mixin.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.*;

class BufferBuilderMixinTest {
    @Test
    void reservesTheFullModelFromTheCrashReport() throws Exception {
        // Vanilla starts with 256 * 6 bytes and grows in 2 MiB blocks.
        // The reported draw needs 66308 * 36 = 2387088 bytes.
        try (var target = new Target(DefaultVertexFormat.NEW_ENTITY)) {
            try (var region = target.ysm$reserve(66308)) {
                assertEquals(2387088, region.size());
                region.nio().put(2387087, (byte) 0x5a);
            }
            assertEquals(0, target.integer("vertices"));
            assertEquals(0, target.integer("nextElementByte"));
            target.ysm$advance(66308);
            assertEquals(66308, target.integer("vertices"));
            assertEquals(2387088, target.integer("nextElementByte"));
        }
    }

    @Test
    void successiveModelsPreservePreviouslyWrittenVerticesAcrossGrowth() throws Exception {
        for (var format : new VertexFormat[]{DefaultVertexFormat.NEW_ENTITY, DefaultVertexFormat.BLOCK}) {
            try (var target = new Target(format)) {
                int stride = format.getVertexSize();
                for (int batch = 0; batch < 5; batch++) {
                    try (var region = target.ysm$reserve(66308)) {
                        region.nio().put(0, (byte) (batch + 1));
                        region.nio().put(region.size() - 1, (byte) (batch + 11));
                    }
                    target.ysm$advance(66308);
                }
                ByteBuffer result = target.buffer();
                for (int batch = 0; batch < 5; batch++) {
                    assertEquals(batch + 1, result.get(batch * 66308 * stride));
                    assertEquals(batch + 11, result.get((batch + 1) * 66308 * stride - 1));
                }
                assertEquals(5 * 66308, target.integer("vertices"));
            }
        }
    }

    @Test
    void rejectsInvalidSizesWithoutChangingTheBuffer() throws Exception {
        try (var target = new Target(DefaultVertexFormat.NEW_ENTITY)) {
            ByteBuffer original = target.buffer();
            assertThrows(IllegalArgumentException.class, () -> target.ysm$reserve(-1));
            assertThrows(ArithmeticException.class, () -> target.ysm$reserve(Integer.MAX_VALUE));
            try (var empty = target.ysm$reserve(0)) {
                assertEquals(0, empty.size());
            }
            assertSame(original, target.buffer());
            assertEquals(0, target.integer("nextElementByte"));
        }
    }

    /** Run the production mixin methods against Minecraft's real allocator, without a GL context.
     * JUnit does not apply mixins, so synchronize only the four @Shadow fields at the boundary.
     * The growth algorithm is invoked on BufferBuilder itself, never reimplemented here. */
    private static final class Target extends BufferBuilderMixin implements AutoCloseable {
        private final BufferBuilder builder = new BufferBuilder(256);
        private static final String[] FIELDS = {"buffer", "nextElementByte", "vertices", "format"};

        Target(VertexFormat format) throws Exception {
            builder.begin(VertexFormat.Mode.QUADS, format);
            sync(builder, this);
        }

        @Override
        protected void ensureCapacity(int bytes) {
            try {
                sync(this, builder);
                var method = BufferBuilder.class.getDeclaredMethod("ensureCapacity", int.class);
                method.setAccessible(true);
                method.invoke(builder, bytes);
                sync(builder, this);
            } catch (InvocationTargetException error) {
                throw new AssertionError(error.getCause());
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        }

        int integer(String name) throws Exception { return field(this, name).getInt(this); }
        ByteBuffer buffer() throws Exception { return (ByteBuffer) field(this, "buffer").get(this); }
        @Override public void close() throws Exception { MemoryUtil.memFree(buffer()); }

        private static Field field(Object owner, String name) throws NoSuchFieldException {
            var type = owner instanceof BufferBuilder ? BufferBuilder.class : BufferBuilderMixin.class;
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        private static void sync(Object from, Object to) throws IllegalAccessException, NoSuchFieldException {
            for (String name : FIELDS) field(to, name).set(to, field(from, name).get(from));
        }
    }
}
