package com.gvchat.im.user.infra.persistence.account.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.gvchat.im.user.domain.account.port.UserAccountDataPurger;
import com.gvchat.im.user.infra.messaging.outbox.mapper.UserOutboxMapper;
import com.gvchat.im.user.infra.messaging.outbox.po.UserOutboxPo;
import com.gvchat.im.user.infra.persistence.account.mapper.UserSecurityQuestionMapper;
import com.gvchat.im.user.infra.persistence.account.mapper.UserStatusOperationMapper;
import com.gvchat.im.user.infra.persistence.account.po.UserSecurityQuestionPo;
import com.gvchat.im.user.infra.persistence.account.po.UserStatusOperationPo;
import com.gvchat.im.user.infra.persistence.device.mapper.DeviceSessionMapper;
import com.gvchat.im.user.infra.persistence.device.mapper.DeviceTokenMapper;
import com.gvchat.im.user.infra.persistence.device.po.DeviceSessionPo;
import com.gvchat.im.user.infra.persistence.device.po.DeviceTokenPo;
import com.gvchat.im.user.infra.persistence.devicekey.mapper.DeviceKeyMapper;
import com.gvchat.im.user.infra.persistence.devicekey.po.DeviceKeyPo;
import com.gvchat.im.user.infra.persistence.notificationsetting.mapper.UserNotificationSettingMapper;
import com.gvchat.im.user.infra.persistence.notificationsetting.po.UserNotificationSettingPo;
import com.gvchat.im.user.infra.persistence.privacysetting.mapper.UserPrivacySettingMapper;
import com.gvchat.im.user.infra.persistence.privacysetting.po.UserPrivacySettingPo;
import com.gvchat.im.user.infra.persistence.social.mapper.FriendRelationMapper;
import com.gvchat.im.user.infra.persistence.social.mapper.FriendRequestMapper;
import com.gvchat.im.user.infra.persistence.social.po.FriendRelationPo;
import com.gvchat.im.user.infra.persistence.social.po.FriendRequestPo;
import com.gvchat.im.user.infra.persistence.sticker.mapper.UserStickerMapper;
import com.gvchat.im.user.infra.persistence.sticker.mapper.UserStickerQuotaMapper;
import com.gvchat.im.user.infra.persistence.sticker.po.UserStickerPo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户数据硬删实现：删除该用户在用户服务拥有的全部关联数据（设备令牌/设备会话/好友/贴纸/设备密钥/设置/密保问题/审计等）。
 * 账号主记录改为墓碑化（{@code UserAccount#cancel} 置为 disabled），本清理器负责删除其私有关联数据。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MybatisUserAccountDataPurger implements UserAccountDataPurger {
  private final DeviceTokenMapper deviceTokenMapper;
  private final FriendRelationMapper friendRelationMapper;
  private final FriendRequestMapper friendRequestMapper;
  private final UserStickerMapper userStickerMapper;
  private final UserStickerQuotaMapper userStickerQuotaMapper;
  private final DeviceKeyMapper deviceKeyMapper;
  private final UserNotificationSettingMapper userNotificationSettingMapper;
  private final UserPrivacySettingMapper userPrivacySettingMapper;
  private final UserStatusOperationMapper userStatusOperationMapper;
  private final UserSecurityQuestionMapper userSecurityQuestionMapper;
  private final DeviceSessionMapper deviceSessionMapper;
  private final UserOutboxMapper userOutboxMapper;

  @Override
  public void purge(Long userId) {
    deviceTokenMapper.delete(Wrappers.<DeviceTokenPo>lambdaQuery().eq(DeviceTokenPo::getUserId, userId));
    friendRelationMapper.delete(Wrappers.<FriendRelationPo>lambdaQuery()
        .eq(FriendRelationPo::getUserId, userId).or().eq(FriendRelationPo::getFriendId, userId));
    friendRequestMapper.delete(Wrappers.<FriendRequestPo>lambdaQuery()
        .eq(FriendRequestPo::getFromUserId, userId).or().eq(FriendRequestPo::getToUserId, userId));
    userStickerMapper.delete(Wrappers.<UserStickerPo>lambdaQuery().eq(UserStickerPo::getUserId, userId));
    userStickerQuotaMapper.deleteByUserId(userId);
    deviceKeyMapper.delete(Wrappers.<DeviceKeyPo>lambdaQuery().eq(DeviceKeyPo::getUserId, userId));
    userNotificationSettingMapper.delete(
        Wrappers.<UserNotificationSettingPo>lambdaQuery().eq(UserNotificationSettingPo::getUserId, userId));
    userPrivacySettingMapper.delete(
        Wrappers.<UserPrivacySettingPo>lambdaQuery().eq(UserPrivacySettingPo::getUserId, userId));
    userStatusOperationMapper.delete(
        Wrappers.<UserStatusOperationPo>lambdaQuery().eq(UserStatusOperationPo::getUserId, userId));
    userSecurityQuestionMapper.delete(
        Wrappers.<UserSecurityQuestionPo>lambdaQuery().eq(UserSecurityQuestionPo::getUserId, userId));
    deviceSessionMapper.delete(
        Wrappers.<DeviceSessionPo>lambdaQuery().eq(DeviceSessionPo::getUserId, userId));
    userOutboxMapper.delete(Wrappers.<UserOutboxPo>lambdaQuery()
        .eq(UserOutboxPo::getAggregateId, String.valueOf(userId)));
    log.info("Purged user account data, userId={}", userId);
  }
}
