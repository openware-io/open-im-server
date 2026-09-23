package io.openware.platform.order;

import io.openware.infrastructure.audit.AuditClientConfig;
import io.openware.infrastructure.mq.MqInfrastructureConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@Import({AuditClientConfig.class, MqInfrastructureConfig.class})
public class PlatformOrderApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformOrderApplication.class, args);
    }
}
