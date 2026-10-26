package com.example.colorgate;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("colorgate")
public class ColorGateProperties {

    /** Set to false where there is no blue/green (local, dev): the pod is then always active. */
    private boolean enabled = true;

    /** Row key in color_gate. Defaults to spring.application.name. */
    private String serviceName;

    /** This pod's color, BLUE or GREEN. Defaults to the APP_COLOR environment variable. */
    private String myColor;

    /** How often color_gate is read. */
    private Duration pollInterval = Duration.ofSeconds(1);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getMyColor() {
        return myColor;
    }

    public void setMyColor(String myColor) {
        this.myColor = myColor;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }
}
