package dev.gprometeus.devdocsrag;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Containers for tests and for {@link TestDevdocsRagApplication}.
 * <p>
 * No Ollama container: startup never calls the model, and an empty container without pulled
 * models could not serve requests anyway. The native Ollama from application.yml is used instead.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// Same image as docker-compose.yml: the V1 migration needs the pgvector extension
		return new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:0.8.7-pg18")
				.asCompatibleSubstituteFor("postgres"));
	}

}
