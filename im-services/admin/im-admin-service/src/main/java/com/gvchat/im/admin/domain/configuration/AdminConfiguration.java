package com.gvchat.im.admin.domain.configuration;

import java.time.LocalDateTime;

public record AdminConfiguration(Integer id, String configKey, String configValue, String configGroup,
                                 String description, LocalDateTime createdAt, LocalDateTime updatedAt) {
}
