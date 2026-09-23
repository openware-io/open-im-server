package io.openware.platform.customer.infra.schedule;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.openware.platform.customer.application.MemberApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 姓名盲索引回填任务的调度回归：开关关闭时完全不碰数据库；服务抛异常时不得逃出（
 * 否则调度线程会因未捕获异常停摆，后续轮次不再执行）。
 */
class NameIndexRebuildJobTest {

  private final MemberApplicationService memberService = mock(MemberApplicationService.class);
  private final MemberNameIndexRebuildJob job = new MemberNameIndexRebuildJob(memberService);

  @Test
  void disabledJobDoesNotTouchTheDatabase() {
    ReflectionTestUtils.setField(job, "enabled", false);

    job.rebuildMissingNameIndex();

    verify(memberService, never()).rebuildNameIndexAllTenants();
  }

  @Test
  void enabledJobDelegatesToTheService() {
    ReflectionTestUtils.setField(job, "enabled", true);

    job.rebuildMissingNameIndex();

    verify(memberService).rebuildNameIndexAllTenants();
  }

  @Test
  void serviceFailureNeverEscapesTheScheduledMethod() {
    ReflectionTestUtils.setField(job, "enabled", true);
    when(memberService.rebuildNameIndexAllTenants()).thenThrow(new IllegalStateException("db down"));

    assertDoesNotThrow(() -> job.rebuildMissingNameIndex());
  }
}
