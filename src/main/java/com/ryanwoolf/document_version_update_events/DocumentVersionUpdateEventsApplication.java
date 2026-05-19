package com.ryanwoolf.document_version_update_events;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jms.annotation.EnableJms;

@EnableJms
@SpringBootApplication
public class DocumentVersionUpdateEventsApplication {

	public static void main(String[] args) {
		SpringApplication.run(DocumentVersionUpdateEventsApplication.class, args);
	}

}
