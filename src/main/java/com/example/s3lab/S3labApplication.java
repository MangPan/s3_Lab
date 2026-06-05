package com.example.s3lab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling // ** Scheduling 활성화 **
@SpringBootApplication 
public class S3labApplication {
	public static void main(String[] args) {
		SpringApplication.run(S3labApplication.class, args);
	}

}
