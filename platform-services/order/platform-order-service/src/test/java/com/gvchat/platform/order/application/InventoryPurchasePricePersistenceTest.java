package com.gvchat.platform.order.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gvchat.infrastructure.audit.AuditClient;
import com.gvchat.infrastructure.mq.MqConsumerFactory;
import com.gvchat.infrastructure.mq.MqProducer;
import com.gvchat.infrastructure.tenant.TenantContext;
import com.gvchat.infrastructure.tenant.TenantContextHolder;
import com.gvchat.infrastructure.time.TimeRangeParams.TimeRange;
import com.gvchat.platform.order.infra.mq.EventOutboxRelay;
import com.gvchat.platform.order.infra.persistence.mapper.InventoryMaterialMapper;
import com.gvchat.platform.order.infra.persistence.po.InventoryMaterialPo;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 采购价落库集成测试（真跑 H2 + Flyway + MyBatis，仅 mock MQ/审计）。
 *
 * <p>覆盖 V20__ord_inventory_material_purchase_price.sql 新增的 purchase_price 列：
 * 1) 新建带采购价可写入并读回，且列表接口（listMaterials）返回该字段；
 * 2) 「0 = 清空」在真实 UPDATE 语句里写成 NULL —— MyBatis-Plus 默认 NOT_NULL 会跳过 null 字段，
 *    这里用原始 JDBC 读列值来证明清空不是静默失效（PO 侧靠 updateStrategy=ALWAYS 生效）；
 * 3) 不传采购价（null）时保留库中原值。
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryPurchasePricePersistenceTest {

  private static final long TENANT_ID = 1001L;
  private static final long STORE_ID = 2601L;

  @MockitoBean
  private MqProducer mqProducer;

  @MockitoBean
  private MqConsumerFactory mqConsumerFactory;

  @MockitoBean
  private AuditClient auditClient;

  @MockitoBean
  private EventOutboxRelay eventOutboxRelay;

  @Autowired
  private InventoryApplicationService inventoryService;

  @Autowired
  private InventoryMaterialMapper materialMapper;

  @Autowired
  private DataSource dataSource;

  @BeforeEach
  void setUpTenant() {
    TenantContextHolder.set(new TenantContext(TENANT_ID, 1L, STORE_ID, 0L, 0));
  }

  @AfterEach
  void tearDownTenant() {
    TenantContextHolder.clear();
  }

  @Test
  void createMaterialPersistsPurchasePriceAndListReturnsIt() throws Exception {
    InventoryMaterialPo created = inventoryService.createMaterial(command("可乐 330ml", new BigDecimal("350")));

    assertEquals(0, created.getPurchasePrice().compareTo(new BigDecimal("350")));
    assertEquals(0, rawPurchasePrice(created.getId()).compareTo(new BigDecimal("350")), "分必须原样落库，不做单位换算");

    List<InventoryMaterialPo> listed = inventoryService
        .listMaterials(1L, InventoryApplicationService.MAX_PAGE_SIZE, STORE_ID, null, null, null, TimeRange.none())
        .getRecords();
    InventoryMaterialPo hit = listed.stream().filter(row -> row.getId().equals(created.getId())).findFirst().orElse(null);
    assertNotNull(hit, "列表必须返回新建的物料");
    assertNotNull(hit.getPurchasePrice(), "列表接口必须带上采购价");
    assertEquals(0, hit.getPurchasePrice().compareTo(new BigDecimal("350")));
  }

  @Test
  void updateMaterialReplacesPurchasePrice() throws Exception {
    InventoryMaterialPo created = inventoryService.createMaterial(command("雪碧 330ml", new BigDecimal("350")));

    inventoryService.updateMaterial(created.getId(), command(null, new BigDecimal("420")));

    assertEquals(0, rawPurchasePrice(created.getId()).compareTo(new BigDecimal("420")));
  }

  @Test
  void updateMaterialClearsPurchasePriceToNullWhenZero() throws Exception {
    InventoryMaterialPo created = inventoryService.createMaterial(command("芬达 330ml", new BigDecimal("350")));

    inventoryService.updateMaterial(created.getId(), command(null, BigDecimal.ZERO));

    assertNull(rawPurchasePrice(created.getId()), "0 = 清空，列值必须是 NULL 而不是 0");
    assertNull(materialMapper.selectById(created.getId()).getPurchasePrice());
  }

  @Test
  void updateMaterialKeepsPurchasePriceWhenNotProvided() throws Exception {
    InventoryMaterialPo created = inventoryService.createMaterial(command("冰红茶", new BigDecimal("350")));

    inventoryService.updateMaterial(created.getId(), command("冰红茶 500ml", null));

    InventoryMaterialPo reloaded = materialMapper.selectById(created.getId());
    assertEquals("冰红茶 500ml", reloaded.getName());
    assertNotNull(reloaded.getPurchasePrice(), "null = 不修改，不能把已维护的采购价冲掉");
    assertEquals(0, reloaded.getPurchasePrice().compareTo(new BigDecimal("350")));
  }

  /** 未填采购价的新建物料，落库必须是 NULL（而不是 0），避免与「采购价 0 分」混用。 */
  @Test
  void createMaterialWithoutPurchasePriceKeepsColumnNull() throws Exception {
    InventoryMaterialPo created = inventoryService.createMaterial(command("毛巾", null));

    assertNull(created.getPurchasePrice());
    assertNull(rawPurchasePrice(created.getId()));
  }

  /** 采购价上限 10^13 分：等于上限可写入，超过由 service 直接 400（见单测），这里守住列宽可容纳上限。 */
  @Test
  void purchasePriceUpperBoundFitsColumn() throws Exception {
    InventoryMaterialPo created = inventoryService.createMaterial(command("上限物料", new BigDecimal("10000000000000")));

    assertEquals(0, rawPurchasePrice(created.getId()).compareTo(new BigDecimal("10000000000000")));
  }

  private static InventoryApplicationService.MaterialCommand command(String name, BigDecimal purchasePrice) {
    return new InventoryApplicationService.MaterialCommand(
        STORE_ID, "PP-" + System.nanoTime(), name, "饮品", "瓶", BigDecimal.ZERO, purchasePrice, null, null, null);
  }

  /** 绕过 ORM 直读原始列值，确认 UPDATE/INSERT 真的写到了 purchase_price。 */
  private BigDecimal rawPurchasePrice(Long id) throws Exception {
    try (Connection connection = dataSource.getConnection();
         Statement statement = connection.createStatement();
         ResultSet resultSet = statement.executeQuery(
             "SELECT purchase_price FROM ord_inventory_material WHERE id = " + id)) {
      assertTrue(resultSet.next(), "物料行必须存在");
      return resultSet.getBigDecimal(1);
    }
  }
}
