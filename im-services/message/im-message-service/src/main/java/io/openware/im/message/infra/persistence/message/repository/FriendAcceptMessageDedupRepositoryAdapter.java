package io.openware.im.message.infra.persistence.message.repository;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.openware.im.message.domain.message.repository.FriendAcceptMessageDedupRepository;
import io.openware.im.message.infra.persistence.message.mapper.FriendAcceptMessageDedupMapper;
import io.openware.im.message.infra.persistence.message.po.FriendAcceptMessageDedupPo;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FriendAcceptMessageDedupRepositoryAdapter implements FriendAcceptMessageDedupRepository {
  private final FriendAcceptMessageDedupMapper mapper;

  @Override
  public Optional<Record> findByRequestId(long requestId) {
    FriendAcceptMessageDedupPo po = mapper.selectOne(Wrappers.<FriendAcceptMessageDedupPo>lambdaQuery()
        .eq(FriendAcceptMessageDedupPo::getRequestId, requestId).last("LIMIT 1"));
    return Optional.ofNullable(po).map(this::toRecord);
  }

  @Override
  public boolean createPending(long requestId, String eventId, LocalDateTime createdAt) {
    FriendAcceptMessageDedupPo po = new FriendAcceptMessageDedupPo();
    po.setRequestId(requestId);
    po.setEventId(eventId);
    po.setStatus("PENDING");
    po.setCreatedAt(createdAt);
    po.setUpdatedAt(createdAt);
    try {
      mapper.insert(po);
      return true;
    } catch (DuplicateKeyException duplicate) {
      return false;
    }
  }

  @Override
  public void mark(long requestId, String status, String msgId, LocalDateTime updatedAt) {
    FriendAcceptMessageDedupPo po = mapper.selectOne(Wrappers.<FriendAcceptMessageDedupPo>lambdaQuery()
        .eq(FriendAcceptMessageDedupPo::getRequestId, requestId).last("LIMIT 1"));
    if (po == null) {
      throw new IllegalStateException("Friend acceptance dedup record not found: " + requestId);
    }
    po.setStatus(status);
    po.setMsgId(msgId);
    po.setUpdatedAt(updatedAt);
    mapper.updateById(po);
  }

  private Record toRecord(FriendAcceptMessageDedupPo po) {
    return new Record(po.getRequestId(), po.getEventId(), po.getStatus(), po.getMsgId());
  }
}
