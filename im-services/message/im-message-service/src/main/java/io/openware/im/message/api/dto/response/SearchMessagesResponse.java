package io.openware.im.message.api.dto.response;

import java.util.List;

public record SearchMessagesResponse(List<MessageResponse> items, long total, int page, int pageSize) { }
