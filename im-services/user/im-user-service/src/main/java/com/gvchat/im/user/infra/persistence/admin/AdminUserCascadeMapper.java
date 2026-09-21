package com.gvchat.im.user.infra.persistence.admin;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 后台「删除用户」的级联清理 Mapper：按用户 ID 物理删除各表行并返回影响行数。
 *
 * <p>为什么集中在这一张 Mapper 上（而不是复用各域自己的仓储）：
 * <ul>
 *   <li>删除必须在**同一个事务**内完成并逐表回执行数，交给分散的仓储方法拿不到统一口径；</li>
 *   <li>IM 各服务共用同一个库（{@code im_server}），群成员 / 频道订阅 / 收藏等行虽然归属会话域与消息域，
 *       但物理上就在同一库里——跨服务调用无法参与本事务，反而会留下半删状态；</li>
 *   <li><b>不触碰任何 {@code msg_message} 等消息本体表</b>：门店确认消息保留，避免对方聊天记录出现空洞。</li>
 * </ul>
 */
@Mapper
public interface AdminUserCascadeMapper {

  /** 设备推送令牌：删掉后不会再向已删除账号推消息。 */
  @Delete("DELETE FROM user_device_token WHERE user_id = #{userId}")
  int deleteDeviceTokens(@Param("userId") Long userId);

  /** 登录态：所有设备会话失效。 */
  @Delete("DELETE FROM user_device_session WHERE user_id = #{userId}")
  int deleteDeviceSessions(@Param("userId") Long userId);

  /** 设备密钥：端到端加密的本地密钥材料。 */
  @Delete("DELETE FROM user_device_key WHERE user_id = #{userId}")
  int deleteDeviceKeys(@Param("userId") Long userId);

  /** 个人通知设置。 */
  @Delete("DELETE FROM user_notification_setting WHERE user_id = #{userId}")
  int deleteNotificationSettings(@Param("userId") Long userId);

  /** 隐私设置。 */
  @Delete("DELETE FROM user_privacy_setting WHERE user_id = #{userId}")
  int deletePrivacySettings(@Param("userId") Long userId);

  /** 密保问题。 */
  @Delete("DELETE FROM user_security_question WHERE user_id = #{userId}")
  int deleteSecurityQuestions(@Param("userId") Long userId);

  /**
   * 消息收藏（{@code msg_message_favorite}，消息域同库表）。
   *
   * <p>收藏是账号的**个人数据**，随账号删除；只删收藏关系，不动 {@code msg_message} 消息本体。
   */
  @Delete("DELETE FROM msg_message_favorite WHERE user_id = #{userId}")
  int deleteFavorites(@Param("userId") Long userId);

  /** 好友关系（双向行）：对方联系人少一个，符合「人已不存在」。 */
  @Delete("DELETE FROM user_friend WHERE user_id = #{userId} OR friend_id = #{userId}")
  int deleteFriendRelations(@Param("userId") Long userId);

  /** 好友申请（双向行）。 */
  @Delete("DELETE FROM user_friend_request WHERE from_user_id = #{userId} OR to_user_id = #{userId}")
  int deleteFriendRequests(@Param("userId") Long userId);

  /** 群成员行（会话域同库表 {@code conversation_group_member}）。 */
  @Delete("DELETE FROM conversation_group_member WHERE user_id = #{userId}")
  int deleteGroupMembers(@Param("userId") Long userId);

  /** 频道订阅行（会话域同库表 {@code channel_subscription}）。 */
  @Delete("DELETE FROM channel_subscription WHERE user_id = #{userId}")
  int deleteChannelSubscriptions(@Param("userId") Long userId);

  /**
   * 密聊会话行（会话域同库表 {@code secret_chat}）。
   *
   * <p>口径与用户自助注销一致：账号不存在后密聊会话不再成立，参与行一并删除。
   */
  @Delete("DELETE FROM secret_chat WHERE user_a = #{userId} OR user_b = #{userId}")
  int deleteSecretChats(@Param("userId") Long userId);

  /** 私密群聊成员行（会话域同库表 {@code conversation_secret_group_member}）。 */
  @Delete("DELETE FROM conversation_secret_group_member WHERE user_id = #{userId}")
  int deleteSecretGroupMembers(@Param("userId") Long userId);

  /** 用户表情。 */
  @Delete("DELETE FROM user_sticker WHERE user_id = #{userId}")
  int deleteStickers(@Param("userId") Long userId);

  /** 用户表情名额。 */
  @Delete("DELETE FROM user_sticker_quota WHERE user_id = #{userId}")
  int deleteStickerQuota(@Param("userId") Long userId);

  /** 后台状态变更操作审计行（随账号一并清理）。 */
  @Delete("DELETE FROM user_admin_status_operation WHERE user_id = #{userId}")
  int deleteStatusOperations(@Param("userId") Long userId);

  /** 用户域待发布事件：账号已删，事件不再有意义（{@code aggregate_id} 是字符串列，显式 CAST 避免隐式转换）。 */
  @Delete("DELETE FROM user_outbox WHERE aggregate_id = CAST(#{userId} AS CHAR)")
  int deleteOutboxEvents(@Param("userId") Long userId);
}
