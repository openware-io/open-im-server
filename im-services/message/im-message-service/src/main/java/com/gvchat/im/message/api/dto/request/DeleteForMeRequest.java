package com.gvchat.im.message.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** 「删除仅我」请求：一次可删除多条消息，仅影响当前用户。 */
public record DeleteForMeRequest(@NotEmpty List<String> msgIds) {
}
