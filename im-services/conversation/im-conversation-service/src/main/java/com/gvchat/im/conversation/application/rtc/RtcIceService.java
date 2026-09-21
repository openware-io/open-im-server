package com.gvchat.im.conversation.application.rtc;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.conversation.config.RtcProperties;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RtcIceService {
  private final RtcProperties rtcProperties;

  public List<Map<String, Object>> getIceServers() {
    if (!rtcProperties.enabled()) {
      throw new ApiException(HttpStatusCodes.SERVICE_UNAVAILABLE, "RTC disabled");
    }
    if (isBlank(rtcProperties.turnUrl())
        || isBlank(rtcProperties.turnUsername())
        || isBlank(rtcProperties.turnPassword())) {
      throw new ApiException(HttpStatusCodes.SERVICE_UNAVAILABLE, "RTC ICE configuration unavailable");
    }
    return List.of(Map.of(
        "urls", turnUrls(rtcProperties.turnUrl()),
        "username", rtcProperties.turnUsername(),
        "credential", rtcProperties.turnPassword()));
  }

  private List<String> turnUrls(String value) {
    return List.of(value.split(","))
        .stream()
        .map(String::trim)
        .filter(url -> !url.isEmpty())
        .toList();
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
