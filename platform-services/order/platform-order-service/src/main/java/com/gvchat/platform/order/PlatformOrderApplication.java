package com.gvchat.platform.order;

import com.gvchat.infrastructure.audit.AuditClientConfig;
import com.gvchat.infrastructure.mq.MqInfrastructureConfig;
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
