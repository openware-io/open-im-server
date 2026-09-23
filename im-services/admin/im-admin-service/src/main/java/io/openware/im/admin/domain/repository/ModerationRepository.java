package io.openware.im.admin.domain.repository;

import io.openware.im.admin.domain.moderation.Report;
import io.openware.im.admin.domain.moderation.SensitiveWord;
import io.openware.im.admin.domain.moderation.Violation;
import io.openware.common.enums.ReportStatus;
import io.openware.common.enums.ViolationAction;
import java.util.List;
import java.util.Optional;

public interface ModerationRepository {
  Optional<Report> findReportById(Long id);

  boolean hasPendingReport(Long reporterId, Long targetId);

  long countPendingReports();

  List<Report> findReports(ReportStatus status, int offset, int pageSize);

  long countReports(ReportStatus status);

  Report saveReport(Report report);

  SensitiveWord saveSensitiveWord(SensitiveWord sensitiveWord);

  Optional<SensitiveWord> findSensitiveWordById(Long id);

  List<SensitiveWord> findSensitiveWords(int offset, int pageSize);

  long countSensitiveWords();

  void deleteSensitiveWord(Long id);

  /** 全部已启用的审核词（供消息服务过滤链路拉取）。 */
  List<SensitiveWord> findEnabledSensitiveWords();

  Violation saveViolation(Violation violation);

  List<Violation> findViolations(Long targetId, ViolationAction action, int offset, int pageSize);

  long countViolations(Long targetId, ViolationAction action);
}
