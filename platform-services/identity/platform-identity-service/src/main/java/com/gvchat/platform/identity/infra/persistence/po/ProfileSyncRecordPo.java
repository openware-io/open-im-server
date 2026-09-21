package com.gvchat.platform.identity.infra.persistence.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("idt_profile_sync_record")
public class ProfileSyncRecordPo {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String eventId;
    private Long accountId;
    private String provider;
    private String profileJson;
    private LocalDateTime occurredAt;
}
