package com.example.colorgate;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

class ColorGateTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AopAutoConfiguration.class, ColorGateAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class)
            .withPropertyValues("spring.application.name=test-service", "colorgate.my-color=GREEN");

    @Test
    void passivePodSkipsGatedWorkButRunsRunInAllColors() {
        runner.run(context -> {
            context.getBean(StubSource.class).active = Color.BLUE;
            context.getBean(ColorGateWatcher.class).check();

            Tasks tasks = context.getBean(Tasks.class);
            tasks.scheduled();
            tasks.everyColor();
            tasks.gated();

            assertThat(context.getBean(ColorGate.class).isActive()).isFalse();
            assertThat(tasks.calls()).containsExactly("everyColor");
        });
    }

    @Test
    void startingAsActiveColorRunsHandlersWithStartupReason() {
        runner.run(context -> {
            context.getBean(StubSource.class).active = Color.GREEN;
            context.getBean(ColorGateWatcher.class).check();

            assertThat(context.getBean(ColorGate.class).isActive()).isTrue();
            assertThat(context.getBean(Tasks.class).calls()).containsExactly("activated:STARTUP");
        });
    }

    @Test
    void switchRunsActivationHandlersBeforeGateOpens() {
        runner.run(context -> {
            StubSource source = context.getBean(StubSource.class);
            ColorGateWatcher watcher = context.getBean(ColorGateWatcher.class);
            Tasks tasks = context.getBean(Tasks.class);

            source.active = Color.BLUE;
            watcher.check();
            source.active = Color.GREEN;
            watcher.check();
            tasks.scheduled();

            assertThat(tasks.gateOpenDuringActivation()).isFalse();
            assertThat(context.getBean(ColorGate.class).isActive()).isTrue();
            assertThat(tasks.calls()).containsExactly("activated:SWITCH", "scheduled");
        });
    }

    @Test
    void switchingAwayClosesGate() {
        runner.run(context -> {
            StubSource source = context.getBean(StubSource.class);
            ColorGateWatcher watcher = context.getBean(ColorGateWatcher.class);

            source.active = Color.GREEN;
            watcher.check();
            source.active = Color.BLUE;
            watcher.check();

            assertThat(context.getBean(ColorGate.class).isActive()).isFalse();
            assertThat(context.getBean(Tasks.class).calls()).containsExactly("activated:STARTUP", "deactivated");
        });
    }

    @Test
    void unreadableTableKeepsCurrentState() {
        runner.run(context -> {
            StubSource source = context.getBean(StubSource.class);
            ColorGateWatcher watcher = context.getBean(ColorGateWatcher.class);

            source.active = Color.GREEN;
            watcher.check();
            source.failing = true;
            watcher.check();
            source.failing = false;
            watcher.check();

            assertThat(context.getBean(ColorGate.class).isActive()).isTrue();
            assertThat(context.getBean(Tasks.class).calls()).containsExactly("activated:STARTUP");
        });
    }

    @Test
    void noRowMeansNobodyIsActive() {
        runner.run(context -> {
            context.getBean(ColorGateWatcher.class).check();

            assertThat(context.getBean(ColorGate.class).isActive()).isFalse();
        });
    }

    @Test
    void disabledGateIsActiveAfterStartup() {
        runner.withPropertyValues("colorgate.enabled=false", "colorgate.my-color=").run(context -> {
            context.getBean(ColorGateWatcher.class).check();
            Tasks tasks = context.getBean(Tasks.class);
            tasks.scheduled();

            assertThat(context.getBean(ColorGate.class).isActive()).isTrue();
            assertThat(tasks.calls()).containsExactly("activated:STARTUP", "scheduled");
        });
    }

    @Test
    void unknownColorFailsStartup() {
        runner.withPropertyValues("colorgate.my-color=RED").run(context ->
                assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("'RED'").hasMessageContaining("APP_COLOR"));
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfig {

        @Bean
        StubSource stubSource() {
            return new StubSource();
        }

        @Bean
        Tasks tasks(ColorGate gate) {
            return new Tasks(gate);
        }
    }

    static class StubSource implements ActiveColorSource {

        volatile Color active;
        volatile boolean failing;

        @Override
        public Optional<Color> read(String serviceName) {
            if (failing) {
                throw new IllegalStateException("database down");
            }
            return Optional.ofNullable(active);
        }
    }

    static class Tasks {

        private final ColorGate gate;
        private final List<String> calls = new CopyOnWriteArrayList<>();
        private Boolean gateOpenDuringActivation;

        Tasks(ColorGate gate) {
            this.gate = gate;
        }

        @Scheduled(fixedDelay = 1000)
        public void scheduled() {
            calls.add("scheduled");
        }

        @RunInAllColors
        @Scheduled(fixedDelay = 1000)
        public void everyColor() {
            calls.add("everyColor");
        }

        @ColorGated
        public void gated() {
            calls.add("gated");
        }

        @OnColorActivated
        public void onActivated(ColorActivatedEvent event) {
            calls.add("activated:" + event.reason());
            gateOpenDuringActivation = gate.isActive();
        }

        @OnColorDeactivated
        public void onDeactivated() {
            calls.add("deactivated");
        }

        public List<String> calls() {
            return calls;
        }

        public Boolean gateOpenDuringActivation() {
            return gateOpenDuringActivation;
        }
    }
}
