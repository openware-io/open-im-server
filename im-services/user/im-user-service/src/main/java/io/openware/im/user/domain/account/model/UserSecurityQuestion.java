package io.openware.im.user.domain.account.model;

/**
 * 用户密保问题实体：承载「密保问题 + 答案哈希」，用于密保问题找回密码。
 *
 * <p>答案以 bcrypt 哈希存储（复用 {@code PasswordHasher}），领域层只保留哈希与问题文本，
 * 答案校验由应用层调用哈希端口完成，避免领域层耦合加密实现。</p>
 *
 * <p><b>为什么不带审计列</b>（standards/10 L299）：{@code createdBy}/{@code createdAt}/
 * {@code updatedBy}/{@code updatedAt} 是记账信息，由适配器在写库时确定。
 * 这四项在本服务里**没有领域或应用层读者**——只有适配器把它们映射到 PO，因此不需要载体类型。
 * 操作人恒为用户本人，适配器可由 {@code userId} 直接得出，不必穿过业务签名。
 */
public class UserSecurityQuestion {
  private Long id;
  private Long userId;
  private String question;
  private String answerHash;

  /** 首次设置密保问题：答案必须已哈希。 */
  public static UserSecurityQuestion set(Long userId, String question, String answerHash) {
    UserSecurityQuestion entity = new UserSecurityQuestion();
    entity.userId = userId;
    entity.question = question;
    entity.answerHash = answerHash;
    return entity;
  }

  /** 更新密保问题与答案（答案必须已哈希）。 */
  public void update(String question, String answerHash) {
    this.question = question;
    this.answerHash = answerHash;
  }

  /** 问题是否匹配：比较前去除首尾空白。 */
  public boolean matchesQuestion(String expected) {
    if (this.question == null || expected == null) {
      return false;
    }
    return this.question.trim().equals(expected.trim());
  }

  public void restore(Long id, Long userId, String question, String answerHash) {
    this.id = id;
    this.userId = userId;
    this.question = question;
    this.answerHash = answerHash;
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public String getQuestion() { return question; }
  public String getAnswerHash() { return answerHash; }

  public void assignId(Long id) { this.id = id; }
}
