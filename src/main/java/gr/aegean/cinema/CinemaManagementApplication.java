package gr.aegean.cinema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Σημείο εκκίνησης (entry point) της εφαρμογής Spring Boot. Εκκινεί τον
 * ενσωματωμένο Tomcat server (default port 8080) και αρχικοποιεί όλο το
 * Spring context (repositories, services, controllers, interceptors).
 */
@SpringBootApplication
public class CinemaManagementApplication {
    public static void main(String[] args) {
        SpringApplication.run(CinemaManagementApplication.class, args);
    }
}
