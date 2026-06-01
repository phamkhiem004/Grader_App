package com.example.grader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class GraderApplication {

	public static void main(String[] args) {
		SpringApplication.run(GraderApplication.class, args);
	}

}
