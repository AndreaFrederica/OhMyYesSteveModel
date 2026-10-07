package com.elfmcys.ysm.api.internal.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.elfmcys.ysm.api.annotation.YsmEventHandler;
import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.common.discovery.ASMDataTable;
import org.junit.jupiter.api.Test;

class YsmEventHandlerLoaderTest {
    @Test
    void selectsClassAnnotationsThenDeduplicatesAndSortsHandlers() {
        ASMDataTable.ASMData first = annotation(
                "sample.ZHandler", "sample.ZHandler", YsmEventHandler.class);
        ASMDataTable.ASMData methodIgnored = annotation(
                "sample.Ignored", "sample.Ignored#method()V", YsmEventHandler.class);
        ASMDataTable.ASMData deprecatedIgnored = annotation(
                "sample.IgnoredAnnotation", "sample.IgnoredAnnotation", Deprecated.class);

        ASMDataTable.ASMData tableFirst = annotation(
                "sample.A$Nested", "sample.A$Nested", YsmEventHandler.class);
        ASMDataTable.ASMData tableSecond = annotation(
                "sample.ZHandler", "sample.ZHandler", YsmEventHandler.class);

        assertEquals(List.of("sample.A$Nested", "sample.ZHandler"),
                YsmEventHandlerLoader.discoverHandlerNames(List.of(first, methodIgnored, deprecatedIgnored, tableFirst, tableSecond)));
    }

    private static ASMDataTable.ASMData annotation(
            String className,
            String objectName,
            Class<?> annotationType) {
        return new ASMDataTable.ASMData(
                null, annotationType.getName(),
                className, objectName, Map.of());
    }
}
