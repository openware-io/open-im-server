package com.gvchat.platform.admin.infra;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gvchat.common.exception.ApiException;
import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 媒体服务客户端的线上行为：图片字节走原始请求体、biz/scope 走查询参数，
 * 且请求携带能通过平台内部 HMAC 校验的签名头（用本机 HTTP 服务端复算签名验证）。
 */
class MediaServiceImageClientTest {

  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  private final AtomicReference<String> seenQuery = new AtomicReference<>();
  private final AtomicReference<byte[]> seenBody = new AtomicReference<>();
  private final AtomicReference<String> seenContentType = new AtomicReference<>();
  private final AtomicReference<String> seenSource = new AtomicReference<>();
  private final AtomicReference<String> seenVersion = new AtomicReference<>();
  private final AtomicReference<Boolean> signatureValid = new AtomicReference<>();

  private HttpServer server;
  private InternalServiceAuthentication authentication;
  private MediaServiceImageClient client;
  private volatile int responseStatus = 200;
  private volatile String responseBody = "";

  @BeforeEach
  void startServer() throws IOException {
    authentication = new InternalServiceAuthentication(authenticationProperties());
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/internal/media/images", exchange -> {
      byte[] body = exchange.getRequestBody().readAllBytes();
      String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
      String source = exchange.getRequestHeaders().getFirst(InternalServiceAuthentication.SOURCE_HEADER);
      seenQuery.set(exchange.getRequestURI().getRawQuery());
      seenBody.set(body);
      seenContentType.set(contentType);
      seenSource.set(source);
      seenVersion.set(exchange.getRequestHeaders().getFirst(InternalServiceAuthentication.VERSION_HEADER));
      signatureValid.set(authentication.isValid(exchange.getRequestMethod(),
          exchange.getRequestURI().getRawPath(), exchange.getRequestURI().getRawQuery(), contentType, body, source,
          exchange.getRequestHeaders().getFirst(InternalServiceAuthentication.REQUEST_ID_HEADER),
          exchange.getRequestHeaders().getFirst(InternalServiceAuthentication.TIMESTAMP_HEADER),
          exchange.getRequestHeaders().getFirst(InternalServiceAuthentication.CONTENT_SHA256_HEADER),
          exchange.getRequestHeaders().getFirst(InternalServiceAuthentication.SIGNATURE_HEADER)));
      byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(responseStatus, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    client = new MediaServiceImageClient("http://127.0.0.1:" + server.getAddress().getPort(),
        new InternalServiceAuthenticationInterceptor(authentication));
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void sendsRawImageBodyWithSignedQueryAndMapsResponse() {
    byte[] image = bytes("png-bytes");
    responseBody = """
        {"url":"/api/v1/media-public/gv-media-public/saas/t100-s7/202609/abc.png",\
        "objectKey":"saas/t100-s7/202609/abc.png","bucket":"gv-media-public","contentType":"image/png","size":9}""";

    MediaServiceImageClient.UploadedImage uploaded = client.upload("saas", "t100-s7", "image/png", image);

    assertEquals("biz=saas&scope=t100-s7", seenQuery.get());
    assertArrayEquals(image, seenBody.get());
    assertEquals("image/png", seenContentType.get());
    assertEquals("platform-admin-service", seenSource.get());
    assertEquals("2", seenVersion.get());
    assertTrue(signatureValid.get(), "内部 HMAC 签名必须覆盖原始图片字节与查询参数");
    assertEquals("/api/v1/media-public/gv-media-public/saas/t100-s7/202609/abc.png", uploaded.url());
    assertEquals("saas/t100-s7/202609/abc.png", uploaded.objectKey());
    assertEquals("image/png", uploaded.contentType());
    assertEquals(9L, uploaded.size());
  }

  @Test
  void omitsBlankScopeAndFallsBackToOctetStreamForUnusableContentType() {
    responseBody = "{\"url\":\"/api/v1/media-public/b/k.png\",\"objectKey\":\"k.png\",\"contentType\":\"image/png\",\"size\":3}";

    client.upload("saas", " ", null, bytes("png"));

    assertEquals("biz=saas", seenQuery.get());
    assertEquals("application/octet-stream", seenContentType.get());
    assertTrue(signatureValid.get());
  }

  @Test
  void passesThroughFourHundredMessageFromMediaService() {
    responseStatus = 400;
    responseBody = "{\"message\":\"图片大小不能超过 10MB\",\"code\":\"MEDIA_TOO_LARGE\"}";

    ApiException error = assertThrows(ApiException.class,
        () -> client.upload("saas", "t100", "image/png", bytes("png")));

    assertEquals(400, error.getStatus());
    assertEquals("MEDIA_TOO_LARGE", error.getCode());
    assertEquals("图片大小不能超过 10MB", error.getMessage());
  }

  @Test
  void mapsServerErrorAndInternalAuthRejectionToServiceUnavailable() {
    responseStatus = 500;
    responseBody = "{\"message\":\"Internal error\"}";
    ApiException serverError = assertThrows(ApiException.class,
        () -> client.upload("saas", "t100", "image/png", bytes("png")));
    assertEquals(503, serverError.getStatus());
    assertEquals("MEDIA_UPLOAD_FAILED", serverError.getCode());
    assertEquals("图片上传失败，请稍后重试", serverError.getMessage());

    responseStatus = 403;
    responseBody = "{\"message\":\"Forbidden\"}";
    ApiException authRejected = assertThrows(ApiException.class,
        () -> client.upload("saas", "t100", "image/png", bytes("png")));
    assertEquals(503, authRejected.getStatus());
    assertEquals("MEDIA_UPLOAD_FAILED", authRejected.getCode());
  }

  @Test
  void mapsConnectionFailureToServiceUnavailable() {
    int closedPort;
    try (ServerSocket socket = new ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
    MediaServiceImageClient offline = new MediaServiceImageClient("http://127.0.0.1:" + closedPort,
        new InternalServiceAuthenticationInterceptor(authentication));

    ApiException error = assertThrows(ApiException.class,
        () -> offline.upload("saas", "t100", "image/png", bytes("png")));

    assertEquals(503, error.getStatus());
    assertEquals("MEDIA_UPLOAD_FAILED", error.getCode());
  }

  private static InternalServiceAuthenticationProperties authenticationProperties() {
    InternalServiceAuthenticationProperties properties = new InternalServiceAuthenticationProperties();
    properties.setServiceName("platform-admin-service");
    properties.setExpectedSource("platform-admin-service");
    properties.setSecret(SECRET);
    return properties;
  }

  private static byte[] bytes(String content) {
    return content.getBytes(StandardCharsets.UTF_8);
  }
}
