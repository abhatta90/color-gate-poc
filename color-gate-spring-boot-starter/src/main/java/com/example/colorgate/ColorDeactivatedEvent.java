package com.example.colorgate;

/**
 * Published when this pod stops being the active color. Handle it with {@link OnColorDeactivated}.
 * In the normal switch flow the old color is stopped before the switch, so this only fires when
 * color_gate is changed while the pod is still running.
 */
public record ColorDeactivatedEvent(Color color) {
}
