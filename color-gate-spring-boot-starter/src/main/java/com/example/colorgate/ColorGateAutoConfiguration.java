package com.example.colorgate;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.endpoint.condition.ConditionalOnAvailableEndpoint;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

@AutoConfiguration(after = JdbcTemplateAutoConfiguration.class)
@EnableConfigurationProperties(ColorGateProperties.class)
public class ColorGateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ColorGate colorGate(ColorGateProperties properties, Environment environment) {
        String serviceName = StringUtils.hasText(properties.getServiceName())
                ? properties.getServiceName()
                : environment.getProperty("spring.application.name");
        if (!properties.isEnabled()) {
            return new ColorGate(false, serviceName, null);
        }
        if (!StringUtils.hasText(serviceName)) {
            throw new IllegalStateException("Color gate: set colorgate.service-name or spring.application.name");
        }
        String rawColor = StringUtils.hasText(properties.getMyColor())
                ? properties.getMyColor()
                : environment.getProperty("APP_COLOR");
        Color myColor = Color.parse(rawColor);
        if (myColor == null) {
            throw new IllegalStateException("Color gate: this pod's color is "
                    + (StringUtils.hasText(rawColor) ? "'" + rawColor + "'" : "not set")
                    + ". Set APP_COLOR=BLUE or GREEN (or colorgate.my-color),"
                    + " or colorgate.enabled=false where there is no blue/green.");
        }
        return new ColorGate(true, serviceName, myColor);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcTemplate.class)
    public ActiveColorSource activeColorSource(JdbcTemplate jdbcTemplate) {
        return new JdbcActiveColorSource(jdbcTemplate);
    }

    @Bean
    public ColorGateWatcher colorGateWatcher(ColorGate gate, ObjectProvider<ActiveColorSource> source,
                                             ApplicationEventPublisher publisher, ColorGateProperties properties) {
        ActiveColorSource activeColorSource = source.getIfAvailable();
        if (gate.isEnabled() && activeColorSource == null) {
            throw new IllegalStateException("Color gate: no ActiveColorSource."
                    + " Add a DataSource (for the color_gate table) or define an ActiveColorSource bean.");
        }
        return new ColorGateWatcher(gate, activeColorSource, publisher, properties.getPollInterval());
    }

    @Bean
    public ColorGateAspect colorGateAspect(ObjectProvider<ColorGate> gate) {
        return new ColorGateAspect(gate);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    static class ColorGateEndpointConfiguration {

        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnAvailableEndpoint
        ColorGateEndpoint colorGateEndpoint(ColorGate gate) {
            return new ColorGateEndpoint(gate);
        }
    }
}
