package com.fitpilot.infrastructure.performance;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PerformanceConfigTest {
    @Test
    void applicationDefaultsBoundDisplayStalenessUsingWriteExpiration() throws IOException {
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        var properties = Binder.get(environment).bind("fitpilot.performance", PerformanceProperties.class).get();
        var cache = new PerformanceConfig().hotDataLocalCache(properties);

        assertThat(cache.policy().expireAfterWrite().orElseThrow().getExpiresAfter()).isEqualTo(Duration.ofSeconds(5));
        assertThat(cache.policy().expireAfterAccess()).isEmpty();
        assertThat(properties.cache().l2TtlSeconds()).isEqualTo(30);
        assertThat(properties.cache().l2TtlJitterSeconds()).isEqualTo(5);
    }
}
