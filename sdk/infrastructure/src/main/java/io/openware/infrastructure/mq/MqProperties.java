package io.openware.infrastructure.mq;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "im.mq")
public class MqProperties {
  private MqClientType type = MqClientType.REMOTING;
  private String namesrvAddr;
  private String producerGroup = "open_im_default_producer";
  private String consumerGroupPrefix = "open_im";
  private String accessKey;
  private String secretKey;
}
