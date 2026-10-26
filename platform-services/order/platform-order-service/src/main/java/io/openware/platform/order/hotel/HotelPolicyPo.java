package io.openware.platform.order.hotel;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 酒店店铺策略；一个酒店一条，租户/门店隔离。 */
@Getter
@Setter
@TableName("htl_policy")
public class HotelPolicyPo {
    @TableId
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long hotelId;
    private String accessMode;
    private String entryPoints;
    private String paymentMode;
    private Integer depositRatioBps;
    private Boolean stayDepositRequired;
    private String stayDepositMode;
    private Long stayDepositMinor;
    private Integer stayDepositRatioBps;
    private String cancelPolicy;
    private Boolean pointsRedeemEnabled;
    private Integer pointsMaxRatioBps;
    private String invoiceMode;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
