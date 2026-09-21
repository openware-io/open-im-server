package com.gvchat.im.admin.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.gvchat.im.admin.integration.InternalServiceProperties;
import com.gvchat.common.dto.AdminListFriendsDto;
import com.gvchat.common.dto.AdminListUsersDto;
import com.gvchat.common.enums.FriendStatus;
import com.gvchat.common.enums.UserStatus;
import com.gvchat.infrastructure.security.InternalServiceAuthentication;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationInterceptor;
import com.gvchat.infrastructure.security.InternalServiceAuthenticationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AdminReadClientTest {
  private MockRestServiceServer server;
  private AdminReadClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder();
    server = MockRestServiceServer.bindTo(builder).build();
    InternalServiceProperties properties = new InternalServiceProperties();
    properties.setBaseUrl("http://im-user-service");
    InternalServiceAuthenticationProperties authenticationProperties = new InternalServiceAuthenticationProperties();
    authenticationProperties.setServiceName("im-admin-service");
    authenticationProperties.setExpectedSource("im-admin-service");
    authenticationProperties.setSecret("test-internal-service-authentication-secret");
    client = new AdminReadClient(builder, properties,
        new InternalServiceAuthenticationInterceptor(new InternalServiceAuthentication(authenticationProperties)));
  }

  @Test
  void listFriendsShouldForwardAllFilters() {
    AdminListFriendsDto query = AdminListFriendsDto.builder().keyword("xiaocaihong6").status(FriendStatus.NORMAL)
        .groupName("colleagues").page(2).pageSize(50).build();
    server.expect(once(), requestTo("http://im-user-service/internal/admin/users/friends?page=2&pageSize=50&keyword=xiaocaihong6&status=NORMAL&groupName=colleagues"))
        .andExpect(method(HttpMethod.GET)).andExpect(queryParam("keyword", "xiaocaihong6"))
        .andExpect(queryParam("status", "NORMAL")).andExpect(queryParam("groupName", "colleagues"))
        .andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":2,\"pageSize\":50}", MediaType.APPLICATION_JSON));

    assertThat(client.listFriends(query).getPage()).isEqualTo(2);
    server.verify();
  }

  @Test
  void listUsersShouldForwardAllFilters() {
    AdminListUsersDto query = AdminListUsersDto.builder().username("admin").keyword("administrator")
        .status(UserStatus.ACTIVE).page(2).pageSize(50).build();
    server.expect(once(), requestTo("http://im-user-service/internal/admin/users?page=2&pageSize=50&username=admin&keyword=administrator&status=ACTIVE"))
        .andExpect(method(HttpMethod.GET)).andExpect(queryParam("username", "admin"))
        .andExpect(queryParam("keyword", "administrator")).andExpect(queryParam("status", "ACTIVE"))
        .andRespond(withSuccess("{\"items\":[],\"total\":0,\"page\":2,\"pageSize\":50}", MediaType.APPLICATION_JSON));

    assertThat(client.listUsers(query).getPage()).isEqualTo(2);
    server.verify();
  }

  @Test
  void deleteUserShouldForwardIdOperatorAndConfirmUsername() {
    server.expect(once(), requestTo("http://im-user-service/internal/admin/users/71?operatorId=1&confirmUsername=im_71"))
        .andExpect(method(HttpMethod.DELETE))
        .andExpect(queryParam("operatorId", "1"))
        .andExpect(queryParam("confirmUsername", "im_71"))
        .andRespond(withSuccess("{\"deleted\":true,\"username\":\"im_71\","
            + "\"tombstoneUsername\":\"deleted_71_ab12cd34\",\"messagesPreserved\":true,"
            + "\"cascade\":{\"deviceTokens\":6,\"deviceSessions\":2,\"deviceKeys\":1,\"notificationSettings\":1,"
            + "\"securityQuestions\":1,\"favorites\":3,\"friendRelations\":4,\"friendRequests\":1,"
            + "\"groupMembers\":5,\"channelSubscriptions\":0,\"secretChats\":0,\"secretGroupMembers\":0,"
            + "\"stickers\":0,\"privacySettings\":1,\"statusOperations\":0,\"loginIdentities\":1,"
            + "\"oauthLinks\":1,\"unifiedAccounts\":0,\"unifiedAccountRetained\":true,"
            + "\"unifiedAccountType\":\"EMPLOYEE\",\"account\":1}}", MediaType.APPLICATION_JSON));

    var response = client.deleteUser(71L, 1L, "im_71");

    assertThat(response.deleted()).isTrue();
    assertThat(response.username()).isEqualTo("im_71");
    assertThat(response.tombstoneUsername()).startsWith("deleted_71_");
    assertThat(response.messagesPreserved()).isTrue();
    assertThat(response.cascade().deviceSessions()).isEqualTo(2);
    assertThat(response.cascade().groupMembers()).isEqualTo(5);
    assertThat(response.cascade().loginIdentities()).isEqualTo(1);
    assertThat(response.cascade().unifiedAccounts()).isZero();
    assertThat(response.cascade().unifiedAccountRetained()).isTrue();
    assertThat(response.cascade().unifiedAccountType()).isEqualTo("EMPLOYEE");
    server.verify();
  }
}
