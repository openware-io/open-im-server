package io.openware.platform.customer.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 姓名/客户号**盲索引**（blind index）的纯函数实现：把一段文本切成 1/2/3-gram 并逐个做 SHA-256。
 *
 * <p><b>为什么需要</b>：{@code cst_member.name_cipher} 是随机 IV 的 AES-256-GCM 密文
 * （{@code AesGcmCipher} 每条密文的 IV 都不同），SQL 既不能 {@code LIKE} 也不能建索引，
 * 手机号靠额外的 SHA-256 摘要列做精确匹配，姓名此前完全不可检索。
 *
 * <p><b>规则</b>（与 {@code cst_member_name_token} 表、检索侧必须完全一致）：
 * <ol>
 *   <li>归一化：去掉首尾空白 + 去掉**所有**空白字符（含全角空格 U+3000）+ ASCII 大写转小写（中文不动）；</li>
 *   <li>对归一化结果取滑动窗口 1..3-gram；长度不足 3 时按实际长度出 1..n gram
 *       （「张三」→ {张, 三, 张三}；「张三丰」→ {张, 三, 丰, 张三, 三丰, 张三丰}）；
 *   <li>每个 gram 的 {@code SHA-256} 十六进制（小写，定长 64）即 token；</li>
 *   <li>检索时把关键词切 token，要求**所有**关键词 token 都命中（AND）→ 近似「包含」语义。</li>
 * </ol>
 *
 * <p><b>已知边界</b>：gram-AND 是「包含」的**候选集**语义，不是严格的子串判定
 * （例如索引里同时放了姓名与客户号的 token 时，关键词的各 gram 可能分别命中两段文本）。
 * 它只会带来少量假阳性、不会漏（真子串的 gram 必然全部命中）。要精确判定必须在应用层解密比对，
 * 那会破坏「分页 + 总数一致」的口径，因此本实现选择候选集语义（见迁移 V5 注释）。
 */
public final class NameBlindIndex {

    /** 最大 gram 长度：1/2/3-gram。 */
    private static final int MAX_GRAM = 3;

    private NameBlindIndex() {
    }

    /**
     * 归一化：去首尾空白、去掉所有空白字符、ASCII 大写转小写。
     *
     * @param value 原始文本，可为 {@code null}
     * @return 归一化后的文本，{@code null} 视为空串
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            // 去掉所有空白字符（含全角空格 U+3000，Character.isWhitespace 判定为 true）
            if (Character.isWhitespace(c)) {
                continue;
            }
            // 仅 ASCII 大写转小写：中文/其它 Unicode 字符原样保留，避免依赖 Locale 的 toLowerCase 把
            // 某些字符（如土耳其语 İ）改写成不可预测的形态，导致写入/查询两侧规则不一致。
            normalized.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return normalized.toString();
    }

    /**
     * 归一化文本的 1..3-gram 集合（去重、保持插入顺序，便于单测与幂等写入）。
     *
     * @param value 原始文本，可为 {@code null}
     * @return gram 集合；文本为空/全空白时返回空集合
     */
    public static Set<String> grams(String value) {
        String normalized = normalize(value);
        Set<String> grams = new LinkedHashSet<>();
        for (int n = 1; n <= MAX_GRAM; n++) {
            for (int i = 0; i + n <= normalized.length(); i++) {
                grams.add(normalized.substring(i, i + n));
            }
        }
        return grams;
    }

    /**
     * 归一化文本的 token 集合：{@code token = SHA-256(gram) 十六进制}。
     *
     * @param value 原始文本，可为 {@code null}
     * @return token 集合；文本为空/全空白时返回空集合
     */
    public static Set<String> tokenize(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String gram : grams(value)) {
            tokens.add(sha256Hex(gram));
        }
        return tokens;
    }

    /** SHA-256 十六进制（小写，64 字符）；{@code null} 返回 {@code null}。 */
    public static String sha256Hex(String value) {
        if (value == null) {
            return null;
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
