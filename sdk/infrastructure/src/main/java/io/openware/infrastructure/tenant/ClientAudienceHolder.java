package io.openware.infrastructure.tenant;

/** 当前请求的客户端族，来自已验签 tenant context token 的 clientApp claim。 */
public final class ClientAudienceHolder {
  private static final ThreadLocal<String> CLIENT_APP = new ThreadLocal<>();

  private ClientAudienceHolder() {
  }

  public static void set(String clientApp) {
    CLIENT_APP.set(clientApp);
  }

  public static String get() {
    return CLIENT_APP.get();
  }

  public static void clear() {
    CLIENT_APP.remove();
  }
}
