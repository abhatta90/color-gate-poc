package com.example.colorgate;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationListener;

import com.example.colorgate.ColorActivatedEvent.Reason;

/**
 * Reads color_gate once the application is ready and then every poll interval, and opens or closes the
 * {@link ColorGate}.
 * <p>
 * On activation the {@link OnColorActivated} handlers run first and the gate opens after them. That way
 * no gated task can start a job that a handler such as "resubmit EXECUTING jobs" would then treat as orphaned.
 * <p>
 * If color_gate cannot be read, the current state is kept. Going passive on a DB blip and active again
 * would re-run the activation handlers while this pod's own jobs are still running.
 */
public class ColorGateWatcher implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ColorGateWatcher.class);

    private final ColorGate gate;
    private final ActiveColorSource source;
    private final ApplicationEventPublisher publisher;
    private final Duration pollInterval;

    private ScheduledExecutorService poller;
    private boolean readOnce;

    ColorGateWatcher(ColorGate gate, ActiveColorSource source, ApplicationEventPublisher publisher, Duration pollInterval) {
        this.gate = gate;
        this.source = source;
        this.publisher = publisher;
        this.pollInterval = pollInterval;
    }

    @Override
    public synchronized void onApplicationEvent(ApplicationReadyEvent event) {
        if (poller != null) {
            return;
        }
        check();
        if (gate.isEnabled()) {
            poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "colorgate-watcher");
                thread.setDaemon(true);
                return thread;
            });
            long millis = pollInterval.toMillis();
            poller.scheduleWithFixedDelay(this::checkSafely, millis, millis, TimeUnit.MILLISECONDS);
        }
    }

    synchronized void check() {
        if (!gate.isEnabled()) {
            if (!gate.isActive()) {
                activate(Reason.STARTUP);
            }
            return;
        }

        Color active;
        try {
            active = source.read(gate.getServiceName()).orElse(null);
        } catch (RuntimeException e) {
            log.warn("Color gate: cannot read color_gate, {} stays {} ({})",
                    gate.getMyColor(), gate.isActive() ? "ACTIVE" : "PASSIVE", e.getMessage());
            return;
        }

        boolean firstRead = !readOnce;
        readOnce = true;
        gate.setActiveColor(active);
        boolean shouldBeActive = active == gate.getMyColor();

        if (shouldBeActive && !gate.isActive()) {
            activate(firstRead ? Reason.STARTUP : Reason.SWITCH);
        } else if (!shouldBeActive && gate.isActive()) {
            deactivate(active);
        } else if (firstRead) {
            log.info("Color gate: {} starts PASSIVE (active color: {})", gate.getMyColor(), describe(active));
        }
    }

    private void checkSafely() {
        try {
            check();
        } catch (Throwable e) {
            // An exception would silently cancel the scheduled poll.
            log.error("Color gate: unexpected error while checking color_gate", e);
        }
    }

    private void activate(Reason reason) {
        log.info("Color gate: {} is ACTIVE ({}), running @OnColorActivated handlers", label(), reason);
        try {
            publisher.publishEvent(new ColorActivatedEvent(gate.getMyColor(), reason));
        } catch (RuntimeException e) {
            // Open anyway: retrying would re-run handlers that may already have done part of their work.
            log.error("Color gate: an @OnColorActivated handler failed", e);
        }
        gate.setOpen(true);
        log.info("Color gate: {} gate OPEN, background work enabled", label());
    }

    private void deactivate(Color active) {
        gate.setOpen(false);
        log.info("Color gate: {} is PASSIVE, background work stopped (active color: {})", label(), describe(active));
        try {
            publisher.publishEvent(new ColorDeactivatedEvent(gate.getMyColor()));
        } catch (RuntimeException e) {
            log.error("Color gate: an @OnColorDeactivated handler failed", e);
        }
    }

    private String label() {
        return gate.isEnabled() ? gate.getMyColor().name() : "(gate disabled)";
    }

    private static String describe(Color active) {
        return active == null ? "none" : active.name();
    }

    @Override
    public void destroy() {
        if (poller != null) {
            poller.shutdownNow();
        }
    }
}
