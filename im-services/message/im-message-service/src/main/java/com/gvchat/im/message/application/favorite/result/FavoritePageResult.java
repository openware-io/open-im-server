package com.gvchat.im.message.application.favorite.result;

import java.util.List;

public record FavoritePageResult(List<FavoriteResult> items, long total, int page, int pageSize) { }
