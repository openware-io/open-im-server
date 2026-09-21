package com.gvchat.im.user.integration;

import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 统一账号模型（SaaS 身份域）清理客户端：调 platform-identity-service 的内部端点
 * {@code DELETE /internal/accounts/im/{username}}。
 *
 * <p>为什么由**本服务**在删除事务内调用：{@code idt_login_identity(login_type='IM', login_identifier='im_<id>')}
 * 是 IM 账号在统一账号模型里的落点，只有身份域能删；而「删 IM 用户」必须整体成功或整体失败，
 * 因此把它作为事务内的**最后一步**发起——远端失败会让本地级联与墓碑化一起回滚，不留半删状态。
 *
 * <p>身份域**不做拒绝**：IM 用户删除是 IM 自己的业务，不受 SaaS 域口径约束；
 * 员工 / 平台运营账号本体保留（只有客户账号成为孤儿时才删），删除后该账号处于「未绑定 IM」，
 * 等后台「换绑 IM 账号」即可。
 */
@Component
@RequiredArgsConstructor
public class UnifiedAccountPurgeClient {

  private final RestClient.Builder restClientBuilder;
  private final InternalServiceAuthenticationInterceptor authenticationInterceptor;

  @Value("${internal.services.identity.base-url:http://platform-identity-service:4100}")
  private String baseUrl;

  /**
   * 清理结果。
   *
   * @param loginIdentities 物理删除的 IM 登录标识行数
   * @param oauthLinks      物理删除的 IM OAuth 绑定行数
   * @param deletedAccount  账号本体是否被删除（只有「客户账号 + 孤儿」才 true；员工/平台运营账号本体保留）
   * @param accountType     涉及的账号类型（EMPLOYEE / CUSTOMER / PLATFORM_OPERATOR；无账号时 null）
   */
  public record PurgeResult(int loginIdentities, int oauthLinks, boolean deletedAccount, String accountType) {

    public static PurgeResult none() {
      return new PurgeResult(0, 0, false, null);
    }
  }

  /**
   * 清理指定 IM 用户名在统一账号模型里的落点（幂等）。
   *
   * <p>调用失败（网络/5xx）抛异常 → 让删除事务整体回滚（IM 侧不留半删状态）。
   */
  public PurgeResult purgeImAccount(String username) {
    try {
      PurgeResult result = client().delete().uri("/internal/accounts/im/{username}", username)
          .retrieve().body(PurgeResult.class);
      return result == null ? PurgeResult.none() : result;
    } catch (RuntimeException exception) {
      throw new IllegalStateException("清理统一账号落点失败", exception);
    }
  }

  private RestClient client() {
    return restClientBuilder.clone().baseUrl(baseUrl).requestInterceptor(authenticationInterceptor).build();
  }
}
