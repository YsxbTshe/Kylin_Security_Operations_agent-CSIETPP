package com.superskidder.kylinops.backend.policy;

import java.util.List;

/**
 * 工具定义提供者：策略引擎据此读取工具的风险分级、路径白名单与补偿动作声明。
 *
 * <p>方法签名对齐原 {@code HostAgentToolCatalogService#describeTools(List)}。
 * PolicyEngine 只依赖本接口，不再耦合任何具体工具目录实现。
 */
public interface ToolDefinitionProvider {

    /**
     * 按工具名逐个给出定义；未知工具按"按需审批的写操作"兜底（至少 R3），
     * 保持与旧目录一致的 fail-closed 语义。
     */
    List<ToolDefinition> describeTools(List<String> toolNames);
}
