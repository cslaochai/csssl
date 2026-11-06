package com.csssl.workflow;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.csssl.entity.SysUser;
import com.csssl.mapper.FlowDefinitionMapper;
import com.csssl.mapper.FlowNodeMapper;
import com.csssl.mapper.FlowSkipMapper;
import com.csssl.mapper.SysUserMapper;
import com.csssl.vo.UserSimpleVO;
import com.csssl.workflow.dto.DefJson;
import com.csssl.workflow.dto.NodeJson;
import com.csssl.workflow.dto.SkipJson;
import com.csssl.workflow.model.FlowDefinition;
import com.csssl.workflow.model.FlowNode;
import com.csssl.workflow.model.FlowSkip;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 流程定义服务（自研轻量引擎的「定义侧」）。
 *
 * <p>负责流程定义的增删改查、发布/取消发布，以及设计器 JSON（{@link DefJson}）与
 * 流程表（flow_definition / flow_node / flow_skip）之间的双向转换。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowDefService {

    private final FlowDefinitionMapper definitionMapper;
    private final FlowNodeMapper nodeMapper;
    private final FlowSkipMapper skipMapper;
    private final SysUserMapper sysUserMapper;
    private final ObjectMapper objectMapper;

    public List<FlowDefinition> list() {
        return definitionMapper.selectList(new QueryWrapper<FlowDefinition>().orderByDesc("update_time"));
    }

    /**
     * 设计器选人下拉：全部启用用户（只暴露账号/姓名/角色）
     */
    public List<UserSimpleVO> listAssignableUsers() {
        List<SysUser> users = sysUserMapper.selectList(new QueryWrapper<SysUser>()
                .eq("status", 1)
                .orderByAsc("role_code")
                .orderByAsc("id"));
        List<UserSimpleVO> vos = new ArrayList<>();
        for (SysUser u : users) {
            UserSimpleVO vo = new UserSimpleVO();
            vo.setUsername(u.getUsername());
            vo.setRealName(u.getRealName());
            vo.setRoleCode(u.getRoleCode());
            vo.setStatus(u.getStatus());
            vos.add(vo);
        }
        return vos;
    }

    public FlowDefinition getPublished(String flowCode) {
        return definitionMapper.selectOne(new QueryWrapper<FlowDefinition>()
                .eq("flow_code", flowCode)
                .eq("is_publish", 1)
                .last("limit 1"));
    }

    public FlowDefinition getByFlowCode(String flowCode) {
        return definitionMapper.selectOne(new QueryWrapper<FlowDefinition>()
                .eq("flow_code", flowCode)
                .last("limit 1"));
    }

    /**
     * 设计器回显：把库里的定义/节点/跳转拼成前端契约结构。
     */
    public DefJson queryDesign(Long id) {
        FlowDefinition def = definitionMapper.selectById(id);
        if (def == null) {
            throw new IllegalArgumentException("流程定义不存在（id=" + id + "）");
        }
        DefJson dto = new DefJson();
        dto.setId(def.getId());
        dto.setFlowCode(def.getFlowCode());
        dto.setFlowName(def.getFlowName());
        dto.setCategory(def.getCategory());
        dto.setVersion(def.getVersion());
        dto.setIsPublish(def.getIsPublish());
        dto.setFormCustom(def.getFormCustom());

        List<FlowNode> nodes = nodeMapper.selectList(new QueryWrapper<FlowNode>()
                .eq("definition_id", id)
                .orderByAsc("node_type", "node_code"));
        List<FlowSkip> skips = skipMapper.selectList(new QueryWrapper<FlowSkip>()
                .eq("definition_id", id));

        for (FlowNode n : nodes) {
            NodeJson nj = new NodeJson();
            nj.setNodeCode(n.getNodeCode());
            nj.setNodeName(n.getNodeName());
            nj.setNodeType(n.getNodeType());
            nj.setPermissionFlag(n.getPermissionFlag());
            nj.setNodeRatio(n.getNodeRatio());
            nj.setCoordinate(n.getCoordinate());
            nj.setVersion(n.getVersion());
            List<SkipJson> sjList = new ArrayList<>();
            for (FlowSkip s : skips) {
                if (!n.getNodeCode().equals(s.getNowNodeCode())) {
                    continue;
                }
                SkipJson sj = new SkipJson();
                sj.setNowNodeCode(s.getNowNodeCode());
                sj.setNextNodeCode(s.getNextNodeCode());
                sj.setSkipType(s.getSkipType());
                sj.setSkipName(s.getSkipName());
                sj.setCoordinate(s.getCoordinate());
                sjList.add(sj);
            }
            nj.setSkipList(sjList);
            dto.getNodeList().add(nj);
        }
        return dto;
    }

    /**
     * 保存流程定义（新增或更新），保存后为草稿态，需显式发布才生效。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long save(DefJson dto) {
        if (dto.getFlowCode() == null || dto.getFlowCode().isBlank()) {
            throw new IllegalArgumentException("流程编码不能为空");
        }
        if (dto.getFlowName() == null || dto.getFlowName().isBlank()) {
            throw new IllegalArgumentException("流程名称不能为空");
        }
        if (dto.getNodeList() == null || dto.getNodeList().isEmpty()) {
            throw new IllegalArgumentException("流程至少需要一个节点");
        }

        FlowDefinition def;
        if (dto.getId() == null) {
            FlowDefinition exists = getByFlowCode(dto.getFlowCode().trim());
            if (exists != null) {
                throw new IllegalArgumentException("流程编码已存在：" + dto.getFlowCode());
            }
            def = new FlowDefinition();
            def.setFlowCode(dto.getFlowCode().trim());
            def.setVersion(dto.getVersion() == null ? "1" : dto.getVersion());
            def.setIsPublish(0);
            def.setActivityStatus(1);
            def.setFormCustom(dto.getFormCustom() == null ? "N" : dto.getFormCustom());
            def.setCreateTime(LocalDateTime.now());
            def.setUpdateTime(LocalDateTime.now());
        } else {
            def = definitionMapper.selectById(dto.getId());
            if (def == null) {
                throw new IllegalArgumentException("流程定义不存在（id=" + dto.getId() + "）");
            }
            def.setUpdateTime(LocalDateTime.now());
        }
        def.setFlowName(dto.getFlowName().trim());
        def.setCategory(dto.getCategory());
        def.setModelValue(toJson(dto));

        // 先写定义，拿到 id；节点/跳转按 id 重建
        if (dto.getId() == null) {
            definitionMapper.insert(def);
        } else {
            definitionMapper.updateById(def);
            nodeMapper.delete(new QueryWrapper<FlowNode>().eq("definition_id", def.getId()));
            skipMapper.delete(new QueryWrapper<FlowSkip>().eq("definition_id", def.getId()));
        }

        List<FlowNode> nodeRows = new ArrayList<>();
        for (NodeJson nj : dto.getNodeList()) {
            FlowNode n = new FlowNode();
            n.setDefinitionId(def.getId());
            n.setNodeCode(nj.getNodeCode());
            n.setNodeName(nj.getNodeName());
            n.setNodeType(nj.getNodeType() == null ? 1 : nj.getNodeType());
            n.setPermissionFlag(nj.getPermissionFlag());
            n.setNodeRatio(nj.getNodeRatio() == null ? "0.000" : nj.getNodeRatio());
            n.setCoordinate(nj.getCoordinate());
            n.setVersion(def.getVersion());
            n.setCreateTime(LocalDateTime.now());
            n.setUpdateTime(LocalDateTime.now());
            nodeMapper.insert(n);
            nodeRows.add(n);
        }

        for (NodeJson nj : dto.getNodeList()) {
            if (nj.getSkipList() == null) {
                continue;
            }
            FlowNode from = nodeRows.stream()
                    .filter(x -> x.getNodeCode().equals(nj.getNodeCode()))
                    .findFirst().orElse(null);
            for (SkipJson sj : nj.getSkipList()) {
                FlowNode to = nodeRows.stream()
                        .filter(x -> x.getNodeCode().equals(sj.getNextNodeCode()))
                        .findFirst().orElse(null);
                FlowSkip s = new FlowSkip();
                s.setDefinitionId(def.getId());
                s.setNodeId(from == null ? null : from.getId());
                s.setNowNodeCode(sj.getNowNodeCode());
                s.setNowNodeType(from == null ? null : from.getNodeType());
                s.setNextNodeCode(sj.getNextNodeCode());
                s.setNextNodeType(to == null ? null : to.getNodeType());
                s.setSkipType(sj.getSkipType() == null ? "PASS" : sj.getSkipType());
                s.setSkipName(sj.getSkipName());
                s.setCoordinate(sj.getCoordinate());
                s.setCreateTime(LocalDateTime.now());
                s.setUpdateTime(LocalDateTime.now());
                skipMapper.insert(s);
            }
        }
        return def.getId();
    }

    /**
     * 保存并发布（单一事务）：先落库当前设计，再置为已发布。
     * 供设计器「保存并发布」按钮使用 —— 避免前端先发保存、再发发布两次请求，
     * 也避免保存成功而发布失败时留下半成品。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveAndPublish(DefJson dto) {
        Long id = save(dto);
        publish(id);
        return id;
    }

    /**
     * 发布：校验流程结构完整后置为已发布，新启动的实例立即使用该定义。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean publish(Long id) {
        FlowDefinition def = definitionMapper.selectById(id);
        if (def == null) {
            throw new IllegalArgumentException("流程定义不存在（id=" + id + "）");
        }
        List<FlowNode> nodes = nodeMapper.selectList(new QueryWrapper<FlowNode>().eq("definition_id", id));
        List<FlowSkip> skips = skipMapper.selectList(new QueryWrapper<FlowSkip>().eq("definition_id", id));

        long startCount = nodes.stream().filter(n -> n.getNodeType() != null && n.getNodeType() == 0).count();
        long endCount = nodes.stream().filter(n -> n.getNodeType() != null && n.getNodeType() == 2).count();
        if (startCount != 1) {
            throw new IllegalArgumentException("流程必须有且仅有一个开始节点");
        }
        if (endCount < 1) {
            throw new IllegalArgumentException("流程必须至少有一个结束节点");
        }
        for (FlowNode n : nodes) {
            if (n.getNodeType() != null && n.getNodeType() == 1) {
                boolean hasPass = skips.stream()
                        .anyMatch(s -> n.getNodeCode().equals(s.getNowNodeCode()) && "PASS".equals(s.getSkipType()));
                if (!hasPass) {
                    throw new IllegalArgumentException("审批节点「" + n.getNodeName() + "」缺少通过流转线");
                }
                validateFixedAssignees(n);
            }
        }
        def.setIsPublish(1);
        def.setUpdateTime(LocalDateTime.now());
        definitionMapper.updateById(def);
        return true;
    }

    /**
     * 发布前校验：审批节点必须配置办理人，且「固定人员」必须存在且启用。
     * role:XXX 的用户由角色动态解析，不在此处校验。
     */
    private void validateFixedAssignees(FlowNode node) {
        String flag = node.getPermissionFlag();
        if (flag == null || flag.isBlank()) {
            throw new IllegalArgumentException("审批节点「" + node.getNodeName() + "」未配置办理人");
        }
        for (String raw : flag.split("@@")) {
            String f = raw.trim();
            if (f.isEmpty() || f.startsWith("role:")) {
                continue;
            }
            String username = f.startsWith("user:") ? f.substring("user:".length()) : f;
            SysUser user = sysUserMapper.selectOne(new QueryWrapper<SysUser>()
                    .eq("username", username)
                    .last("limit 1"));
            if (user == null || (user.getStatus() != null && user.getStatus() != 1)) {
                throw new IllegalArgumentException("审批节点「" + node.getNodeName()
                        + "」指定的办理人不存在或已停用：" + username);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean unpublish(Long id) {
        FlowDefinition def = definitionMapper.selectById(id);
        if (def == null) {
            throw new IllegalArgumentException("流程定义不存在（id=" + id + "）");
        }
        def.setIsPublish(0);
        def.setUpdateTime(LocalDateTime.now());
        definitionMapper.updateById(def);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long id) {
        nodeMapper.delete(new QueryWrapper<FlowNode>().eq("definition_id", id));
        skipMapper.delete(new QueryWrapper<FlowSkip>().eq("definition_id", id));
        definitionMapper.deleteById(id);
        return true;
    }

    public String export(Long id) {
        return toJson(queryDesign(id));
    }

    /**
     * 供种子初始化使用：已存在同 flowCode 的定义则跳过，避免重复插入。
     */
    public boolean existsByFlowCode(String flowCode) {
        return getByFlowCode(flowCode) != null;
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("流程定义序列化失败", e);
        }
    }
}
