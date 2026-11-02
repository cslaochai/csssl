package com.csssl.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.csssl.entity.ApplicationTrail;
import com.csssl.mapper.ApplicationTrailMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 申请单审批轨迹（按步留痕，面向业务展示，与防篡改审计链解耦）。
 */
@Service
@RequiredArgsConstructor
public class ApplicationTrailService {

    private final ApplicationTrailMapper trailMapper;

    /** 记录一步轨迹 */
    public void record(String companyUscc, Long applicationId, String applyNo, String step,
                       String operatorUsername, String operatorRealName, String operatorRole,
                       String fromStatus, String toStatus, String comment) {
        ApplicationTrail t = new ApplicationTrail();
        t.setCompanyUscc(companyUscc == null || companyUscc.isBlank() ? "default" : companyUscc);
        t.setApplicationId(applicationId);
        t.setApplyNo(applyNo);
        t.setStep(step);
        t.setOperatorUsername(operatorUsername);
        t.setOperatorRealName(operatorRealName);
        t.setOperatorRole(operatorRole);
        t.setFromStatus(fromStatus);
        t.setToStatus(toStatus);
        t.setComment(comment);
        t.setCreatedAt(LocalDateTime.now());
        trailMapper.insert(t);
    }

    /** 按申请单查完整轨迹（时间正序） */
    public List<ApplicationTrail> listByApplication(Long applicationId) {
        return trailMapper.selectList(new LambdaQueryWrapper<ApplicationTrail>()
                .eq(ApplicationTrail::getApplicationId, applicationId)
                .orderByAsc(ApplicationTrail::getCreatedAt));
    }
}
