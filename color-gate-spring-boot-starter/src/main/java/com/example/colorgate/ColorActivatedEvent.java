package com.example.colorgate;

/**
 * Published when this pod becomes the active color. Handle it with {@link OnColorActivated}.
 *
 * @param color  this pod's color (null when the gate is disabled)
 * @param reason whether the pod started as the active color or was switched to it
 */
public record ColorActivatedEvent(Color color, Reason reason) {

    public enum Reason {
        /** The pod started and its color was already active (or the gate is disabled). */
        STARTUP,
        /** The pipeline switched color_gate to this pod's color while it was running. */
        SWITCH
    }
}
