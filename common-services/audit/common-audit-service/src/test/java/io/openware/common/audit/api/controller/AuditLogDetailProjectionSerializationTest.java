package io.openware.common.audit.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.openware.common.audit.api.dto.AuditLogView;
import io.openware.common.audit.api.dto.AuditPageView;
import io.openware.common.audit.application.service.AuditQueryApplicationService;
import java.time.LocalDateTime;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * 回归：审计查询响应体里的 {@code detailJson} 必须是**业务 JSON 文本**，不能退化成 Jackson 元数据。
 *
 * <p>真机验收实测的线上缺陷：{@code GET /admin/audit-logs} 的 {@code detailJson} 整体是
 * <pre>
 * {"array":false,"bigDecimal":false,...,"nodeType":"OBJECT","object":true,"pojo":false,"textual":false,
 *  "valueNode":false}
 * </pre>
 * ——本工程是 Spring Boot 4 / Spring Framework 7，HTTP 消息转换器是 Jackson 3 的
 * {@link JacksonJsonHttpMessageConverter}（{@code tools.jackson.*}）；Jackson 2 的
 * {@code com.fasterxml.jackson.databind.JsonNode} 在 Jackson 3 眼里只是一个普通 POJO，于是被
 * BeanSerializer 序列化成上面那串「节点类型探测」方法，真实 detail（例如
 * {@code {"orderId":76,"status":"ACTIVE"}}）整段丢失，前端「详情」列变成一串 false/true。
 *
 * <p>因此本用例刻意用**与运行期同一套转换器**（Jackson 3 {@code JsonMapper} + Spring 的
 * {@code JacksonJsonHttpMessageConverter}）走一遍 MockMvc，断言：
 * <ol>
 *   <li>{@code detailJson} 在响应体里是字符串（而不是对象），且内容可被前端 {@code JSON.parse}
 *       还原成原始业务对象；</li>
 *   <li>对象 / 非 JSON 纯文本 / 缺失三种形态都不 500，且各自降级口径正确。</li>
 * </ol>
 *
 * <p>本模块没有 DB 集成测试基建，这里用 Mockito 替身提供 {@link AuditPageView}，只锁投影形状。
 */
class AuditLogDetailProjectionSerializationTest {

  private static final String ORDER_DETAIL = "{\"orderId\":76,\"status\":\"ACTIVE\"}";

  private final AuditQueryApplicationService queryService = mock(AuditQueryApplicationService.class);
  private final MockMvc mockMvc = MockMvcBuilders
      .standaloneSetup(new AuditController(queryService))
      .setMessageConverters(jackson3())
      .build();

  /**
   * 与运行期同源的 Jackson 3 消息转换器：{@code tools.jackson.databind.json.JsonMapper} +
   * Spring Framework 7 的 {@code JacksonJsonHttpMessageConverter}。
   *
   * <p>{@code findAndAddModules()}：用 classpath 上发现的模块（含 Spring Boot 注册的 Jackson 2
   * 注解兼容模块）构建，尽量贴近自动配置出来的那个 {@code JsonMapper} Bean。
   */
  private static JacksonJsonHttpMessageConverter jackson3() {
    JsonMapper mapper = JsonMapper.builder()
        .findAndAddModules()
        .build();
    return new JacksonJsonHttpMessageConverter(mapper);
  }

  /** 核心断言：detailJson 是业务 JSON 文本，而不是 Jackson 的节点类型元数据。 */
  @Test
  void detailJsonIsBusinessJsonTextNotJacksonNodeMetadata() throws Exception {
    whenPage(ORDER_DETAIL);

    mockMvc.perform(get("/admin/audits").param("page", "1").param("pageSize", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].detailJson", Matchers.instanceOf(String.class)))
        .andExpect(jsonPath("$.items[0].detailJson").value(ORDER_DETAIL))
        // 元数据一旦回归，这两个探测字段就会出现在响应体里。
        .andExpect(jsonPath("$.items[0].detailJson.nodeType").doesNotExist())
        .andExpect(jsonPath("$.items[0].detailJson.valueNode").doesNotExist());
  }

  /**
   * 缺陷机制的「正向复现」：把 Jackson 2 的 {@code JsonNode} 放进响应对象，用**同一个**
   * Jackson 3 转换器序列化，得到的正是线上那串元数据。
   *
   * <p>本用例不测生产代码，而是把「为什么 detailJson 不能声明成 JsonNode」钉在测试里：
   * 有了它，即使将来有人把 DTO 改回 {@code JsonNode}，上面那条断言和这条机制说明会一起指出原因，
   * 而不是只在真机上表现为「详情列全是 false/true」。
   */
  @Test
  void jackson2JsonNodeUnderJackson3ConverterBecomesMetadata() throws Exception {
    record NodeProbe(String name, com.fasterxml.jackson.databind.JsonNode detailJson) {
    }

    NodeProbe probe = new NodeProbe("probe",
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(ORDER_DETAIL));
    MockMvc probeMvc = MockMvcBuilders
        .standaloneSetup(new ProbeController(probe))
        .setMessageConverters(jackson3())
        .build();

    probeMvc.perform(get("/probe"))
        .andExpect(status().isOk())
        // 线上实测响应体里就是这些字段（nodeType/object/pojo/valueNode …），业务内容整段丢失。
        .andExpect(content().string(Matchers.containsString("\"nodeType\":\"OBJECT\"")))
        .andExpect(content().string(Matchers.containsString("\"valueNode\":false")))
        .andExpect(content().string(Matchers.containsString("\"containerNode\":true")))
        .andExpect(content().string(Matchers.not(Matchers.containsString("\"orderId\""))));
  }

  /** 只为上面那条「机制复现」用的最小控制器（返回一个含 JsonNode 字段的对象）。 */
  @RestController
  static class ProbeController {
    private final Object payload;

    ProbeController(Object payload) {
      this.payload = payload;
    }

    @GetMapping("/probe")
    public Object probe() {
      return payload;
    }
  }

  /** 缺失 → null（前端显示「-」），不是 500，也不是空对象。 */
  @Test
  void missingDetailSerializesAsNullWithoutFailing() throws Exception {
    whenPage(null);

    mockMvc.perform(get("/admin/audits").param("page", "1").param("pageSize", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].detailJson").doesNotExist());
  }

  /**
   * 历史脏数据（非 JSON 纯文本）→ 仍是合法 JSON 文本，原文不丢。
   *
   * <p>服务层把原文本 {@code legacy-text-not-json} 包成 JSON 字符串字面量
   * {@code "legacy-text-not-json"}（含引号），响应体里它就是一段可被前端
   * {@code JSON.parse} 出原字符串的合法 JSON —— 直接断言序列化后的字节形状。
   */
  @Test
  void nonJsonTextFallsBackToQuotedJsonString() throws Exception {
    whenPage("\"legacy-text-not-json\"");

    mockMvc.perform(get("/admin/audits").param("page", "1").param("pageSize", "1"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString(
            "\"detailJson\":\"\\\"legacy-text-not-json\\\"\"")));
  }

  private void whenPage(String detailJson) {
    AuditLogView item = new AuditLogView(9L, 100L, "A380", null, null, 1L, "管理员", "admin", "PLATFORM",
        "order.item.confirm", "订单加项确认", "ord_order_item", "76", "DSH-1", "SUCCESS", null, null, null,
        "req-1", null, "open-im-audit-reporter", detailJson,
        LocalDateTime.of(2026, 9, 20, 13, 6, 11), LocalDateTime.of(2026, 9, 20, 13, 6, 11));
    when(queryService.list(any())).thenReturn(new AuditPageView("PLATFORM", null, 1, 1, 1L, 1, false,
        null, List.of(item)));
    when(queryService.detail(anyLong())).thenReturn(item);
  }
}
