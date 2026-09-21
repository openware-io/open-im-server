package com.gvchat.im.message.domain.moderation;

/** 一条已启用的内容审核词规则：词面 + 级别（low/medium/high）。 */
public record ModerationWordRule(String word, String level) {
}
