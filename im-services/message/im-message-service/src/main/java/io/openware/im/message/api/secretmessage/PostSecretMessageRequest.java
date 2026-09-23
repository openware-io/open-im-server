package io.openware.im.message.api.secretmessage;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PostSecretMessageRequest {
  @NotNull
  private Long secretChatId;
  @NotBlank
  private String msgId;
  @NotBlank
  private String ciphertext;

  /** 非加密媒体对象 id（供服务端绑定媒体引用、接收方重新换取访问 URL；不含内容）。 */
  private List<String> mediaObjectIds;
}
