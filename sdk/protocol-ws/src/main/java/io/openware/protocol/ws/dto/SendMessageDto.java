package io.openware.protocol.ws.dto;

import io.openware.common.enums.ChatType;
import io.openware.common.enums.MsgType;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * WebSocket 发送消息事件载荷（{@code chat:send}）。
 *
 * <p>服务端收到该事件后，会根据会话类型与消息类型做合法性校验与投递，并将结果广播给会话参与方。</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SendMessageDto {
  @NotBlank
  private String toId;
  @NotNull
  private ChatType chatType;
  @NotNull
  private MsgType msgType;
  private String content;
  private String clientMsgId;
  private String replyMsgId;
  private List<String> atUsers;
  private List<String> mediaObjectIds;
}

