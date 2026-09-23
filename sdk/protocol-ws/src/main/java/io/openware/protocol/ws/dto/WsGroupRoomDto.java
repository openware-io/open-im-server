package io.openware.protocol.ws.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * WebSocket 群房间加入/离开事件载荷（{@code group:join}/{@code group:leave}）。
 *
 * <p>用于客户端与服务端协商进入/退出指定群的广播房间。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WsGroupRoomDto {
  @NotNull
  private Long groupId;
}

