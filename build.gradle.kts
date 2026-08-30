plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.3.21"
}

group = "com.sesac"
version = "0.0.1-SNAPSHOT"
description = "SeSAC Speech App Backend"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	// Spring Boot Starters
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springframework.boot:spring-boot-starter-security")

	// Kotlin
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

	// Firebase Admin SDK
	implementation("com.google.firebase:firebase-admin:9.9.0")

	// google-http-client 버전 강제 (GZIP 디코딩 버그 회피)
	implementation("com.google.http-client:google-http-client:1.44.2")

	// JWT (jjwt)
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

	// Database
	runtimeOnly("com.h2database:h2")
	runtimeOnly("com.oracle.database.jdbc:ojdbc11")  // Oracle XE (dev/prod)



	// OCI Object Storage SDK — 버전은 oci-java-sdk-common(코어)이 전이적으로 일괄 관리
	implementation("com.oracle.oci.sdk:oci-java-sdk-objectstorage:3.95.0")
	implementation("com.oracle.oci.sdk:oci-java-sdk-common-httpclient-jersey3:3.95.0")

	// 파일 업로드 크기 제한 검증용
	implementation("org.springframework.boot:spring-boot-starter-validation")

	// DevTools
	developmentOnly("org.springframework.boot:spring-boot-devtools")

	// Test
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
	useJUnitPlatform()
	// -Docitest=true 로 실행한 경우에만 수동 OCI 테스트가 활성화되도록 시스템 프로퍼티 전달
	systemProperty("ocitest", System.getProperty("ocitest"))
}