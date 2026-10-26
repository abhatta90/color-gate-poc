package com.example.colorgate;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.event.EventListener;

/**
 * Runs the method when this pod stops being the active color, after the gate has closed.
 * The method takes no arguments or a {@link ColorDeactivatedEvent}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EventListener(ColorDeactivatedEvent.class)
public @interface OnColorDeactivated {
}
