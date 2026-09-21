package com.gvchat.im.user.application.sticker;

import com.gvchat.common.exception.ApiException;
import com.gvchat.common.http.HttpStatusCodes;
import com.gvchat.im.user.application.sticker.command.AddUserStickerCommand;
import com.gvchat.im.user.application.sticker.result.UserStickerResult;
import com.gvchat.im.user.domain.sticker.model.UserSticker;
import com.gvchat.im.user.domain.sticker.repository.UserStickerRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StickerApplicationService {
  private static final int MAX_STICKERS = 200;

  private final UserStickerRepository userStickerRepository;

  @Transactional(readOnly = true)
  public List<UserStickerResult> listByUser(Long userId) {
    return userStickerRepository.findByUserId(userId).stream().map(this::toResult).toList();
  }

  @Transactional
  public UserStickerResult add(Long userId, AddUserStickerCommand command) {
    return addAll(userId, List.of(command)).getFirst();
  }

  @Transactional
  public List<UserStickerResult> batchAdd(Long userId, List<AddUserStickerCommand> commands) {
    return addAll(userId, commands);
  }

  @Transactional
  public void remove(Long userId, Long stickerId) {
    if (!userStickerRepository.deleteByIdAndUserId(stickerId, userId)) {
      throw new ApiException(HttpStatusCodes.NOT_FOUND, "Sticker not found");
    }
    userStickerRepository.releaseSlots(userId, 1);
  }

  private List<UserStickerResult> addAll(Long userId, List<AddUserStickerCommand> commands) {
    if (commands.isEmpty()) {
      return List.of();
    }
    if (!userStickerRepository.reserveSlots(userId, commands.size(), MAX_STICKERS)) {
      throw new ApiException(HttpStatusCodes.BAD_REQUEST, "Sticker limit reached");
    }
    LocalDateTime occurredAt = LocalDateTime.now();
    List<UserSticker> stickers = commands.stream()
        .map(command -> UserSticker.create(userId, command.url(), command.thumbnail(), occurredAt))
        .toList();
    return userStickerRepository.saveAll(stickers).stream().map(this::toResult).toList();
  }

  private UserStickerResult toResult(UserSticker sticker) {
    return new UserStickerResult(
        sticker.getId(), sticker.getUserId(), sticker.getUrl(), sticker.getThumbnail(), sticker.getSortOrder(),
        sticker.getCreatedBy(), sticker.getCreatedAt(), sticker.getUpdatedBy(), sticker.getUpdatedAt());
  }
}
