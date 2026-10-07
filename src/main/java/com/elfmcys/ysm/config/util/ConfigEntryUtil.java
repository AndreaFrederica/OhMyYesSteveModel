package com.elfmcys.ysm.config.util;

import net.minecraftforge.common.config.Property;

import java.util.regex.Pattern;

@SuppressWarnings("unused")
public final class ConfigEntryUtil {
    public static void validifyAllowedValues(Property prop) {
        String[] validValues = prop.getValidValues();
        if (validValues == null || validValues.length == 0) return;

        String current = prop.getString();
        String bestMatch = null;
        // Higher level matches better
        // 0 - Not found, 1 - Ignore marks, 2 - Ignore case
        int bestMatchLevel = 0;
        // Used in ignore marks
        String cleanCurrent = null;

        for (String valid : validValues) {
            // Perfect
            if (valid.equals(current)) return;

            // Ignore case
            if (bestMatchLevel < 2 && valid.equalsIgnoreCase(current)) {
                bestMatch = valid;
                bestMatchLevel = 2;
                continue;
            }

            // Ignore marks
            if (bestMatchLevel < 1) {
                // Strip all marks
                String cleanValid = valid.replaceAll("[^\\p{L}\\p{N}]", "");
                if (cleanCurrent == null) cleanCurrent = current.replaceAll("[^\\p{L}\\p{N}]", "");
                if (cleanValid.equalsIgnoreCase(cleanCurrent)) {
                    bestMatch = valid;
                    bestMatchLevel = 1;
                }
            }
        }

        if (bestMatchLevel > 0) prop.set(bestMatch);
        else prop.set(prop.getDefault());
    }

    public static void commentAllowedValues(Property prop) {
        String[] validValues = prop.getValidValues();
        if (validValues == null || validValues.length == 0) return;

        prop.setComment(prop.getComment() + "\nAllowed values: " + String.join(", ", validValues));
    }

    public static void validifyPattern(Property prop) {
        Pattern pattern = prop.getValidationPattern();
        if (pattern == null) return;

        if (!pattern.matcher(prop.getString()).matches()) prop.set(prop.getDefault());
    }

    public static void commentPattern(Property prop) {
        Pattern pattern = prop.getValidationPattern();
        if (pattern == null) return;

        prop.setComment(prop.getComment() + "\nFormat: " + pattern.pattern());
    }

    public static void validifyRange(Property prop) {
        switch (prop.getType()) {
            case INTEGER -> {
                int current = prop.getInt();
                int clamped = Math.clamp(prop.getInt(), Integer.parseInt(prop.getMinValue()), Integer.parseInt(prop.getMaxValue()));
                if (current != clamped) prop.set(clamped);
            }
            case DOUBLE -> {
                double current = prop.getDouble();
                double clamped = Math.clamp(prop.getDouble(), Double.parseDouble(prop.getMinValue()), Double.parseDouble(prop.getMaxValue()));
                if (current != clamped) prop.set(clamped);
            }
        }
    }

    public static void commentRange(Property prop) {
        switch (prop.getType()) {
            case INTEGER -> {
                int max = Integer.parseInt(prop.getMaxValue());
                int min = Integer.parseInt(prop.getMinValue());
                if (max == Integer.MAX_VALUE && min == Integer.MIN_VALUE) return;

                String comment;
                if (max == Integer.MAX_VALUE) comment = "> " + min;
                else if (min == Integer.MIN_VALUE) comment = "< " + max;
                else comment = min + " ~ " + max;

                prop.setComment(prop.getComment() + "\nRange: " + comment);
            }
            case DOUBLE -> {
                double max = Double.parseDouble(prop.getMaxValue());
                double min = Double.parseDouble(prop.getMinValue());
                // Double has a larger range, so we still check default integer range here
                if (max == Integer.MAX_VALUE && min == Integer.MIN_VALUE) return;

                String comment;
                if (max == Integer.MAX_VALUE) comment = "> " + min;
                else if (min == Integer.MIN_VALUE) comment = "< " + max;
                else comment = min + " ~ " + max;

                prop.setComment(prop.getComment() + "\nRange: " + comment);
            }
        }
    }

    public interface IEnumTranslatable {
        String getLangKey();
    }
}
