package io.openware.im.user.infra.persistence.sticker.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface UserStickerQuotaMapper {
  /** 名额行由用户自身操作创建，审计字段直接取自该用户；时间列由 DDL 默认值填充。 */
  @Insert("INSERT IGNORE INTO user_sticker_quota (user_id, sticker_count, created_by, updated_by) "
      + "VALUES (#{userId}, 0, #{userId}, #{userId})")
  int createIfAbsent(@Param("userId") Long userId);

  @Delete("DELETE FROM user_sticker_quota WHERE user_id = #{userId}")
  int deleteByUserId(@Param("userId") Long userId);

  @Update("UPDATE user_sticker_quota SET sticker_count = sticker_count + #{slotCount} "
      + "WHERE user_id = #{userId} AND sticker_count <= #{maximum} - #{slotCount}")
  int reserveSlots(@Param("userId") Long userId, @Param("slotCount") int slotCount, @Param("maximum") int maximum);

  @Update("UPDATE user_sticker_quota SET sticker_count = GREATEST(sticker_count - #{slotCount}, 0) "
      + "WHERE user_id = #{userId}")
  int releaseSlots(@Param("userId") Long userId, @Param("slotCount") int slotCount);
}
