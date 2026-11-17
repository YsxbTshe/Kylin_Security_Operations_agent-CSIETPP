package com.superskidder.kylinops.backend.agentruntime;

import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.security.AccessControlService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审批端点。
 *
 * <p>租户边界：一律取会话组织 {@link AccessControlService#currentOrganizationId()}，
 * 不接受请求参数覆盖——此前四个端点都是 {@code tenantId != null ? tenantId : 当前组织}，
 * 任意登录用户带 {@code ?tenantId=<他人组织>} 即可读取甚至审批其他组织的 R3/R4 写操作。
 * 与同包 {@link AgentRuntimeQueryController} 的口径保持一致。
 *
 * <p>权限：读端点要求 {@code agent:read}；决策端点要求 {@code agent:approve}
 * （V8 迁移新增的专用码，授予 platform_admin / org_admin / security_operator，
 * 与 V6 迁移中 agent-approval decide 的 ABAC 主体集合完全一致。此前借用
 * {@code incident:respond}——主体集相同、功能上正确，但审批一次 R3 写操作并不总是
 * 在处置安全事件，语义不符）。功能权限只是第一道闸：具体某一单能不能批，仍由
 * {@link ApprovalService#decide} 的资源级 ABAC 判定（含风险上限、发起人不可自批）决定。
 */
@RestController
@RequestMapping("/api/v2/approvals")
public class ApprovalController {

    /** 只读查看待审/详情。 */
    private static final String PERMISSION_READ = "agent:read";
    /** 做出批准/拒绝决策。 */
    private static final String PERMISSION_DECIDE = "agent:approve";

    private final ApprovalService approvalService;
    private final AgentRuntimeStore runtimeStore;
    private final AccessControlService accessControl;

    public ApprovalController(ApprovalService approvalService, AgentRuntimeStore runtimeStore,
                              AccessControlService accessControl) {
        this.approvalService = approvalService;
        this.runtimeStore = runtimeStore;
        this.accessControl = accessControl;
    }

    @GetMapping
    public List<ApprovalService.ApprovalView> listPending(
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String toolId,
            @RequestParam(required = false) String nodeId) {
        accessControl.requirePermission(PERMISSION_READ);
        return approvalService.listPending(accessControl.currentOrganizationId(), runId, toolId, nodeId);
    }

    @GetMapping("/{approvalId}")
    public ApprovalService.ApprovalView detail(@PathVariable String approvalId) {
        accessControl.requirePermission(PERMISSION_READ);
        return approvalService.findById(accessControl.currentOrganizationId(), approvalId)
                .orElseThrow(() -> new com.superskidder.kylinops.backend.common.AppException(
                        HttpStatus.NOT_FOUND, "Approval not found: " + approvalId));
    }

    @PostMapping("/{approvalId}/decide")
    public ApprovalService.ApprovalView decide(
            @PathVariable String approvalId,
            @RequestBody DecideRequest request) {
        accessControl.requirePermission(PERMISSION_DECIDE);
        String approver = accessControl.currentUser().userId();
        return approvalService.decide(accessControl.currentOrganizationId(), approvalId, approver,
                List.copyOf(accessControl.currentUser().roleCodes()),
                request.approve(), request.reason());
    }

    @PostMapping("/batch-decide")
    @ResponseStatus(HttpStatus.OK)
    public List<ApprovalService.ApprovalView> batchDecide(@RequestBody BatchDecideRequest request) {
        accessControl.requirePermission(PERMISSION_DECIDE);
        String approver = accessControl.currentUser().userId();
        return approvalService.batchDecide(accessControl.currentOrganizationId(), request.approvalIds(), approver,
                List.copyOf(accessControl.currentUser().roleCodes()),
                request.approve(), request.reason());
    }

    public record DecideRequest(boolean approve, String reason) {}
    public record BatchDecideRequest(List<String> approvalIds, boolean approve, String reason) {}
}
