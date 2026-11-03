package com.csssl.workflow;

import com.csssl.workflow.dto.DefJson;
import com.csssl.workflow.dto.NodeJson;
import com.csssl.workflow.dto.SkipJson;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 流程种子初始化：首次启动时把四条业务流的默认定义写入并发布。
 * 幂等 —— 已存在同 flowCode 的定义直接跳过，不覆盖管理员在设计器里的修改。
 */
@Slf4j
@Component
@Order(2) // 晚于 WorkflowSchemaInitializer 建表
@RequiredArgsConstructor
public class WorkflowSeeder implements ApplicationRunner {

    private final WorkflowDefService defService;

    @Override
    public void run(ApplicationArguments args) {
        seed("raApplyFlow", "RA证书申请审批流", "证书管理", raApply());
        seed("certOpTicketFlow", "证书操作单审批流", "证书管理", certOpTicket());
        seed("companyOpTicketFlow", "公司变更单审批流", "公司管理", companyOpTicket());
        seed("subcaRebuildFlow", "SUB_CA重建审批流", "SUB_CA管理", subcaRebuild());
    }

    private void seed(String flowCode, String flowName, String category, List<NodeJson> nodes) {
        try {
            if (defService.existsByFlowCode(flowCode)) {
                log.info("[Workflow] 流程 {} 已存在，跳过种子初始化", flowCode);
                return;
            }
            DefJson d = new DefJson();
            d.setFlowCode(flowCode);
            d.setFlowName(flowName);
            d.setCategory(category);
            d.setVersion("1");
            d.setFormCustom("N");
            d.setNodeList(nodes);
            Long id = defService.save(d);
            defService.publish(id);
            log.info("[Workflow] 流程 {} 初始化并发布成功（id={}）", flowCode, id);
        } catch (Exception e) {
            log.error("[Workflow] 流程 {} 初始化失败：{}", flowCode, e.getMessage(), e);
        }
    }

    private List<NodeJson> raApply() {
        return List.of(
                node("1", "提交申请", 0, null, null, 80, 120)
                        .withSkips(List.of(skip("1", "2", "PASS", "提交"))),
                node("2", "审核审批", 1, "role:APPROVER@@role:RA_ADMIN", "0.000", 280, 120)
                        .withSkips(List.of(skip("2", "3", "PASS", "审批通过"))),
                node("3", "执行签发", 1, "role:CERT_OFFICER", "0.000", 480, 120)
                        .withSkips(List.of(skip("3", "4", "PASS", "签发完成"))),
                node("4", "结束", 2, null, null, 680, 120)
        );
    }

    private List<NodeJson> certOpTicket() {
        return List.of(
                node("1", "发起操作单", 0, null, null, 80, 120)
                        .withSkips(List.of(skip("1", "2", "PASS", "提交"))),
                node("2", "审批", 1, "role:APPROVER", "0.000", 280, 120)
                        .withSkips(List.of(skip("2", "3", "PASS", "审批通过"))),
                node("3", "执行变更", 1, "role:CERT_OFFICER", "0.000", 480, 120)
                        .withSkips(List.of(skip("3", "4", "PASS", "执行完成"))),
                node("4", "结束", 2, null, null, 680, 120)
        );
    }

    private List<NodeJson> companyOpTicket() {
        return List.of(
                node("1", "发起变更单", 0, null, null, 80, 120)
                        .withSkips(List.of(skip("1", "2", "PASS", "提交"))),
                node("2", "审批", 1, "role:COMPANY_APPROVER", "0.000", 280, 120)
                        .withSkips(List.of(skip("2", "3", "PASS", "审批通过"))),
                node("3", "执行变更", 1, "role:COMPANY_ADMIN", "0.000", 480, 120)
                        .withSkips(List.of(skip("3", "4", "PASS", "执行完成"))),
                node("4", "结束", 2, null, null, 680, 120)
        );
    }

    private List<NodeJson> subcaRebuild() {
        return List.of(
                node("1", "发起重建", 0, null, null, 80, 120)
                        .withSkips(List.of(skip("1", "2", "PASS", "提交"))),
                node("2", "保管人审批（3-of-5）", 1, "role:KEY_ADMIN", "60.000", 280, 120)
                        .withSkips(List.of(skip("2", "3", "PASS", "会签通过"))),
                node("3", "执行重建", 1, "role:SECURITY_ADMIN", "0.000", 480, 120)
                        .withSkips(List.of(skip("3", "4", "PASS", "重建完成"))),
                node("4", "结束", 2, null, null, 680, 120)
        );
    }

    private NodeJson node(String code, String name, int type, String permission, String ratio, int x, int y) {
        NodeJson n = new NodeJson();
        n.setNodeCode(code);
        n.setNodeName(name);
        n.setNodeType(type);
        n.setPermissionFlag(permission);
        n.setNodeRatio(ratio == null ? "0.000" : ratio);
        n.setCoordinate(x + "," + y + "|" + (x + 140) + "," + (y + 44));
        n.setVersion("1");
        n.setSkipList(List.of());
        return n;
    }

    private SkipJson skip(String from, String to, String type, String name) {
        SkipJson s = new SkipJson();
        s.setNowNodeCode(from);
        s.setNextNodeCode(to);
        s.setSkipType(type);
        s.setSkipName(name);
        return s;
    }
}
