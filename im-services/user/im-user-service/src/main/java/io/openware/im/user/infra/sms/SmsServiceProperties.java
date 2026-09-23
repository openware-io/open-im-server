package io.openware.im.user.infra.sms;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 短信服务内部地址配置（用户服务复用 common-sms-service 下发校验码短信）。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "internal.services.sms")
public class SmsServiceProperties {
  private String baseUrl;
}
