package io.openware.platform.admin.infrastructure.security;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.infrastructure.tenant.ClientAudienceHolder;
import io.openware.infrastructure.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** B 端管理接口拒绝带 C 端 clientApp 的 token；内部无 clientApp 调用保持兼容。 */
public class AdminAudienceInterceptor implements HandlerInterceptor {

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String actual = ClientAudienceHolder.get();
    if (actual == null && TenantContextHolder.get() != null) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "CLIENT_AUDIENCE_FORBIDDEN",
          "缺少客户端族声明");
    }
    if (actual != null && !"saas-b".equals(actual)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "CLIENT_AUDIENCE_FORBIDDEN",
          "当前客户端不能访问管理接口");
    }
    return true;
  }
}
