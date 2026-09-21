package com.gvchat.im.message.api.dto.response;

import java.util.List;

public record FavoritePageResponse(List<FavoriteResponse> items, long total, int page, int pageSize) { }
