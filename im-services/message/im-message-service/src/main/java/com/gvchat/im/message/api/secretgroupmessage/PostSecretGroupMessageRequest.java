package com.gvchat.im.message.api.secretgroupmessage;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PostSecretGroupMessageRequest {
  @NotNull
  private Long secretGroupId;

  @NotBlank
  private String msgId;

  @NotEmpty
  @Valid
  private List<RecipientRequest> recipients;

  /** 非加密媒体对象 id（供服务端绑定媒体引用、接收方重新换取访问 URL；不含内容）。 */
  private List<String> mediaObjectIds;

  /** 被 @ 提及的成员 userId（明文元数据，供通知路由与「有人@我」角标；不含内容）。 */
  private List<Long> atUserIds;

  @Getter
  @Setter
  public static class RecipientRequest {
    @NotNull
    private Long userId;

    @NotBlank
    private String ciphertext;
  }
}
