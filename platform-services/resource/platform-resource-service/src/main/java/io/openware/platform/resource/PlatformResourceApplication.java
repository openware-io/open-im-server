package io.openware.platform.resource;

import io.openware.infrastructure.audit.AuditClientConfig;
import io.openware.infrastructure.mq.MqInfrastructureConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@Import({AuditClientConfig.class, MqInfrastructureConfig.class})
public class PlatformResourceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformResourceApplication.class, args);
    }
}
