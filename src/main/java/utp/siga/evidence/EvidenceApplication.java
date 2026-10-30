package utp.siga.evidence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class EvidenceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EvidenceApplication.class, args);
    }
}
