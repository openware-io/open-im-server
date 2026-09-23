package io.openware.im.message.domain.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容审核词过滤：按级别处理消息正文。
 *
 * <ul>
 *   <li>{@code high}：拦截（返回 blocked，正文不落库）。</li>
 *   <li>{@code medium}：替换（命中词以等长星号替换后落库）。</li>
 *   <li>{@code low}：仅记录（正文原样落库，仅返回命中信息用于审计日志）。</li>
 * </ul>
 *
 * <p>纯函数式、无框架依赖，便于单元测试；命中匹配大小写不敏感。</p>
 */
public final class ModerationWordFilter {
  public static final String LEVEL_HIGH = "high";
  public static final String LEVEL_MEDIUM = "medium";
  public static final String LEVEL_LOW = "low";

  private ModerationWordFilter() {
  }

  public static Result filter(String text, List<ModerationWordRule> rules) {
    String content = text == null ? "" : text;
    List<ModerationWordRule> enabled = rules == null ? List.of() : rules.stream()
        .filter(rule -> rule != null && rule.word() != null && !rule.word().isBlank() && rule.level() != null)
        .toList();

    List<Match> matches = new ArrayList<>();
    for (ModerationWordRule rule : enabled) {
      if (containsIgnoreCase(content, rule.word())) {
        matches.add(new Match(rule.word(), rule.level()));
      }
    }

    boolean blocked = matches.stream().anyMatch(match -> LEVEL_HIGH.equals(match.level()));
    String filtered = content;
    if (!blocked) {
      boolean hasMedium = matches.stream().anyMatch(match -> LEVEL_MEDIUM.equals(match.level()));
      if (hasMedium) {
        for (ModerationWordRule rule : enabled) {
          if (LEVEL_MEDIUM.equals(rule.level())) {
            filtered = replaceIgnoreCase(filtered, rule.word(), mask(rule.word()));
          }
        }
      }
    }
    return new Result(blocked, filtered, List.copyOf(matches));
  }

  private static boolean containsIgnoreCase(String text, String word) {
    return text.toLowerCase(Locale.ROOT).contains(word.toLowerCase(Locale.ROOT));
  }

  private static String replaceIgnoreCase(String text, String word, String replacement) {
    return Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE)
        .matcher(text)
        .replaceAll(Matcher.quoteReplacement(replacement));
  }

  private static String mask(String word) {
    return "*".repeat(Math.max(3, word.length()));
  }

  /** 命中词及其级别（用于审计记录）。 */
  public record Match(String word, String level) {
  }

  /** 过滤结果：是否拦截、处理后正文、命中词列表。 */
  public record Result(boolean blocked, String content, List<Match> matches) {
  }
}
