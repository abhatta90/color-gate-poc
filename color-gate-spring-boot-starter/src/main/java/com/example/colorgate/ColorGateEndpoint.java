package com.example.colorgate;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;

/**
 * {@code GET /actuator/colorgate}: this pod's color, the active color and whether the gate is open.
 */
@Endpoint(id = "colorgate")
public class ColorGateEndpoint {

    private final ColorGate gate;

    public ColorGateEndpoint(ColorGate gate) {
        this.gate = gate;
    }

    @ReadOperation
    public Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("enabled", gate.isEnabled());
        state.put("serviceName", gate.getServiceName());
        state.put("myColor", gate.getMyColor());
        state.put("activeColor", gate.getActiveColor());
        state.put("active", gate.isActive());
        return state;
    }
}
