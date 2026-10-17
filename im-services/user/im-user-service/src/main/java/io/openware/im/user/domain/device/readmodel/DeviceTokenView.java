package io.openware.im.user.domain.device.readmodel;

import io.openware.common.enums.ClientPlatform;
import io.openware.common.enums.PushProvider;
import java.time.LocalDateTime;

/**
 * 推送令牌管理端列表的只读投影（standards/10 L266-279）。
 *
 * <p>列表是只读聚合查询、结果不参与状态变更决策，因此用投影而非领域模型；
 * 投影不定义业务行为、不被写路径当实体使用。
 *
 * @param id        令牌行标识
 * @param userId    所属用户
 * @param token     推送令牌
 * @param provider  推送渠道
 * @param platform  客户端平台
 * @param deviceId  设备标识
 * @param enabled   是否启用
 * @param createdAt 创建时刻（排序键）
 * @param updatedAt 最后更新时刻
 */
public record DeviceTokenView(Long id, Long userId, String token, PushProvider provider, ClientPlatform platform,
    String deviceId, Boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {
}