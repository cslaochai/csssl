package com.csssl.workflow;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流注册表：集中声明系统里所有审批流（RA 申请 / 证书操作单 / 公司变更单 / SUB_CA 重建）。
 *
 * <p>这是「引入工作流」的核心——流程步骤、每步处理角色、分派策略都在这里配置，
 * 前端按定义生成流程图，后端按定义推导当前步骤与下一处理人，避免各页硬编码、口径不一。
 * 新增一条审批流只需在此注册，无需改前端画布。</p>
 */
@Component
public class WorkflowRegistry {

    private final Map<String, WorkflowDefinition> defs = new LinkedHashMap<>();

    public WorkflowRegistry() {
        defs.put("CERT_APPLICATION", raApplication());
        defs.put("CERT_OP", certOp());
        defs.put("COMPANY_OP", companyOp());
        defs.put("SUB_CA_REBUILD", subCaRebuild());
    }

    public WorkflowDefinition get(String bizType) {
        return defs.get(bizType);
    }

    public List<WorkflowDefinition> all() {
        return new ArrayList<>(defs.values());
    }

    private static WorkflowDefinition raApplication() {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setBizType("CERT_APPLICATION");
        d.setName("RA 证书申请");
        d.setDescription("录入员提交 → 审核员审批（单必归属到人，按积压最少自动分派）→ 操作员签发");
        d.setSteps(List.of(
                new WorkflowStep("SUBMIT", "提交申请", "录入员（OPERATOR）", List.of("OPERATOR"), AssignStrategy.POOL, false),
                new WorkflowStep("REVIEW", "审核审批", "审核员 / RA管理员", List.of("APPROVER", "RA_ADMIN"), AssignStrategy.SINGLE_LEAST_BACKLOG, false),
                new WorkflowStep("ISSUE", "签发证书", "操作员 / 证书操作员", List.of("OPERATOR", "CERT_OFFICER"), AssignStrategy.POOL, false),
                new WorkflowStep("DONE", "完成", "——", List.of(), AssignStrategy.POOL, true)
        ));
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PENDING", "REVIEW");
        m.put("APPROVED", "ISSUE");
        m.put("ISSUED", "DONE");
        m.put("REJECTED", "DONE");
        m.put("CANCELLED", "DONE");
        d.setStatusToStep(m);
        return d;
    }

    private static WorkflowDefinition certOp() {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setBizType("CERT_OP");
        d.setName("证书操作单（吊销/挂起/恢复/换发/重签）");
        d.setDescription("提交人（操作员/证书操作员）发起 → 审核员（APPROVER）审批即执行（批准后立即生效，进 CRL/OCSP）；提交与审批分离");
        d.setSteps(List.of(
                new WorkflowStep("SUBMIT", "提交操作", "操作员 / 证书操作员（OPERATOR/CERT_OFFICER）", List.of("OPERATOR", "CERT_OFFICER"), AssignStrategy.POOL, false),
                new WorkflowStep("APPROVE", "审核执行", "审核员（APPROVER）", List.of("APPROVER"), AssignStrategy.SINGLE_LEAST_BACKLOG, false),
                new WorkflowStep("DONE", "完成", "——", List.of(), AssignStrategy.POOL, true)
        ));
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PENDING", "APPROVE");
        m.put("EXECUTED", "DONE");
        m.put("REJECTED", "DONE");
        m.put("CANCELLED", "DONE");
        d.setStatusToStep(m);
        return d;
    }

    private static WorkflowDefinition companyOp() {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setBizType("COMPANY_OP");
        d.setName("公司变更单（停用/恢复/关闭/重置凭证/重指定保管人）");
        d.setDescription("公司管理员发起 → 公司审批员（COMPANY_APPROVER）按积压最少自动分派审批即执行；重指定保管人为原 5 保管人投票分支");
        d.setSteps(List.of(
                new WorkflowStep("SUBMIT", "提交变更", "公司管理员（COMPANY_ADMIN）", List.of("COMPANY_ADMIN"), AssignStrategy.POOL, false),
                new WorkflowStep("APPROVE", "审批执行", "公司审批员（COMPANY_APPROVER）", List.of("COMPANY_APPROVER"), AssignStrategy.SINGLE_LEAST_BACKLOG, false),
                new WorkflowStep("DONE", "完成", "——", List.of(), AssignStrategy.POOL, true)
        ));
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PENDING", "APPROVE");
        m.put("EXECUTED", "DONE");
        m.put("REJECTED", "DONE");
        m.put("CANCELLED", "DONE");
        d.setStatusToStep(m);
        return d;
    }

    private static WorkflowDefinition subCaRebuild() {
        WorkflowDefinition d = new WorkflowDefinition();
        d.setBizType("SUB_CA_REBUILD");
        d.setName("SUB_CA 重建（3-of-5 门限）");
        d.setDescription("发起申请 → 该公司 5 名密钥保管人 3-of-5 审批 → 用根 CA 重签二级 CA");
        d.setSteps(List.of(
                new WorkflowStep("SUBMIT", "发起申请", "COMPANY_ADMIN / KEY_ADMIN", List.of("COMPANY_ADMIN", "KEY_ADMIN"), AssignStrategy.POOL, false),
                new WorkflowStep("VOTE", "保管人审批（3-of-5）", "该公司 5 名密钥保管人", List.of("KEY_ADMIN"), AssignStrategy.MULTI_VOTE, false),
                new WorkflowStep("EXECUTE", "根CA重签执行", "持 ca:subca:rebuild:execute 权限者", List.of("KEY_ADMIN"), AssignStrategy.POOL, false),
                new WorkflowStep("DONE", "完成", "——", List.of(), AssignStrategy.POOL, true)
        ));
        Map<String, String> m = new LinkedHashMap<>();
        m.put("PENDING", "VOTE");
        m.put("APPROVED", "EXECUTE");
        m.put("EXECUTED", "DONE");
        m.put("REJECTED", "DONE");
        m.put("CANCELLED", "DONE");
        d.setStatusToStep(m);
        return d;
    }
}
