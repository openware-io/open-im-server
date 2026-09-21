package com.gvchat.im.user.application.sticker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gvchat.common.dto.PageResult;
import com.gvchat.common.exception.ApiException;
import com.gvchat.im.user.application.sticker.command.AddUserStickerCommand;
import com.gvchat.im.user.domain.sticker.model.UserSticker;
import com.gvchat.im.user.domain.sticker.repository.UserStickerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StickerApplicationServiceTest {
  @Test
  void shouldNeverReserveMoreThanTwoHundredStickersUnderConcurrentAdds() throws Exception {
    InMemoryUserStickerRepository repository = new InMemoryUserStickerRepository();
    StickerApplicationService service = new StickerApplicationService(repository);
    try (var executor = Executors.newFixedThreadPool(16)) {
      List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int index = 0; index < 240; index++) {
        int sequence = index;
        tasks.add(() -> {
          try {
            service.add(1L, new AddUserStickerCommand("https://example.com/" + sequence, null));
            return true;
          } catch (ApiException exception) {
            return false;
          }
        });
      }
      List<Future<Boolean>> results = executor.invokeAll(tasks);
      assertEquals(200, results.stream().filter(result -> {
        try {
          return result.get();
        } catch (Exception exception) {
          throw new IllegalStateException(exception);
        }
      }).count());
    }
    assertEquals(200, repository.findByUserId(1L).size());
  }

  @Test
  void shouldRejectBatchWhenItCannotReserveAllRequestedSlots() {
    InMemoryUserStickerRepository repository = new InMemoryUserStickerRepository();
    StickerApplicationService service = new StickerApplicationService(repository);
    for (int index = 0; index < 199; index++) {
      service.add(1L, new AddUserStickerCommand("https://example.com/" + index, null));
    }

    assertThrows(ApiException.class, () -> service.batchAdd(1L, List.of(
        new AddUserStickerCommand("https://example.com/200", null),
        new AddUserStickerCommand("https://example.com/201", null))));
    assertEquals(199, repository.findByUserId(1L).size());
  }

  private static final class InMemoryUserStickerRepository implements UserStickerRepository {
    private final List<UserSticker> stickers = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private int stickerCount;

    @Override
    public synchronized List<UserSticker> findByUserId(Long userId) {
      return stickers.stream().filter(sticker -> userId.equals(sticker.getUserId())).toList();
    }

    @Override
    public synchronized boolean reserveSlots(Long userId, int slotCount, int maximum) {
      if (stickerCount + slotCount > maximum) {
        return false;
      }
      stickerCount += slotCount;
      return true;
    }

    @Override
    public synchronized void releaseSlots(Long userId, int slotCount) {
      stickerCount -= slotCount;
    }

    @Override
    public synchronized List<UserSticker> saveAll(List<UserSticker> newStickers) {
      newStickers.forEach(sticker -> {
        sticker.assignId(ids.incrementAndGet());
        stickers.add(sticker);
      });
      return newStickers;
    }

    @Override
    public Optional<UserSticker> findById(Long id) {
      return stickers.stream().filter(sticker -> id.equals(sticker.getId())).findFirst();
    }

    @Override
    public PageResult<UserSticker> findForAdmin(Long userId, int page, int pageSize) {
      return PageResult.<UserSticker>builder().items(List.of()).total(0).page(page).pageSize(pageSize).build();
    }

    @Override
    public synchronized boolean deleteByIdAndUserId(Long id, Long userId) {
      return stickers.removeIf(sticker -> id.equals(sticker.getId()) && userId.equals(sticker.getUserId()));
    }

    @Override
    public void deleteById(Long id) {
      stickers.removeIf(sticker -> id.equals(sticker.getId()));
    }
  }
}
