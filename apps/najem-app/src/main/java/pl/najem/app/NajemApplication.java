package pl.najem.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = "pl.najem")
@EnableScheduling
public class NajemApplication {

    public static void main(String[] args) {
        SpringApplication.run(NajemApplication.class, args);
    }
}
