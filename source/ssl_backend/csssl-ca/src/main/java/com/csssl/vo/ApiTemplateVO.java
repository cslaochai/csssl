package com.csssl.vo;

import com.csssl.entity.CertTemplate;
import lombok.Data;

/**
 * 对外模板视图：只暴露外部系统选模板所需字段，不返回内部 ID/公司归属。
 */
@Data
public class ApiTemplateVO {

    private String templateCode;
    private String templateName;
    private String certType;
    private String algorithm;
    private Integer validityDays;
    private String keyUsage;
    private String extKeyUsage;

    public static ApiTemplateVO from(CertTemplate tpl) {
        ApiTemplateVO vo = new ApiTemplateVO();
        vo.setTemplateCode(tpl.getTemplateCode());
        vo.setTemplateName(tpl.getTemplateName());
        vo.setCertType(tpl.getCertType());
        vo.setAlgorithm(tpl.getAlgorithm());
        vo.setValidityDays(tpl.getValidityDays());
        vo.setKeyUsage(tpl.getKeyUsage());
        vo.setExtKeyUsage(tpl.getExtKeyUsage());
        return vo;
    }
}
