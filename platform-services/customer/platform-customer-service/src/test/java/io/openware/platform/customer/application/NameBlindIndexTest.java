package io.openware.platform.customer.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 姓名盲索引纯函数单测：归一化 / 1..3-gram / SHA-256 token / 「所有关键词 token 都命中」的包含语义。
 *
 * <p>这些规则是写入侧（{@code cst_member_name_token}）与检索侧共用的唯一口径，两边必须完全一致，
 * 因此这里的断言是「搜得到」的地基：规则错了，SQL 再对也搜不到姓名。
 */
class NameBlindIndexTest {

  @Test
  void normalizeTrimsRemovesAllWhitespaceAndLowercasesAsciiOnly() {
    assertEquals("johnsmith", NameBlindIndex.normalize(" John Smith "));
    assertEquals("johnsmith", NameBlindIndex.normalize("John\tSmith"));
    // 全角空格 U+3000 也是空白，必须去掉
    assertEquals("johnsmith", NameBlindIndex.normalize("JOHN\u3000SMITH"));
    assertEquals("张三", NameBlindIndex.normalize(" 张 三 "));
    // 中文没有大小写，原样保留
    assertEquals("张三丰", NameBlindIndex.normalize("张三丰"));
    assertEquals("", NameBlindIndex.normalize(null));
    assertEquals("", NameBlindIndex.normalize("   "));
  }

  @Test
  void gramsForChineseNameFollowSlidingWindowWithMaxLengthThree() {
    assertEquals(Set.of("张", "三", "张三"), NameBlindIndex.grams("张三"));
    assertEquals(Set.of("张", "三", "丰", "张三", "三丰", "张三丰"), NameBlindIndex.grams("张三丰"));
  }

  @Test
  void gramsForNameShorterThanThreeUseActualLength() {
    assertEquals(Set.of("李"), NameBlindIndex.grams("李"));
    assertEquals(3, NameBlindIndex.grams("李四").size());
  }

  @Test
  void gramsForEnglishNameUseNormalizedFormAndNeverExceedThreeChars() {
    Set<String> grams = NameBlindIndex.grams("John Smith");
    // johnsmith 的 3-gram 是 joh / ohn / hns / nsm / smi / mit / ith
    assertTrue(grams.containsAll(Set.of("j", "o", "h", "joh", "ohn", "smi", "ith")), grams.toString());
    // 归一化后是 johnsmith（大写变小写、空格去掉）
    assertFalse(grams.contains("John"));
    assertFalse(grams.contains("Smith"));
    // 最长 3-gram：整串不是 token，4 字窗口也不存在
    assertFalse(grams.contains("johnsmith"));
    assertFalse(grams.contains("john"));
    assertFalse(grams.contains("smit"));
    assertTrue(grams.stream().allMatch(gram -> gram.length() <= 3));
  }

  @Test
  void gramsForLongNameAreSlidingWindowsUpToThree() {
    String longName = "abcdefghijklmnopqrst";
    Set<String> grams = NameBlindIndex.grams(longName);
    assertEquals(20 + 19 + 18, grams.size());
    assertTrue(grams.contains("abc"));
    assertTrue(grams.contains("rst"));
    assertFalse(grams.contains("abcd"));
  }

  @Test
  void blankTextProducesNoTokens() {
    assertTrue(NameBlindIndex.tokenize(null).isEmpty());
    assertTrue(NameBlindIndex.tokenize("   ").isEmpty());
    assertTrue(NameBlindIndex.tokenize("\u3000\t\n").isEmpty());
  }

  @Test
  void tokensAreSha256HexAndIgnoreWhitespaceAndAsciiCase() {
    Set<String> tokens = NameBlindIndex.tokenize(" 张 三 ");
    assertEquals(NameBlindIndex.tokenize("张三"), tokens);
    assertEquals(3, tokens.size());
    assertTrue(tokens.contains(NameBlindIndex.sha256Hex("张三")));
    assertTrue(tokens.contains(NameBlindIndex.sha256Hex("张")));
    for (String token : tokens) {
      assertEquals(64, token.length(), token);
      assertTrue(token.matches("[0-9a-f]{64}"), token);
    }
    assertEquals(NameBlindIndex.tokenize("John Smith"), NameBlindIndex.tokenize("johnsmith"));
  }

  /** 「包含」语义：真子串的所有 gram 必然被文档 token 覆盖；非连续子串不要求全部命中。 */
  @Test
  void queryTokensAreSubsetOfDocumentTokensForContainedSubstring() {
    Set<String> document = NameBlindIndex.tokenize("张三丰");
    for (String keyword : List.of("张", "三", "丰", "张三", "三丰", "张三丰")) {
      assertTrue(document.containsAll(NameBlindIndex.tokenize(keyword)), "关键词应命中: " + keyword);
    }
    assertFalse(document.containsAll(NameBlindIndex.tokenize("张丰")), "「张丰」不是连续子串，不应全命中");
    assertFalse(document.containsAll(NameBlindIndex.tokenize("四")), "无关字不应命中");
  }
}
