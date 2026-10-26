package com.example.colorgate;

import java.util.Locale;

/**
 * The two deployment colors. Stored as text in {@code color_gate.active_color}.
 */
public enum Color {
    BLUE, GREEN;

    /**
     * Lenient parse for env vars and DB values: case-insensitive, null for blank or unknown text.
     */
    static Color parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
