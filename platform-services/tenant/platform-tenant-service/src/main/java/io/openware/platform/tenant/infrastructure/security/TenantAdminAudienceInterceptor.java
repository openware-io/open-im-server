package io.openware.platform.tenant.infrastructure.security;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.infrastructure.tenant.ClientAudienceHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/** tenant-service /admin/** 只接受 B 端客户端；无 clientApp 的内部调用保持兼容。 */
public class TenantAdminAudienceInterceptor implements HandlerInterceptor {

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String actual = ClientAudienceHolder.get();
    if (actual != null && !"saas-b".equals(actual)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "CLIENT_AUDIENCE_FORBIDDEN",
          "当前客户端不能访问租户管理接口");
    }
    return true;
  }
}
