package com.example.colorgate;

import java.util.Optional;

/**
 * Where the active color is read from. The default implementation reads the {@code color_gate} table;
 * define your own bean to read it from somewhere else.
 */
@FunctionalInterface
public interface ActiveColorSource {

    /** The active color for the service, or empty when there is no row (nobody is active). */
    Optional<Color> read(String serviceName);
}
