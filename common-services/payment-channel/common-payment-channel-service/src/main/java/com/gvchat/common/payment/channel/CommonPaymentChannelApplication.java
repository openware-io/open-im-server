package com.gvchat.common.payment.channel;

import com.gvchat.common.payment.channel.infra.config.PayChannelProviderProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(PayChannelProviderProperties.class)
public class CommonPaymentChannelApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommonPaymentChannelApplication.class, args);
    }
}
