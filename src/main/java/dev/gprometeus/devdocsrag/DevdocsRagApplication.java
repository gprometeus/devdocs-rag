package dev.gprometeus.devdocsrag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DevdocsRagApplication {

	public static void main(String[] args) {
		SpringApplication.run(DevdocsRagApplication.class, args);
	}

}
