package com.platform.subscription_manager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;

@EnableKafka
@SpringBootApplication
public class SubscriptionManagerApplication {

	public static void main(String[] args) {
		SpringApplication.run(SubscriptionManagerApplication.class, args);
	}

}
