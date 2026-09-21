package com.gvchat.im.message.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.enums.ChatType;
import com.gvchat.common.enums.UserRole;
import com.gvchat.im.message.api.dto.request.AddFavoritesBatchRequest;
import com.gvchat.im.message.api.dto.response.FavoriteBatchResponse;
import com.gvchat.im.message.api.dto.response.FavoriteSourceResponse;
import com.gvchat.im.message.application.favorite.FavoriteApplicationService;
import com.gvchat.im.message.application.favorite.command.AddFavoritesBatchCommand;
import com.gvchat.im.message.application.favorite.result.FavoriteBatchResult;
import com.gvchat.im.message.application.favorite.result.FavoriteSourceResult;
import com.gvchat.im.message.application.favorite.result.FavoriteSourceState;
import com.gvchat.infrastructure.security.SecurityUser;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 收藏接口契约：批量收藏请求体/响应体与原消息可用性响应体。 */
class FavoriteControllerTest {
  private final FavoriteApplicationService service = mock(FavoriteApplicationService.class);
  private final FavoriteController controller = new FavoriteController(service);
  private final SecurityUser user = new SecurityUser(7L, "u7", "", UserRole.USER, true);

  @Test
  void batchEndpointPassesUserIdPeerChatTypeAndMessageIds() {
    when(service.favoriteBatch(any())).thenReturn(new FavoriteBatchResult(2, 1, List.of(
        new FavoriteBatchResult.Item("m1", true),
        new FavoriteBatchResult.Item("m2", true),
        new FavoriteBatchResult.Item("m3", false))));

    FavoriteBatchResponse response = controller.favoriteBatch(user,
        new AddFavoritesBatchRequest("8", ChatType.PRIVATE, List.of("m1", "m2", "m3")));

    ArgumentCaptor<AddFavoritesBatchCommand> captor = ArgumentCaptor.forClass(AddFavoritesBatchCommand.class);
    verify(service).favoriteBatch(captor.capture());
    assertEquals(7L, captor.getValue().userId());
    assertEquals("8", captor.getValue().peerId());
    assertEquals(ChatType.PRIVATE, captor.getValue().chatType());
    assertEquals(List.of("m1", "m2", "m3"), captor.getValue().messageIds());

    assertEquals(2, response.created());
    assertEquals(1, response.skipped());
    assertEquals(3, response.items().size());
    assertEquals("m1", response.items().get(0).messageId());
    assertTrue(response.items().get(0).created());
    assertFalse(response.items().get(2).created());
  }

  @Test
  void sourceEndpointMapsEveryStateAndOnlyAvailableCarriesIds() {
    for (FavoriteSourceState state : FavoriteSourceState.values()) {
      FavoriteSourceResult result = state == FavoriteSourceState.AVAILABLE
          ? FavoriteSourceResult.available("conv:private:7:8", "m1")
          : FavoriteSourceResult.of(state);
      when(service.locateSource(7L, "m1")).thenReturn(result);

      FavoriteSourceResponse response = controller.source(user, "m1");

      assertEquals(state, response.state());
      if (state == FavoriteSourceState.AVAILABLE) {
        assertEquals("conv:private:7:8", response.conversationId());
        assertEquals("m1", response.messageId());
      } else {
        assertNull(response.conversationId(), state + " 不得携带可跳转的会话 id");
        assertNull(response.messageId(), state + " 不得携带可跳转的消息 id");
      }
    }
    verify(service, atLeastOnce()).locateSource(7L, "m1");
  }
}
