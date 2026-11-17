package com.superskidder.kylinops.backend.agentruntime.connector;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 预装连接器白名单（封闭集合，无动态注册）。endpoint 基址与凭据引用由配置注入；
 * 未配置基址的连接器视为未启用，执行时 fail-closed。
 */
@Component
public class ConnectorCatalog {

    private final ConnectorDefinition webhook;
    private final ConnectorDefinition httpRead;

    public ConnectorCatalog(
            @Value("${app.agent-runtime.connector.webhook.base-url:}") String webhookBaseUrl,
            @Value("${app.agent-runtime.connector.webhook.credential-reference:}") String webhookCredential,
            @Value("${app.agent-runtime.connector.http-read.base-url:}") String httpReadBaseUrl,
            @Value("${app.agent-runtime.connector.http-read.credential-reference:}") String httpReadCredential
    ) {
        this.webhook = new ConnectorDefinition(ConnectorDefinition.WEBHOOK, "工单/告警外发 Webhook",
                "POST", webhookBaseUrl, webhookCredential, "R3", false);
        this.httpRead = new ConnectorDefinition(ConnectorDefinition.HTTP_READ, "CMDB/外部 API 只读查询",
                "GET", httpReadBaseUrl, httpReadCredential, "R1", true);
    }

    public Optional<ConnectorDefinition> find(String connectorId) {
        if (ConnectorDefinition.WEBHOOK.equals(connectorId)) {
            return Optional.of(webhook);
        }
        if (ConnectorDefinition.HTTP_READ.equals(connectorId)) {
            return Optional.of(httpRead);
        }
        return Optional.empty();
    }

    public List<ConnectorDefinition> all() {
        return List.of(webhook, httpRead);
    }
}
