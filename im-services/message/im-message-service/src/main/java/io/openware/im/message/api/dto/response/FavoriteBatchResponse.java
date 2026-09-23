package io.openware.im.message.api.dto.response;

import java.util.List;

public record FavoriteBatchResponse(int created, int skipped, List<FavoriteBatchItemResponse> items) { }
