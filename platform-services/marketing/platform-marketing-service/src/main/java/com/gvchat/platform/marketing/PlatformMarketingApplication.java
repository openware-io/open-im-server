package com.gvchat.platform.marketing;

import com.gvchat.infrastructure.mq.MqInfrastructureConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@Import({MqInfrastructureConfig.class})
public class PlatformMarketingApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformMarketingApplication.class, args);
    }
}
