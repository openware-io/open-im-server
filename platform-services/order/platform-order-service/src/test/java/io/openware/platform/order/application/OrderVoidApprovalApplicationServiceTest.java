package io.openware.platform.order.application;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.openware.infrastructure.audit.AuditClient;
import io.openware.platform.order.infra.persistence.mapper.OrdOrderVoidApprovalMapper;
import io.openware.platform.order.infra.persistence.po.OrdOrderVoidApprovalPo;
import io.openware.platform.order.infra.persistence.po.OrderPo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderVoidApprovalApplicationServiceTest {
    @Mock private OrdOrderVoidApprovalMapper mapper;
    @Mock private OrderCancellationApplicationService cancellation;
    @Mock private AuditClient audit;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OrdOrderVoidApprovalPo.class);
    }

    @Test
    void approveQueriesApprovalByTenantStoreAndApprovalIdThenExecutesVoid() {
        OrderVoidApprovalApplicationService service = new OrderVoidApprovalApplicationService(mapper, cancellation, audit);
        OrdOrderVoidApprovalPo approval = approval(91L, 11L, 21L, 31L, 41L);
        when(mapper.selectOne(any())).thenReturn(approval);
        when(mapper.update(eq(null), any())).thenReturn(1);
        when(mapper.selectById(91L)).thenAnswer(invocation -> approved(approval));
        OrderPo order = new OrderPo();
        order.setId(31L);
        order.setStatus("SERVING");
        when(cancellation.requireOrder(31L)).thenReturn(order);

        OrdOrderVoidApprovalPo result = service.approve(11L, 21L, 51L, 91L, "同意作废");

        assertEquals(OrderVoidApprovalApplicationService.EXECUTED, result.getStatus());
        verify(cancellation).voidOrderApproved(31L, "测试作废");
        verify(mapper).selectOne(any(Wrapper.class));
    }

    @Test
    void rejectDoesNotExecuteOrderVoid() {
        OrderVoidApprovalApplicationService service = new OrderVoidApprovalApplicationService(mapper, cancellation, audit);
        OrdOrderVoidApprovalPo approval = approval(92L, 12L, 22L, 32L, 42L);
        when(mapper.selectOne(any())).thenReturn(approval);
        when(mapper.update(eq(null), any())).thenReturn(1);
        when(mapper.selectById(92L)).thenAnswer(invocation -> {
            approval.setStatus(OrderVoidApprovalApplicationService.REJECTED);
            return approval;
        });

        OrdOrderVoidApprovalPo result = service.reject(12L, 22L, 52L, 92L, "不同意");

        assertEquals(OrderVoidApprovalApplicationService.REJECTED, result.getStatus());
        verify(cancellation, never()).voidOrderApproved(any(), any());
    }

    private static OrdOrderVoidApprovalPo approval(long id, long tenantId, long storeId, long orderId, long applicantId) {
        OrdOrderVoidApprovalPo approval = new OrdOrderVoidApprovalPo();
        approval.setId(id);
        approval.setTenantId(tenantId);
        approval.setStoreId(storeId);
        approval.setOrderId(orderId);
        approval.setApplicantId(applicantId);
        approval.setReason("测试作废");
        approval.setStatus(OrderVoidApprovalApplicationService.PENDING);
        approval.setVersion(0);
        return approval;
    }

    private static OrdOrderVoidApprovalPo approved(OrdOrderVoidApprovalPo approval) {
        approval.setStatus(OrderVoidApprovalApplicationService.APPROVED);
        approval.setVersion(1);
        return approval;
    }
}
