package dev.gprometeus.devdocsrag;

import org.springframework.boot.SpringApplication;

public class TestDevdocsRagApplication {

	public static void main(String[] args) {
		SpringApplication.from(DevdocsRagApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
