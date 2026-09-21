package com.gvchat.common.payment.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code pay_collect.response_json} 的只读解析器：抽出**不落 pay_intent** 的组合收款分腿
 * （储值币 {@code WALLET} / 积分 {@code POINT}），供营业日结作为独立分项统计。
 *
 * <p><b>真实 JSON 结构（由 {@link CollectApplicationService.CollectResult} 经 Jackson 序列化，字段名稳定）</b>：
 * <pre>
 * {"remainingAmount":0,"collectedByMethod":[{"method":"POINT","amount":2000},
 *                                          {"method":"WALLET","amount":3000},
 *                                          {"method":"CASH","amount":5000}]}
 * </pre>
 * 其中 {@code method} 取值来自请求分腿的支付方式/支付工具码
 * （{@code CASH/WALLET/POINT/ALIPAY/WECHAT/STRIPE}），与 {@link CollectApplicationService} 里
 * {@code methodOf(...)} 的判定、以及 {@code CollectApplicationService#orderCollected} 里
 * {@code byMethod.getOrDefault("WALLET")}/{@code getOrDefault("POINT")} 的既有口径完全一致
 * ——积分分腿的码是 <b>{@code POINT}</b>（不是 {@code POINTS}）。
 *
 * <p><b>这个 {@code amount} 是什么</b>：它是**该腿抵扣的账单金额**（最小货币单位整数，
 * 币种 = 本次收款的币种快照 / 订单币种）。储值币与积分是**组合支付里的支付工具，不是货币**
 * （只有现金与价格才带币种），因此这里**只**把它当货币金额（账单抵扣额）输出，
 * 绝不把它当「代币/积分数量」展示、套币种或参与货币换算。
 *
 * <p><b>为什么没有数量字段</b>：{@code response_json} 的每条分腿只有 {@code method} + 一个数值，
 * <b>没有</b>独立的代币/积分数量字段（扣减数量在 customer 域账本，
 * {@code deductWallet(amount)} / {@code redeemPoints(points)} 与这里的金额同源同值）。
 * 所以本解析器不提供、也不按比例反推任何数量；将来若在 {@code pay_collect} 落库独立数量列，
 * 应以 {@code walletTokens}/{@code points} 这类非 {@code *Amount} 命名新增，
 * 且**不带 {@code currencyCode}、不参与任何货币合计**。
 *
 * <p><b>容错（历史数据不回归）</b>：{@code null}/空白、JSON 非法、缺 {@code collectedByMethod}、
 * 分腿缺 {@code method}/{@code amount}、金额为负数或非数字，一律**跳过**该行/该分腿并计 0，绝不抛错：
 * 一行历史或损坏数据不能让整个营业日结提交失败（与 {@link DailyClosingSummary#parse} 同一降级口径）。
 */
public final class CollectResponseLegs {

  /** 储值币分腿的支付工具码（与请求分腿、pay_intent 之外的跨域扣减口径一致）。 */
  public static final String PROVIDER_WALLET = "WALLET";

  /** 积分分腿的支付工具码（与请求分腿口径一致，是 {@code POINT} 而非 {@code POINTS}）。 */
  public static final String PROVIDER_POINTS = "POINT";

  private static final String FIELD_LEGS = "collectedByMethod";
  private static final String FIELD_METHOD = "method";
  private static final String FIELD_AMOUNT = "amount";

  private static final ObjectMapper MAPPER = new ObjectMapper()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

  private CollectResponseLegs() { }

  /**
   * 一条储值币/积分分腿。
   *
   * @param method 支付工具码（{@code WALLET}/{@code POINT}）
   * @param amount **抵扣的账单金额**（货币，最小货币单位整数；不是代币/积分数量）
   */
  public record WalletPointLeg(String method, long amount) { }

  /**
   * 抽出储值币/积分分腿的**抵扣账单金额**：只返回 {@code WALLET}/{@code POINT} 且金额 &gt; 0 的分腿。
   *
   * <p>现金类分腿（CASH/ALIPAY/WECHAT/STRIPE）已由 {@code pay_intent} 计入
   * {@code collectedAmount}/{@code cashAmount}，这里**必须排除**，否则日结会重复计数。
   *
   * <p>返回的是货币金额（可参与「支付构成」核对），**不是**代币/积分数量：
   * {@code response_json} 里没有独立数量字段，这里也不做任何反推。
   *
   * @param responseJson {@code pay_collect.response_json}（可为 null/损坏）
   * @return 分腿列表；解析不到时返回空列表，绝不抛错
   */
  public static List<WalletPointLeg> walletAndPointLegs(String responseJson) {
    if (responseJson == null || responseJson.isBlank()) {
      return List.of();
    }
    JsonNode legs;
    try {
      legs = MAPPER.readTree(responseJson).path(FIELD_LEGS);
    } catch (Exception ex) {
      // 损坏/非 JSON 的历史快照：跳过并计 0，不让日结整体失败。
      return List.of();
    }
    if (legs == null || !legs.isArray() || legs.isEmpty()) {
      return List.of();
    }
    List<WalletPointLeg> result = new ArrayList<>();
    for (JsonNode leg : legs) {
      if (leg == null || !leg.isObject()) {
        continue;
      }
      JsonNode methodNode = leg.path(FIELD_METHOD);
      JsonNode amountNode = leg.path(FIELD_AMOUNT);
      if (methodNode.isMissingNode() || methodNode.isNull() || amountNode.isMissingNode() || amountNode.isNull()) {
        continue;
      }
      String method = methodNode.asText(null);
      if (method == null || !isWalletOrPoint(method)) {
        continue;
      }
      if (!amountNode.isNumber()) {
        // 非数字（字符串金额/脏数据）：跳过该分腿。
        continue;
      }
      long amount = amountNode.asLong(0L);
      if (amount <= 0L) {
        continue;
      }
      result.add(new WalletPointLeg(method, amount));
    }
    return result;
  }

  /** 是否为储值/积分分腿（大小写不敏感，脏数据写成小写也能识别）。 */
  public static boolean isWalletOrPoint(String method) {
    return method != null
        && (PROVIDER_WALLET.equalsIgnoreCase(method) || PROVIDER_POINTS.equalsIgnoreCase(method));
  }
}
