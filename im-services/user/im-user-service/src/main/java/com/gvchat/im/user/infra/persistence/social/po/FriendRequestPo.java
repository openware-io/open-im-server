package com.gvchat.im.user.infra.persistence.social.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("user_friend_request")
public class FriendRequestPo {
  @TableId(type = IdType.AUTO)
  private Long id;
  @TableField("from_user_id")
  private Long fromUserId;
  @TableField("to_user_id")
  private Long toUserId;
  private String message;
  private String status;
  @TableField("created_by")
  private Long createdBy;
  @TableField("created_at")
  private LocalDateTime createdAt;
  @TableField("updated_by")
  private Long updatedBy;
  @TableField("updated_at")
  private LocalDateTime updatedAt;
}
