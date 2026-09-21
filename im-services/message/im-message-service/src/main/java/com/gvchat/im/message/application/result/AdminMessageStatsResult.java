package com.gvchat.im.message.application.result;

import java.util.List;
import java.util.Map;

public record AdminMessageStatsResult(long messageCount, long newMessages, List<Map<String, Object>> dailySeries,
    long activeUsers) { }
