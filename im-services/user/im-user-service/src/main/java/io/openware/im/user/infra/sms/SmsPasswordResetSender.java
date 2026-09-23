package io.openware.im.user.infra.sms;

import io.openware.im.user.domain.account.port.PasswordResetSmsSender;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 密码重置短信发送实现：复用 common-sms-service 的 {@code /internal/sms/send} 下发验证码短信。
 *
 * <p>未启用（{@code im.sms.enabled=false}，默认）时不发信，只记录模板与掩码手机号；
 * 启用并配置 {@code internal.services.sms.base-url} 后调用短信服务投递。验证码只出现在短信正文里，
 * 任何情况下都不写日志。发送失败降级为告警日志，不阻塞找回主流程（令牌已写入 Redis，短时有效）。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SmsPasswordResetSender implements PasswordResetSmsSender {
  @Value("${im.sms.enabled:false}")
  private boolean enabled;
  @Value("${im.sms.reset-code-template:}")
  private String templateCode;
  @Value("${im.sms.sign-name:}")
  private String signName;

  private final RestClient.Builder restClientBuilder;
  private final SmsServiceProperties properties;

  @Override
  public void sendVerificationCode(String phone, String code) {
    if (!enabled || properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
      // 禁止把短信验证码写进日志：只记模板与掩码手机号
      log.info("[password-reset] sms disabled, template={} maskedPhone={}", templateCode, mask(phone));
      return;
    }
    try {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("phone", phone);
      body.put("templateCode", templateCode);
      body.put("signName", signName);
      body.put("idempotencyKey", "pwd-reset-" + UUID.randomUUID());
      body.put("provider", "aliyun");
      body.put("params", Map.of("code", code));
      restClientBuilder.clone().baseUrl(properties.getBaseUrl())
          .build()
          .post()
          .uri("/internal/sms/send")
          .body(body)
          .retrieve()
          .toBodilessEntity();
      log.info("[password-reset] sent reset sms to maskedPhone={}", mask(phone));
    } catch (RuntimeException ex) {
      log.warn("[password-reset] failed to send reset sms to maskedPhone={}", mask(phone), ex);
    }
  }

  private static String mask(String phone) {
    if (phone == null || phone.length() < 7) {
      return phone;
    }
    return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
  }
}
