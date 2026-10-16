package io.openware.platform.customer.domain.member.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 客户档案分页查询条件（列表 / 积分列表 / 储值列表三处共用同一份口径）。
 *
 * <p><b>为什么归一成一个请求对象</b>：三个列表此前各自拼 {@code LambdaQueryWrapper}，
 * "同一份 {@code joined_at} 过滤实现"只是一句注释；现在过滤条件只能经本对象表达，
 * 端口无法被绕过，口径漂移在结构上不可能发生。
 *
 * <p>{@code nameMatchIds} 与 {@code phoneDigest} 由应用层预先算好
 * （{@link io.openware.platform.customer.application.NameBlindIndex} 与应用服务同属应用层）：
 * 领域端口只消费结果——领域层不依赖加密/摘要工具，适配器也不需要知道 token 是怎么算出来的。
 *
 * @param keyword       关键词原文（已 trim）；空 = 不筛
 * @param phoneDigest   关键词是纯数字手机号时的 SHA-256 摘要；{@code null} = 该分支不参与
 * @param nameMatchIds  姓名/客户号盲索引的候选档案 id；空 = 该 OR 分支跳过
 *                      （不得拼出非法的 {@code IN ()}，也不得因此回空页）
 * @param levelId       等级筛选；{@code null} = 不筛
 * @param status        状态筛选；空白 = 不筛
 * @param onlyUnlinked  true = 只取 {@code im_account IS NULL}
 * @param originStoreId 首次新增门店筛选；{@code null} = 不筛
 * @param joinedFrom    建档时间下界（含）；{@code null} = 不筛
 * @param joinedTo      建档时间上界（含）；{@code null} = 不筛
 * @param offset        偏移（由页码换算，{@code >= 0}）
 * @param limit         上限（页大小，{@code >= 1}）
 */
public record MemberFilter(
    String keyword,
    String phoneDigest,
    List<Long> nameMatchIds,
    Long levelId,
    String status,
    boolean onlyUnlinked,
    Long originStoreId,
    LocalDateTime joinedFrom,
    LocalDateTime joinedTo,
    long offset,
    long limit) {

  /** 关键词是否为空（空即不参与检索）。 */
  public boolean hasKeyword() {
    return keyword != null && !keyword.isBlank();
  }
}
