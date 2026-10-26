package com.example.colorgate;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reads the active color from the {@code color_gate} table (DDL: {@code classpath:colorgate/schema.sql}).
 */
public class JdbcActiveColorSource implements ActiveColorSource {

    private static final Logger log = LoggerFactory.getLogger(JdbcActiveColorSource.class);

    private static final String SELECT_ACTIVE_COLOR = "SELECT active_color FROM color_gate WHERE service_name = ?";

    private final JdbcTemplate jdbc;

    public JdbcActiveColorSource(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Color> read(String serviceName) {
        List<String> rows = jdbc.queryForList(SELECT_ACTIVE_COLOR, String.class, serviceName);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Color color = Color.parse(rows.get(0));
        if (color == null) {
            log.warn("Color gate: color_gate.active_color '{}' for {} is not BLUE or GREEN, treating as nobody active",
                    rows.get(0), serviceName);
        }
        return Optional.ofNullable(color);
    }
}
