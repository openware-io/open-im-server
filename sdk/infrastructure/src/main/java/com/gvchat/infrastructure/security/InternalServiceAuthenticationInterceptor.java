package com.gvchat.infrastructure.security;

import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

@Component
public class InternalServiceAuthenticationInterceptor implements ClientHttpRequestInterceptor {
  private final InternalServiceAuthentication authentication;

  public InternalServiceAuthenticationInterceptor(InternalServiceAuthentication authentication) {
    this.authentication = authentication;
  }

  @Override
  public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
      throws IOException {
    // 所有内部接口（/internal/**）统一加 HMAC 签名，避免 SaaS 域内部端点（/internal/pricing-plans、
    // /internal/resources、/internal/customer/* 等）遗漏白名单导致调用被拒（401）。
    if (request.getURI().getRawPath().startsWith("/internal/")) {
      long timestamp = System.currentTimeMillis();
      String requestId = UUID.randomUUID().toString().replace("-", "");
      String contentHash = authentication.contentHash(body);
      String contentType = request.getHeaders().getFirst("Content-Type");
      request.getHeaders().set(InternalServiceAuthentication.SOURCE_HEADER, authentication.getServiceName());
      request.getHeaders().set(InternalServiceAuthentication.VERSION_HEADER,
          InternalServiceAuthentication.AUTHENTICATION_VERSION);
      request.getHeaders().set(InternalServiceAuthentication.REQUEST_ID_HEADER, requestId);
      request.getHeaders().set(InternalServiceAuthentication.CONTENT_SHA256_HEADER, contentHash);
      request.getHeaders().set(InternalServiceAuthentication.TIMESTAMP_HEADER, Long.toString(timestamp));
      request.getHeaders().set(
          InternalServiceAuthentication.SIGNATURE_HEADER,
          authentication.createSignature(
              request.getMethod(), request.getURI(), contentType, body, authentication.getServiceName(), requestId,
              timestamp));
    }
    return execution.execute(request, body);
  }
}
