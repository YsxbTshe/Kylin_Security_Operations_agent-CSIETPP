package com.superskidder.kylinops.backend.agentruntime.connector;

/**
 * 平台预装连接器定义。白名单封闭：只允许平台预装、审核、签名的适配器，
 * 不存在动态注册 API（设计红线）。endpoint 基址与凭据引用来自配置文件，
 * 工具参数只能填充 path/query，不能改变 host/scheme（防 SSRF）。
 */
public record ConnectorDefinition(
        String id,
        String displayName,
        String method,
        String endpointBaseUrl,
        String credentialReference,
        String riskFloor,
        boolean readOnly
) {

    public static final String WEBHOOK = "webhook";
    public static final String HTTP_READ = "http_read";

    public boolean configured() {
        return endpointBaseUrl != null && !endpointBaseUrl.isBlank();
    }
}
