package com.csssl.vo;

import lombok.Data;

/**
 * 对外信任材料：外部系统据此配置验签链与吊销信息获取地址，
 * 无需知晓公司标识（URL 已由服务端按 appId 绑定公司生成）。
 */
@Data
public class ApiTrustVO {

    /**
     * 公司根CA证书 PEM
     */
    private String rootPem;
    /**
     * 当前签发二级CA证书 PEM
     */
    private String subCaPem;
    /**
     * 全量 CRL 下载地址（GET，application/x-pem-file）
     */
    private String crlUrl;
    /**
     * 增量 CRL 下载地址（GET）
     */
    private String deltaCrlUrl;
    /**
     * OCSP 地址（POST application/ocsp-request，或 GET base64url）
     */
    private String ocspUrl;
}
