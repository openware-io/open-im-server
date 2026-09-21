package com.gvchat.platform.order.application;

import com.gvchat.common.exception.BusinessException;

/**
 * 商品/物料描述规则：单段文本、长度 ≤ 255（与 ord_inventory_material.description 列一致，
 * ord_product.description 虽是 500 但业务口径统一按 255 收口，避免同一入口两种限制）。
 * 空白串归一为 null，便于运营把描述清空。
 */
public final class ItemDescriptions {

  public static final int MAX_LENGTH = 255;

  private ItemDescriptions() {
  }

  public static String normalize(String description, String errorCode) {
    if (description == null) {
      return null;
    }
    String trimmed = description.trim();
    if (trimmed.length() > MAX_LENGTH) {
      throw new BusinessException(errorCode, "描述长度不能超过 %d 个字符".formatted(MAX_LENGTH));
    }
    return trimmed.isEmpty() ? null : trimmed;
  }
}
