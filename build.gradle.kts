plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "dev.gprometeus"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

extra["springAiVersion"] = "2.0.1"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.ai:spring-ai-starter-model-ollama")
	// Rust HF tokenizers via JNI: counts tokens exactly as the bge-m3 embedding model does
	implementation("ai.djl.huggingface:tokenizers:0.39.0")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.springframework.ai:spring-ai-spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-ollama")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
	}
}

// JNA loads the native DJL tokenizer via System.load, a restricted method since JDK 24;
// without this flag the JVM prints a warning now and will block the call in a future release
val nativeAccess = "--enable-native-access=ALL-UNNAMED"

tasks.bootRun {
	jvmArgs(nativeAccess)
}

tasks.bootJar {
	// Same permission for java -jar: the JVM reads it from the executable JAR manifest
	manifest.attributes("Enable-Native-Access" to "ALL-UNNAMED")
}

tasks.withType<Test> {
	useJUnitPlatform()
	jvmArgs(nativeAccess)
	testLogging {
		// CI=true is set by GitHub Actions, GitLab CI and most other CI servers; there passed tests would flood the log
		val ci = System.getenv("CI") != null
		if (ci) events("skipped", "failed") else events("passed", "skipped", "failed")
		// Full assertion message with the expected/actual diff, not just the first line
		exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
	}
}
