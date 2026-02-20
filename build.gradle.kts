plugins {
	java
	id("org.springframework.boot") version "4.0.3"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.platform"
version = "0.0.1-SNAPSHOT"
description = "Subscription manager"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["springModulithVersion"] = "2.0.2"

dependencies {
	// Basic + Web
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	// Modulith
	implementation("org.springframework.modulith:spring-modulith-starter-core")
	implementation("org.springframework.modulith:spring-modulith-events-api")
	implementation("org.springframework.modulith:spring-modulith-starter-jpa")
	// Test
	testImplementation("org.springframework.modulith:spring-modulith-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.kafka:spring-kafka-test")
	testCompileOnly("org.projectlombok:lombok")
	// Observability
	runtimeOnly("org.springframework.modulith:spring-modulith-actuator")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	runtimeOnly("org.springframework.modulith:spring-modulith-observability")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	// DB + Spring Data JPA
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	runtimeOnly("org.postgresql:postgresql")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	// Lombok
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
	// ShedLock (Distributed Lock para o Scheduler)
	implementation("net.javacrumbs.shedlock:shedlock-spring:5.13.0")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.13.0")
	// Kafka
	implementation("org.springframework.kafka:spring-kafka")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
