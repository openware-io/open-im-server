package io.openware.platform.customer.domain.member.repository;

import io.openware.platform.customer.domain.member.model.Member;
import io.openware.platform.customer.domain.member.model.MemberFilter;
import java.util.List;
import java.util.Optional;

/**
 * 客户档案（{@code cst_member}）的仓储端口。
 *
 * <p>不暴露 MyBatis Wrapper、PO、Mapper 或 {@code IPage}/{@code Page}（见 standards/10
 * "端口命名与契约"）。分页以「偏移 + 上限」与总数表达，由适配器翻译成 MyBatis-Plus 的分页对象。
 *
 * <p><b>写入方法是窄口的、按用例命名的</b>：本表有 DDL 列 {@code version}，但 PO 上没有
 * {@code @Version}、工程里也没有 {@code OptimisticLockerInnerInterceptor}，写路径<b>不按版本判定</b>。
 * 因此这里<b>不提供</b>通用的"整个聚合写回"方法——那会让人误以为它带乐观锁，
 * 也会把展示字段一路带到 SQL 上（{@code @TableField(exist = false)} 的列引用是运行时炸弹）。
 */
public interface MemberRepository {

  /**
   * 按 id 取客户。
   *
   * <p>跨租户也走这条路径：租户行过滤由 MyBatis 拦截器附加，查不到就是查不到——调用方据此回
   * 404，不暴露「存在但不属于你」。
   */
  Optional<Member> findById(Long id);

  /**
   * 按账号取<b>最早一条</b>客户（C 端懒创建 / 建档幂等的定位口径）。
   *
   * <p>历史脏数据里同一 {@code accountId} 可能有多条（ACK 租户 100 实测 63 行只有 23 个 accountId）：
   * 这里按 id 升序取最早一条，命中多条由适配器打 WARN，<b>不让查询因多行直接抛异常</b>。
   */
  Optional<Member> findEarliestByAccountId(Long accountId);

  /** 按 IM 账号取<b>最早一条</b>客户（同租户内应唯一；{@code im_account} 为 NULL 的行不参与）。 */
  Optional<Member> findEarliestByImAccount(String imAccount);

  /**
   * 分页取客户 id（按 id 倒序）。
   *
   * <p>关键词的四个分支是 <b>OR</b> 关系：客户号 LIKE / 姓名盲索引 token 全命中 → {@code id IN (...)} /
   * 手机号摘要精确匹配 / IM 账号与用户名 LIKE。token 命中集合为空时只跳过该分支，<b>不回空页</b>
   * ——关键词是手机号或 IM 账号时盲索引必然无命中，回空页会让这两条通道一起失效。
   */
  MemberIdPage listIds(MemberFilter filter);

  /** 按 id 批量取客户（保持传入顺序，缺失的 id 跳过）。 */
  List<Member> findAllById(List<Long> ids);

  /** 租户内客户总数（总部汇总口径：租户级，不按门店过滤）。 */
  long countAll();

  /**
   * 新增客户，返回落库后的聚合（含回填主键）。
   *
   * <p>租户 + 客户号 / 账号 / IM 账号三个唯一键由 DDL 兜底；撞键必须抛出而不是吞掉，
   * 否则调用方会拿着一个未落库的档案继续写盲索引。
   */
  Member insert(Member member);

  /**
   * 绑定 IM 账号（写路径，{@code PUT /business/members/{id}/im-binding}）。
   *
   * <p>只写四列：{@code im_account} / {@code im_username} / {@code im_bound_at} / {@code updated_at}，
   * 以及<b>可选</b>的 {@code name_cipher}（{@code newNameCipher} 非空时才写）。
   *
   * <p>按 {@code version = ?} 做 CAS 并自增；{@code expectedVersion} 必填，
   * 不存在“没有版本快照就无条件更新”的退化路径。
   *
   * @return 是否命中一行；{@code false} = 目标行在此期间消失或版本已变，调用方必须拒绝而不是当作成功
   */
  boolean updateImBinding(Long id, String imAccount, String imUsername, java.time.LocalDateTime imBoundAt,
                          String newNameCipher, int expectedVersion);

  /**
   * 更新"可改属性"：建档时间 {@code joined_at}、等级 {@code level_id}、状态 {@code status}、账号
   * {@code account_id}。传 {@code null} 的字段<b>不参与 SET</b>（不是"写成 NULL"）。
   *
   * <p>存在的理由：这三类字段没有任何业务写路径（等级/权益业务未实现、建档时间只在建档时写、
   * 账号归属由统一账号模型回填），但它们是列表筛选与分页的真实判据——集成测试必须能把库里
   * 的行摆成"某个建档时间/等级/状态"，否则筛选只能靠 mock 断言、证明不了 SQL 真的过滤了。
   *
   * <p>{@code expectedVersion} 必填，始终走 {@code version = ?} CAS 并自增。
   */
  boolean updateAttributes(Long id, java.time.LocalDateTime joinedAt, Long levelId, String status,
                           Long accountId, int expectedVersion);

  /** 删除客户档案（{@code MemberPurgeApplicationService} 的最后一个动作）。 */
  boolean deleteById(Long id);

  /**
   * 客户 id 一页。
   *
   * @param ids   本页 id（按 id 倒序）
   * @param total 满足条件的总条数
   */
  record MemberIdPage(List<Long> ids, long total) {
  }
}
