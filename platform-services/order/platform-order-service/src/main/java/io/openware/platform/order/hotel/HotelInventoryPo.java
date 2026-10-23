package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("htl_inventory_calendar")
public class HotelInventoryPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long roomTypeId;
    private LocalDate stayDate;
    private Integer total;
    private Integer sold;
    private Integer held;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
