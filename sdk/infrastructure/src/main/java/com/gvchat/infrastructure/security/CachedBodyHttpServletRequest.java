package com.gvchat.infrastructure.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 请求体可重复读取的包装器：内部服务鉴权需要在过滤器里读到 body 做内容哈希与 HMAC 校验，
 * 校验通过后控制器仍要能解析同一份 JSON。
 *
 * <p>公开可见，供各服务自行注册内部端点鉴权过滤器时复用（避免每个服务再抄一份包装器）。
 */
public final class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {
  private final byte[] body;

  public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
    super(request);
    body = request.getInputStream().readAllBytes();
  }

  public byte[] getCachedBody() {
    return body.clone();
  }

  @Override
  public ServletInputStream getInputStream() {
    ByteArrayInputStream input = new ByteArrayInputStream(body);
    return new ServletInputStream() {
      @Override
      public int read() {
        return input.read();
      }

      @Override
      public boolean isFinished() {
        return input.available() == 0;
      }

      @Override
      public boolean isReady() {
        return true;
      }

      @Override
      public void setReadListener(ReadListener readListener) {
        throw new UnsupportedOperationException("Async request body reading is not supported");
      }
    };
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
  }
}
