package io.openware.im.conversation.controller;

import java.util.List;
import io.openware.im.conversation.application.rtc.RtcIceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 实时音视频（RTC）辅助接口。
 * <p>
 */
@RestController
@Tag(name = "实时音视频")
@RequestMapping("/rtc")
@RequiredArgsConstructor
public class RtcController {
private final RtcIceService rtcIceService;

  /**
   *
   */
  @GetMapping("/ice-servers")
  public List<Map<String, Object>> getIceServers() {
    return rtcIceService.getIceServers();
  }
}

