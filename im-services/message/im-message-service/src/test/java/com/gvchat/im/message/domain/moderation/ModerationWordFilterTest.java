package com.gvchat.im.message.domain.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ModerationWordFilterTest {
  @Test
  void highLevelWordShouldBlock() {
    ModerationWordFilter.Result result = ModerationWordFilter.filter(
        "please stop", List.of(new ModerationWordRule("stop", "high")));

    assertThat(result.blocked()).isTrue();
    assertThat(result.matches()).extracting(ModerationWordFilter.Match::word).containsExactly("stop");
  }

  @Test
  void mediumLevelWordShouldBeMasked() {
    ModerationWordFilter.Result result = ModerationWordFilter.filter(
        "this is bad", List.of(new ModerationWordRule("bad", "medium")));

    assertThat(result.blocked()).isFalse();
    assertThat(result.content()).isEqualTo("this is ***");
  }

  @Test
  void lowLevelWordShouldOnlyRecord() {
    ModerationWordFilter.Result result = ModerationWordFilter.filter(
        "this is spam", List.of(new ModerationWordRule("spam", "low")));

    assertThat(result.blocked()).isFalse();
    assertThat(result.content()).isEqualTo("this is spam");
    assertThat(result.matches()).hasSize(1);
    assertThat(result.matches().getFirst().level()).isEqualTo("low");
  }

  @Test
  void matchingShouldBeCaseInsensitive() {
    ModerationWordFilter.Result result = ModerationWordFilter.filter(
        "BAD thing", List.of(new ModerationWordRule("bad", "medium")));

    assertThat(result.blocked()).isFalse();
    assertThat(result.content()).isEqualTo("*** thing");
  }

  @Test
  void highLevelShouldWinOverMedium() {
    ModerationWordFilter.Result result = ModerationWordFilter.filter(
        "bad stop", List.of(new ModerationWordRule("bad", "medium"), new ModerationWordRule("stop", "high")));

    assertThat(result.blocked()).isTrue();
    assertThat(result.content()).isEqualTo("bad stop");
  }

  @Test
  void noMatchShouldReturnUnchanged() {
    ModerationWordFilter.Result result = ModerationWordFilter.filter(
        "hello", List.of(new ModerationWordRule("bad", "medium")));

    assertThat(result.blocked()).isFalse();
    assertThat(result.content()).isEqualTo("hello");
    assertThat(result.matches()).isEmpty();
  }

  @Test
  void nullTextShouldBeSafe() {
    ModerationWordFilter.Result result = ModerationWordFilter.filter(
        null, List.of(new ModerationWordRule("bad", "high")));

    assertThat(result.blocked()).isFalse();
    assertThat(result.content()).isEmpty();
  }
}
