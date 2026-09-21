package com.gvchat.im.conversation.infra.persistence.secretgroupchat.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gvchat.im.conversation.domain.secretgroupchat.model.SecretGroupMember;
import com.gvchat.im.conversation.domain.secretgroupchat.repository.SecretGroupMemberRepository;
import com.gvchat.im.conversation.infra.persistence.secretgroupchat.mapper.SecretGroupMemberMapper;
import com.gvchat.im.conversation.infra.persistence.secretgroupchat.po.SecretGroupMemberPo;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MybatisSecretGroupMemberRepository implements SecretGroupMemberRepository {
  private final SecretGroupMemberMapper mapper;

  @Override
  public SecretGroupMember save(SecretGroupMember member) {
    SecretGroupMemberPo po = toPo(member);
    if (po.getId() == null) {
      mapper.insert(po);
    } else {
      mapper.updateById(po);
    }
    return toDomain(po);
  }

  @Override
  public List<SecretGroupMember> findByGroupId(long groupId) {
    return mapper.selectList(new LambdaQueryWrapper<SecretGroupMemberPo>()
        .eq(SecretGroupMemberPo::getSecretGroupId, groupId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public Optional<SecretGroupMember> findByGroupIdAndUserId(long groupId, long userId) {
    return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<SecretGroupMemberPo>()
        .eq(SecretGroupMemberPo::getSecretGroupId, groupId)
        .eq(SecretGroupMemberPo::getUserId, userId))).map(this::toDomain);
  }

  @Override
  public List<SecretGroupMember> findByUserId(long userId) {
    return mapper.selectList(new LambdaQueryWrapper<SecretGroupMemberPo>()
        .eq(SecretGroupMemberPo::getUserId, userId))
        .stream().map(this::toDomain).toList();
  }

  @Override
  public void deleteByGroupId(long groupId) {
    mapper.delete(new LambdaQueryWrapper<SecretGroupMemberPo>()
        .eq(SecretGroupMemberPo::getSecretGroupId, groupId));
  }

  @Override
  public void deleteByGroupIdAndUserId(long groupId, long userId) {
    mapper.delete(new LambdaQueryWrapper<SecretGroupMemberPo>()
        .eq(SecretGroupMemberPo::getSecretGroupId, groupId)
        .eq(SecretGroupMemberPo::getUserId, userId));
  }

  private SecretGroupMemberPo toPo(SecretGroupMember member) {
    SecretGroupMemberPo po = new SecretGroupMemberPo();
    po.setId(member.getId());
    po.setSecretGroupId(member.getSecretGroupId());
    po.setUserId(member.getUserId());
    po.setDevicePublicKey(member.getDevicePublicKey());
    po.setJoinedAt(member.getJoinedAt());
    return po;
  }

  private SecretGroupMember toDomain(SecretGroupMemberPo po) {
    return SecretGroupMember.restore(po.getId(), po.getSecretGroupId(), po.getUserId(), po.getDevicePublicKey(),
        po.getJoinedAt());
  }
}
