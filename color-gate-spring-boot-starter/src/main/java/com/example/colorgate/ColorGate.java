package com.example.colorgate;

/**
 * Answers one question for background work: may this pod start work right now?
 * <p>
 * Inject it and call {@link #isActive()} as the first line of a {@code TimerTask}, executor loop or any
 * other background code the library cannot intercept. The state is kept up to date by
 * {@link ColorGateWatcher}.
 */
public class ColorGate {

    private final boolean enabled;
    private final String serviceName;
    private final Color myColor;

    private volatile Color activeColor;
    private volatile boolean open;

    ColorGate(boolean enabled, String serviceName, Color myColor) {
        this.enabled = enabled;
        this.serviceName = serviceName;
        this.myColor = myColor;
    }

    /**
     * True when this pod's color is the active one and its {@link OnColorActivated} handlers have finished.
     * Always true once started when the gate is disabled ({@code colorgate.enabled=false}).
     */
    public boolean isActive() {
        return open;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getServiceName() {
        return serviceName;
    }

    /** This pod's color; null when the gate is disabled. */
    public Color getMyColor() {
        return myColor;
    }

    /** Last value read from {@code color_gate}; null when there is no row (nobody active) or nothing was read yet. */
    public Color getActiveColor() {
        return activeColor;
    }

    void setActiveColor(Color activeColor) {
        this.activeColor = activeColor;
    }

    void setOpen(boolean open) {
        this.open = open;
    }
}
