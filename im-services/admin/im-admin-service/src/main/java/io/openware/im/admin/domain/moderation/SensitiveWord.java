package io.openware.im.admin.domain.moderation;

import io.openware.common.enums.SensitiveWordCategory;
import io.openware.common.enums.SensitiveWordLevel;
import java.time.LocalDateTime;

public record SensitiveWord(Long id, String word, SensitiveWordCategory category, SensitiveWordLevel level,
                            Boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
