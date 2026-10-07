package com.elfmcys.ysm.config.util;

import com.google.common.base.CaseFormat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

@SuppressWarnings("unused")
@FunctionalInterface
public interface IFormatter {
    @NotNull
    String format(@Nullable String str);

    /**
     * ExampleString -> ExampleString
     */
    IFormatter IDENTITY = input -> input == null ? "" : input;

    /**
     * ExampleString -> examplestring
     */
    IFormatter LOWER_CASE = input -> input == null || input.isEmpty() ? "" :
            input.toLowerCase(Locale.ENGLISH);

    /**
     * ExampleString -> example_string
     */
    IFormatter CAMEL_TO_SNAKE = input -> input == null || input.isEmpty() ? "" :
            CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, input);
}
