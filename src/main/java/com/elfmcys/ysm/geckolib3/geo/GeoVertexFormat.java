package com.elfmcys.ysm.geckolib3.geo;

import com.elfmcys.ysm.mixin.client.VertexFormatAccessor;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;

import java.util.List;

public final class GeoVertexFormat {
    private static final VertexFormatElement OVERLAY_2S = new VertexFormatElement(
            2, VertexFormatElement.EnumType.SHORT, VertexFormatElement.EnumUsage.UV, 2);

    public static final VertexFormat NEW_ENTITY = create();

    private GeoVertexFormat() {
    }

    private static VertexFormat create() {
        var format = new VertexFormat();
        format.addElement(DefaultVertexFormats.POSITION_3F);
        format.addElement(DefaultVertexFormats.COLOR_4UB);
        format.addElement(DefaultVertexFormats.TEX_2F);
        // Add UV elements in index order first; Cleanroom's addElement uses List.add(index, offset).
        format.addElement(DefaultVertexFormats.TEX_2S);
        format.addElement(OVERLAY_2S);
        format.addElement(DefaultVertexFormats.NORMAL_3B);
        format.addElement(DefaultVertexFormats.PADDING_1B);

        var accessor = (VertexFormatAccessor) format;
        swap(accessor.ysm$elements(), 3, 4);
        swap(accessor.ysm$uvOffsetsById(), 1, 2);
        verify(format);
        return format;
    }

    private static <T> void swap(List<T> list, int first, int second) {
        var value = list.get(first);
        list.set(first, list.get(second));
        list.set(second, value);
    }

    private static void verify(VertexFormat format) {
        if (format.getSize() != 36 || format.getElementCount() != 7) {
            throw new IllegalStateException("Unexpected Geo vertex format size or element count");
        }

        int[] offsets = {0, 12, 16, 24, 28, 32, 35};
        for (var index = 0; index < offsets.length; index++) {
            if (format.getOffset(index) != offsets[index]) {
                throw new IllegalStateException("Unexpected Geo vertex format offset at " + index);
            }
        }

        checkElement(format.getElement(0), VertexFormatElement.EnumUsage.POSITION,
                VertexFormatElement.EnumType.FLOAT, 0, 3);
        checkElement(format.getElement(1), VertexFormatElement.EnumUsage.COLOR,
                VertexFormatElement.EnumType.UBYTE, 0, 4);
        checkElement(format.getElement(2), VertexFormatElement.EnumUsage.UV,
                VertexFormatElement.EnumType.FLOAT, 0, 2);
        checkElement(format.getElement(3), VertexFormatElement.EnumUsage.UV,
                VertexFormatElement.EnumType.SHORT, 2, 2);
        checkElement(format.getElement(4), VertexFormatElement.EnumUsage.UV,
                VertexFormatElement.EnumType.SHORT, 1, 2);
        checkElement(format.getElement(5), VertexFormatElement.EnumUsage.NORMAL,
                VertexFormatElement.EnumType.BYTE, 0, 3);
        checkElement(format.getElement(6), VertexFormatElement.EnumUsage.PADDING,
                VertexFormatElement.EnumType.BYTE, 0, 1);

        if (format.getUvOffsetById(0) != 16
                || format.getUvOffsetById(1) != 28
                || format.getUvOffsetById(2) != 24
                || format.getColorOffset() != 12
                || format.getNormalOffset() != 32) {
            throw new IllegalStateException("Unexpected Geo vertex format usage offsets");
        }
    }

    private static void checkElement(VertexFormatElement element,
                                     VertexFormatElement.EnumUsage usage,
                                     VertexFormatElement.EnumType type,
                                     int index, int count) {
        if (element.getUsage() != usage || element.getType() != type
                || element.getIndex() != index || element.getElementCount() != count) {
            throw new IllegalStateException("Unexpected Geo vertex format element: " + element);
        }
    }
}
