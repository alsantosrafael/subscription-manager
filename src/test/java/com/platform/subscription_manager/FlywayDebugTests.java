package com.platform.subscription_manager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FlywayDebugTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoadsAndFlywayExists() {
        assertThat(applicationContext.containsBean("flyway")).isTrue();
        Flyway flyway = applicationContext.getBean(Flyway.class);
        System.out.println("Flyway bean found: " + flyway);
        System.out.println("Flyway baseline on migrate: " + flyway.getConfiguration().isBaselineOnMigrate());
        System.out.println("Flyway locations: " + java.util.Arrays.toString(flyway.getConfiguration().getLocations()));
    }
}
