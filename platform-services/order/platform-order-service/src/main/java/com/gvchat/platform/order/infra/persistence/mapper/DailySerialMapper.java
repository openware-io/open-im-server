package com.gvchat.platform.order.infra.persistence.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 单据号每日序号分配（{@code ord_daily_serial}）。
 *
 * <p><b>为什么忽略租户拦截器</b>：序号分配用的是**调用方显式传入的 tenantId**，而发号发生在
 * 单据落库之前，调用上下文可能缺失或不完整（C 端下单、内部开台、后台代客预约的上下文形态不同）。
 * 因此三个语句都标注 {@code @InterceptorIgnore(tenantLine = "true")} 并显式带 tenant_id，
 * 与仓内 {@code OrderMapper#selectTenantIdById} / {@code MemberNameTokenMapper} 同款写法：
 * 既避免「拦截器再追加一次 tenant_id」的重复条件，也避免「无上下文时被拦成查不到行而静默重发 0001」。
 *
 * <p>三条语句的语义由 {@code DailySerialNumberGenerator} 在**同一个事务**里组合，
 * 靠 InnoDB 行锁（UPDATE 持有到提交）保证「自增 → 读回」之间没有别的会话插入：
 * <ol>
 *   <li>{@link #incrementIfPresent}：行已存在时自增并占用排他锁，返回影响行数 0/1；</li>
 *   <li>{@link #insertFirst}：行不存在时建行（{@code current_seq = 1}，即当日第 1 单），
 *       并发下由 {@code uk_ord_daily_serial_key} 只放行一个事务；</li>
 *   <li>{@link #selectCurrentSeq}：读回本事务刚分配到的值（持锁期间读到的必然是自己写的值）。</li>
 * </ol>
 */
@Mapper
public interface DailySerialMapper {

    /**
     * 行已存在则自增（并持有该行排他锁直到当前事务提交/回滚）。
     *
     * @return 1 = 自增成功；0 = 该「租户 + 单据类型 + 营业日」还没有行（首次发号，需要先建行）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Update("UPDATE ord_daily_serial SET current_seq = current_seq + 1, updated_at = #{now} "
            + "WHERE tenant_id = #{tenantId} AND biz_type = #{bizType} AND business_date = #{businessDate}")
    int incrementIfPresent(@Param("tenantId") Long tenantId,
                           @Param("bizType") String bizType,
                           @Param("businessDate") LocalDate businessDate,
                           @Param("now") LocalDateTime now);

    /**
     * 建行并直接占用当日第 1 单（{@code current_seq = 1}）。
     *
     * <p>并发下唯一键 {@code uk_ord_daily_serial_key} 只允许一个事务成功，其余抛
     * {@link org.springframework.dao.DuplicateKeyException}，由调用方重试自增。
     *
     * @return 影响行数（成功恒为 1）
     */
    @InterceptorIgnore(tenantLine = "true")
    @Insert("INSERT INTO ord_daily_serial (tenant_id, biz_type, business_date, current_seq, created_at, updated_at) "
            + "VALUES (#{tenantId}, #{bizType}, #{businessDate}, 1, #{now}, #{now})")
    int insertFirst(@Param("tenantId") Long tenantId,
                    @Param("bizType") String bizType,
                    @Param("businessDate") LocalDate businessDate,
                    @Param("now") LocalDateTime now);

    /** 读回当前已分配到的序号（只能在自增/建行所在的那个事务内调用，否则可能读到别的会话的值）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("SELECT current_seq FROM ord_daily_serial "
            + "WHERE tenant_id = #{tenantId} AND biz_type = #{bizType} AND business_date = #{businessDate}")
    Long selectCurrentSeq(@Param("tenantId") Long tenantId,
                          @Param("bizType") String bizType,
                          @Param("businessDate") LocalDate businessDate);
}
