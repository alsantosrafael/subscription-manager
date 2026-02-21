package com.platform.subscription_manager;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=" +
		"org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration," +
		"org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration," +
		"org.springframework.boot.orm.jpa.autoconfigure.HibernateJpaAutoConfiguration," +
		"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration," +
		"org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
})
class SubscriptionManagerApplicationTests {

	@Test
	void contextLoads() {
	}
}
