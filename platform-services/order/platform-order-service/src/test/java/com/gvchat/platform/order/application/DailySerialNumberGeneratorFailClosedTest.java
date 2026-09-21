package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.exception.BusinessException;
import com.gvchat.platform.order.application.DailySerialNumberGenerator.DocType;
import com.gvchat.platform.order.infra.persistence.mapper.DailySerialMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

/**
 * 单号生成的**失败语义**（失败关闭）与并发首单重试路径：
 * 序号服务（数据库）不可用时必须抛 503 {@code DOC_NO_SEQUENCE_UNAVAILABLE} 让创建失败，
 * 绝不降级成时间戳/UUID —— 降级会静默产生不符合规则、且可能重复的单号。
 *
 * <p>纯 Mockito 单测（不起 Spring）：这里验证「异常如何收敛」，事务与真库行为由
 * {@link DailySerialNumberGeneratorTest}（H2 真跑）覆盖。
 */
class DailySerialNumberGeneratorFailClosedTest {

    private static final Long TENANT_ID = 7L;
    private static final Instant MOMENT = Instant.parse("2026-09-19T12:00:00Z");
    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 9, 19);

    private DailySerialMapper mapper;
    private DailySerialNumberGenerator generator;

    @BeforeEach
    void setUp() {
        mapper = mock(DailySerialMapper.class);
        generator = new DailySerialNumberGenerator(mapper);
    }

    /** 数据库不可达（连接失败/超时）：抛失败关闭错误码，不返回任何「降级号」。 */
    @Test
    void databaseUnavailableFailsClosed() {
        when(mapper.incrementIfPresent(any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> generator.next(DocType.ORDER, TENANT_ID, MOMENT));

        assertEquals(DailySerialNumberGenerator.CODE_SEQUENCE_UNAVAILABLE, failure.getCode());
    }

    /** 建行语句本身失败（权限/表缺失等）：同样失败关闭，不吞异常返回号。 */
    @Test
    void insertFailureFailsClosed() {
        when(mapper.incrementIfPresent(any(), any(), any(), any())).thenReturn(0);
        when(mapper.insertFirst(any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("table missing"));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> generator.next(DocType.RESERVATION, TENANT_ID, MOMENT));

        assertEquals(DailySerialNumberGenerator.CODE_SEQUENCE_UNAVAILABLE, failure.getCode());
    }

    /** 自增成功但读回为空（数据被并发删除等异常态）：失败关闭，而不是当作 0/1 发出去。 */
    @Test
    void unreadableSequenceFailsClosed() {
        when(mapper.incrementIfPresent(any(), any(), any(), any())).thenReturn(1);
        when(mapper.selectCurrentSeq(any(), any(), any())).thenReturn(null);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> generator.next(DocType.ORDER, TENANT_ID, MOMENT));

        assertEquals(DailySerialNumberGenerator.CODE_SEQUENCE_UNAVAILABLE, failure.getCode());
    }

    /** 并发首单被抢：建行撞唯一键后重试自增，最终拿到正确的下一个序号（不是失败）。 */
    @Test
    void concurrentFirstAllocationRetriesIncrementAndSucceeds() {
        when(mapper.incrementIfPresent(any(), any(), any(), any())).thenReturn(0, 1);
        when(mapper.insertFirst(any(), any(), any(), any())).thenThrow(new DuplicateKeyException("dup"));
        when(mapper.selectCurrentSeq(any(), any(), any())).thenReturn(2L);

        String number = generator.next(DocType.ORDER, TENANT_ID, MOMENT);

        assertEquals("O202609190002", number);
        verify(mapper, times(1)).insertFirst(eq(TENANT_ID), eq(DocType.ORDER.name()), eq(BUSINESS_DATE),
                any(LocalDateTime.class));
    }

    /** 重试上限耗尽（极端竞争/行被反复抢占）：失败关闭，宁可让创建失败也不发不确定的号。 */
    @Test
    void exhaustedAllocationAttemptsFailClosed() {
        when(mapper.incrementIfPresent(any(), any(), any(), any())).thenReturn(0);
        when(mapper.insertFirst(any(), any(), any(), any())).thenThrow(new DuplicateKeyException("dup"));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> generator.next(DocType.ORDER, TENANT_ID, MOMENT));

        assertEquals(DailySerialNumberGenerator.CODE_SEQUENCE_UNAVAILABLE, failure.getCode());
    }

    /** 缺少租户上下文：400（参数问题，不是服务不可用），且不触碰序列表。 */
    @Test
    void missingTenantIsRejectedWithoutTouchingSequenceTable() {
        ApiException failure = assertThrows(ApiException.class,
                () -> generator.next(DocType.ORDER, null, MOMENT));

        assertEquals(400, failure.getStatus());
        assertEquals("SAAS_CONTEXT_REQUIRED", failure.getCode());
    }

    /** 缺少单据类型：400。 */
    @Test
    void missingDocTypeIsRejected() {
        ApiException failure = assertThrows(ApiException.class,
                () -> generator.next(null, TENANT_ID, MOMENT));

        assertEquals(400, failure.getStatus());
        assertEquals("DOC_TYPE_REQUIRED", failure.getCode());
    }
}
