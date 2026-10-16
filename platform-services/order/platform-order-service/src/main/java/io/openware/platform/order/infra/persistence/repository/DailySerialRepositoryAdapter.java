package io.openware.platform.order.infra.persistence.repository;

import io.openware.platform.order.domain.order.repository.DailySerialRepository;
import io.openware.platform.order.infra.persistence.mapper.DailySerialMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * {@link DailySerialRepository} 的持久化适配器。
 *
 * <p>三条语句逐字来自 {@code DailySerialMapper}（都标了 {@code @InterceptorIgnore(tenantLine = "true")}
 * 并显式带 {@code tenant_id}）：发号发生在单据落库之前，调用上下文可能不完整，
 * 忽略租户拦截才能按调用方给的租户定位序号行。
 *
 * <p><b>唯一一处翻译</b>：建行撞唯一键（并发首单被抢）时，把 Spring 的
 * {@link DuplicateKeyException} 翻成 {@code false}，而不是抛给应用层。端口因此只表达
 * 「行已存在 / 被并发抢先」两种结果，应用层不必认识 DAO 异常；把异常放出去会让
 * 「重试自增」这条路径彻底失效（调用方只能失败关闭，表现为并发首单发不出号）。
 */
@Slf4j
@Repository
public class DailySerialRepositoryAdapter implements DailySerialRepository {

    private final DailySerialMapper mapper;

    public DailySerialRepositoryAdapter(DailySerialMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public boolean incrementIfPresent(Long tenantId, String businessType, LocalDate businessDate, LocalDateTime at) {
        return mapper.incrementIfPresent(tenantId, businessType, businessDate, at) == 1;
    }

    @Override
    public boolean insertFirstIfAbsent(Long tenantId, String businessType, LocalDate businessDate,
                                       LocalDateTime at) {
        try {
            mapper.insertFirst(tenantId, businessType, businessDate, at);
            return true;
        } catch (DuplicateKeyException concurrentCreate) {
            // 另一个事务刚建了同一行：本轮没抢到，调用方下一轮走自增分支。
            // 这不是错误——唯一键只放行一个事务正是期望行为；但仍记一条 DEBUG，
            // 否则"建行失败"在日志里完全不可见（与支付侧事件消费者的幂等跳过同型）。
            log.debug("日序列建行未抢到（并发首单），本轮走自增分支: tenantId={}, businessType={}, date={}",
                    tenantId, businessType, businessDate);
            return false;
        }
    }

    @Override
    public Optional<Long> currentSequence(Long tenantId, String businessType, LocalDate businessDate) {
        return Optional.ofNullable(mapper.selectCurrentSeq(tenantId, businessType, businessDate));
    }
}
