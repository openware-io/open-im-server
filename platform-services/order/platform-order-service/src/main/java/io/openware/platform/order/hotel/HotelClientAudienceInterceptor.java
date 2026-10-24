package io.openware.platform.order.hotel;

import io.openware.common.exception.ApiException;
import io.openware.common.http.HttpStatusCodes;
import io.openware.infrastructure.tenant.ClientAudience;
import io.openware.infrastructure.tenant.ClientAudienceHolder;
import io.openware.infrastructure.tenant.TenantContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 酒店 API 的 C/B 客户端族强制校验；claim 缺失或跨族一律 403。 */
public class HotelClientAudienceInterceptor implements HandlerInterceptor {

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    ClientAudience required = method.getMethodAnnotation(ClientAudience.class);
    if (required == null) {
      required = method.getBeanType().getAnnotation(ClientAudience.class);
    }
    String expected = required == null ? null : required.value();
    if (expected == null && request.getRequestURI().startsWith("/admin/")) {
      expected = "saas-b";
    }
    if (expected == null) {
      return true;
    }
    String actual = ClientAudienceHolder.get();
    if (actual == null && TenantContextHolder.get() != null) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "CLIENT_AUDIENCE_FORBIDDEN",
          "缺少客户端族声明");
    }
    if (actual != null && !expected.equals(actual)) {
      throw new ApiException(HttpStatusCodes.FORBIDDEN, "CLIENT_AUDIENCE_FORBIDDEN",
          "当前客户端不能访问该酒店接口");
    }
    return true;
  }
}
