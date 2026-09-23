package io.openware.im.message.application.result;

import java.util.List;

public record AdminMessagePageResult(List<MessageResult> items, long total, int page, int pageSize) { }
