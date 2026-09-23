package io.openware.im.user.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.openware.im.user.application.openplatform.OpenPlatformApplicationService;
import io.openware.im.user.application.openplatform.result.AuthorizeResult;
import io.openware.im.user.application.openplatform.result.ConsentView;
import io.openware.im.user.domain.openplatform.model.AuthorizationRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/** 授权同意页：渲染 scope 勾选、同意跳转携带授权码、拒绝跳转携带 error=access_denied。 */
class OauthConsentControllerTest {

  private final OpenPlatformApplicationService service = mock(OpenPlatformApplicationService.class);
  private final OauthConsentController controller = new OauthConsentController(service);
  @Test
  void consentPage_rendersAppNameAndScopes() {
    when(service.prepareConsent("request-1")).thenReturn(consentView());

    ResponseEntity<String> response = controller.consentPage("request-1");

    assertEquals("text/html", response.getHeaders().getContentType().toString());
    assertTrue(response.getBody().contains("测试应用"));
    assertTrue(response.getBody().contains("profile.basic"));
    assertTrue(response.getBody().contains("同意授权"));
  }

  @Test
  void approve_redirectsWithAuthCode() {
    when(service.approveAuthorization("request-1", List.of("profile.basic"))).thenReturn(
        new AuthorizeResult("gvchat://oauth/callback", "code-123", "st"));

    ResponseEntity<Void> response = controller.approve("request-1", List.of("profile.basic"));

    assertTrue(response.getHeaders().getFirst(HttpHeaders.LOCATION).contains("code=code-123"));
  }

  @Test
  void deny_redirectsWithAccessDenied() {
    when(service.rejectAuthorization("request-1")).thenReturn(new AuthorizationRequest("request-1", "app-1",
        "gvchat://oauth/callback", "profile.basic", "st", "challenge", "S256", 1L,
        System.currentTimeMillis() + 300_000));
    ResponseEntity<Void> response = controller.deny("request-1");

    String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
    assertTrue(location.contains("error=access_denied"));
    assertTrue(location.contains("state=st"));
  }

  private static ConsentView consentView() {
    return new ConsentView("app-1", "测试应用", "gvchat://oauth/callback",
        List.of("profile.basic", "profile.phone"), List.of("profile.basic"), List.of(),
        "st", "challenge", "S256");
  }

}
