package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.config.AuditMetaObjectHandler;
import com.csssl.entity.ApplicationTrail;
import com.csssl.entity.LoginUser;
import com.csssl.mapper.ApplicationTrailMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 申请单审批轨迹（按步留痕，面向业务展示，与防篡改审计链解耦）。
 */
@Service
@RequiredArgsConstructor
public class ApplicationTrailService {

    private final ApplicationTrailMapper trailMapper;

    /**
     * 记录一步轨迹（操作人取登录人：只落 user_id + 真实姓名快照，账号不入表）
     */
    public void record(String companyUscc, Long applicationId, String applyNo, String step,
                       LoginUser loginUser, String fromStatus, String toStatus, String comment) {
        recordAs(companyUscc, applicationId, applyNo, step,
                loginUser == null ? null : loginUser.getUserId(),
                loginUser == null ? AuditMetaObjectHandler.SYSTEM_NAME : loginUser.getRealName(),
                loginUser == null ? null : loginUser.getRoleCode(),
                fromStatus, toStatus, comment);
    }

    /**
     * 记录一步轨迹（指定操作人口径）：无登录人的自动流转与对外 API 通道用，
     * operatorUserId 留空、姓名写「系统」或 API 客户端名称。
     */
    public void recordAs(String companyUscc, Long applicationId, String applyNo, String step,
                         Long operatorUserId, String operatorRealName, String operatorRole,
                         String fromStatus, String toStatus, String comment) {
        ApplicationTrail t = new ApplicationTrail();
        t.setCompanyUscc(companyUscc == null || companyUscc.isBlank() ? "default" : companyUscc);
        t.setApplicationId(applicationId);
        t.setApplyNo(applyNo);
        t.setStep(step);
        t.setOperatorUserId(operatorUserId);
        t.setOperatorRealName(operatorRealName);
        t.setOperatorRole(operatorRole);
        t.setFromStatus(fromStatus);
        t.setToStatus(toStatus);
        t.setComment(comment);
        trailMapper.insert(t);
    }

    /**
     * 按申请单查完整轨迹（时间正序）
     *
     * <p>单据 id 与单号双条件过滤：四条流的单据分属不同表（申请单/操作单/变更单/重建单），
     * 单靠 id 理论上存在跨表撞号风险，补上单号快照后即可精确锁定。</p>
     */
    public List<ApplicationTrail> listByApplication(Long applicationId) {
        return listByApplication(applicationId, null);
    }

    /**
     * 按申请单 + 单号查完整轨迹（时间正序）；applyNo 为空时退化为仅按 id 过滤。
     */
    public List<ApplicationTrail> listByApplication(Long applicationId, String applyNo) {
        LambdaQueryWrapper<ApplicationTrail> qw = new LambdaQueryWrapper<ApplicationTrail>()
                .eq(ApplicationTrail::getApplicationId, applicationId);
        if (applyNo != null && !applyNo.isBlank()) {
            qw.eq(ApplicationTrail::getApplyNo, applyNo);
        }
        return trailMapper.selectList(qw.orderByAsc(ApplicationTrail::getCreatedAt));
    }
}
