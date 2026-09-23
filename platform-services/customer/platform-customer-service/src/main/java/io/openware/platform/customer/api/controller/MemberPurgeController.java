package io.openware.platform.customer.api.controller;

import io.openware.platform.customer.application.MemberPurgeApplicationService;
import io.openware.platform.customer.application.MemberPurgeApplicationService.PurgeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「无 IM 关联客户」清理入口（运营侧，二次确认后调用）。
 *
 * <p>单独成类，避免改动既有 {@code MemberController}（不牵动它的既有测试）。
 * 判定与两道闸（有 IM 关联拒绝 / 有业务引用拒绝）在 {@link MemberPurgeApplicationService}。
 */
@RestController
@RequestMapping("/business/members")
@RequiredArgsConstructor
public class MemberPurgeController {

    private final MemberPurgeApplicationService purgeService;

    /**
     * 清理一个「无 IM 关联且无业务引用」的客户档案：返回客户号与被删的附属行数。
     *
     * <p>拒绝时的错误码可执行：{@code MEMBER_HAS_IM_BINDING}（已关联 IM，不该清）、
     * {@code MEMBER_HAS_REFERENCES}（已有订单/预约/储值/积分/券，需人工核对）。
     */
    @PostMapping("/{id}/purge-unlinked")
    public PurgeResult purgeUnlinked(@PathVariable Long id) {
        return purgeService.purgeUnlinked(id);
    }
}
