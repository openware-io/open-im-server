package com.gvchat.platform.admin.infra.persistence.mapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 商品/服务销售排行 SQL（{@code selectSalesItems}）的口径守卫。
 *
 * <p>这条报表最容易错的三处，都用 SQL 契约钉住：
 * <ol>
 *   <li><b>品类归一</b>：{@code item_type='ADD_ON'} 只是「从加项入口点的」，本身可能是商品也可能是服务，
 *       必须优先取目录项类型，否则商品的销量会被算进「加项」；{@code ROOM_FEE} 单列。</li>
 *   <li><b>只算有效数据</b>：待确认加项（{@code PENDING_APPROVAL}）还没成立、被拒的（{@code REJECTED}）
 *       不该计入销售；已取消/已作废订单同理。</li>
 *   <li><b>区间口径与销售报表一致</b>（订单创建时间闭开区间），且**不按营业日分档**——
 *       排行看整段，写了 {@code DATE_FORMAT} 反而会被营业日守卫要求补平移量。</li>
 * </ol>
 */
class ReportSalesItemsMapperContractTest {

    @Test
    @DisplayName("品类归一：目录项类型优先，退回明细类型，兜底 ADD_ON，包厢费单列")
    void categoryIsNormalised() {
        String sql = sqlOf("selectSalesItems");
        assertTrue(sql.contains("AS item_category"), "必须有归一化后的品类列");
        assertTrue(sql.contains("WHEN i.item_type = 'ROOM_FEE' THEN 'ROOM_FEE'"), "包厢费单列一类");
        assertTrue(sql.contains("WHEN c.item_type IN ('PRODUCT', 'SERVICE', 'PACKAGE') THEN c.item_type"),
                "优先取目录项类型（ADD_ON 入口点进来的商品/服务/套餐要还原真实品类）");
        assertTrue(sql.contains("WHEN i.item_type IN ('PRODUCT', 'SERVICE', 'PACKAGE') THEN i.item_type"),
                "目录项缺失时退回明细类型");
        assertTrue(sql.contains("ELSE 'ADD_ON'"), "都取不到才归到加项");
        assertTrue(sql.contains("LEFT JOIN ord_catalog_item c ON c.id = i.catalog_item_id"), "必须关联目录项");
    }

    @Test
    @DisplayName("只统计已生效明细 + 有效订单（待确认/被拒明细、已取消/已作废订单都不计入）")
    void onlyEffectiveItemsAndOrders() {
        String sql = sqlOf("selectSalesItems");
        assertTrue(sql.contains("i.status = 'ACTIVE'"), "只算已生效明细（PENDING_APPROVAL/REJECTED 不计销售）");
        assertTrue(sql.contains("o.status NOT IN ('CANCELLED', 'VOIDED')"), "排除已取消/已作废订单");
    }

    @Test
    @DisplayName("区间与门店口径与销售报表同源；按名称排行且 LIMIT 参数化")
    void sameRangeAsSalesReport() {
        String sql = sqlOf("selectSalesItems");
        assertTrue(sql.contains("o.created_at >= #{from} AND o.created_at < #{to}"), "与销售报表同区间口径");
        assertTrue(sql.contains("(#{storeId} IS NULL OR o.store_id = #{storeId})"), "门店过滤与其它报表一致");
        assertTrue(sql.contains("GROUP BY i.currency_code, item_category, i.name_snapshot"),
                "按 (币种, 品类, 名称) 聚合；GROUP BY 用别名，避免 ONLY_FULL_GROUP_BY 把重复表达式判为不等价");
        assertTrue(sql.contains("ORDER BY sales_amount DESC"), "按销售额降序排行");
        assertTrue(sql.contains("LIMIT #{limit}"), "取前 N 条由参数控制");
        assertTrue(sql.contains("SUM(i.total_amount) AS sales_amount"), "销售额取明细金额（已扣折扣）");
        assertTrue(sql.contains("SUM(i.discount_amount) AS discount_amount"), "折扣额单列");
        assertTrue(sql.contains("COUNT(DISTINCT i.order_id) AS order_count"), "订单数按订单去重");
        assertTrue(sql.contains("COUNT(DISTINCT o.store_id) AS store_count"), "跨门店汇总时给出售卖门店数");
        assertFalse(sql.contains("DATE_FORMAT("), "排行不按营业日分档（分档是销售报表明细的职责）");
    }

    @Test
    @DisplayName("品类过滤参数化：空值 = 全部品类")
    void categoryFilterIsOptional() {
        String sql = sqlOf("selectSalesItems");
        assertTrue(sql.contains("(#{itemCategory} IS NULL OR"), "品类为空时不筛");
        Set<String> params = Arrays.stream(methodOf("selectSalesItems").getParameters())
                .map(parameter -> parameter.getAnnotation(Param.class))
                .filter(java.util.Objects::nonNull)
                .map(Param::value)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(params.containsAll(Set.of("tenantId", "storeId", "from", "to", "itemCategory", "limit")),
                "参数名必须与 SQL 占位符一致，实际: " + params);
        List<Parameter> limitParam = Arrays.stream(methodOf("selectSalesItems").getParameters())
                .filter(parameter -> {
                    Param param = parameter.getAnnotation(Param.class);
                    return param != null && "limit".equals(param.value());
                })
                .toList();
        assertTrue(limitParam.size() == 1 && limitParam.get(0).getType() == int.class, "limit 必须是 int");
    }

    // —— 辅助 ——

    private static Method methodOf(String methodName) {
        for (Method method : ReportMapper.class.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return method;
            }
        }
        throw new AssertionError("ReportMapper 缺少方法: " + methodName);
    }

    private static String sqlOf(String methodName) {
        Select select = methodOf(methodName).getAnnotation(Select.class);
        assertTrue(select != null, methodName + " 必须保留 @Select");
        return String.join(" ", select.value());
    }
}
