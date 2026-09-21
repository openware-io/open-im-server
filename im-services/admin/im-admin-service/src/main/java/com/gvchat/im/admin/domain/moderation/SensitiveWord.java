package com.gvchat.im.admin.domain.moderation;

import com.gvchat.common.enums.SensitiveWordCategory;
import com.gvchat.common.enums.SensitiveWordLevel;
import java.time.LocalDateTime;

public record SensitiveWord(Long id, String word, SensitiveWordCategory category, SensitiveWordLevel level,
                            Boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
