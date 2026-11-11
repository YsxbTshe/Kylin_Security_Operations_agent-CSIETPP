package com.superskidder.kylinops.backend.agentruntime.skill;

/**
 * Skill 信任等级：决定该 Skill 可触达的执行面。
 * UNTRUSTED 只能在沙箱内以 SubAgent 形式运行；ORG_REVIEWED 可调用被授权的只读 Node 工具；
 * PRIVILEGED 与 PLATFORM_SIGNED 可参与写流程（写工具仍需逐次审批闸门）。
 */
public enum SkillTrustLevel {

    UNTRUSTED,
    ORG_REVIEWED,
    PRIVILEGED,
    PLATFORM_SIGNED;

    /** 解析 manifest 中的信任等级申请；未知取值抛结构化错误。 */
    public static SkillTrustLevel parse(String value) {
        if (value == null || value.isBlank()) {
            throw new SkillImportException("manifest trust_level is required");
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new SkillImportException("manifest trust_level is not recognized");
        }
    }

    /** 是否允许调用 Node 工具（ORG_REVIEWED 及以上）。 */
    public boolean allowsNodeTools() {
        return this != UNTRUSTED;
    }

    /** 是否允许参与写流程（PRIVILEGED 及以上）。 */
    public boolean allowsWriteFlow() {
        return this == PRIVILEGED || this == PLATFORM_SIGNED;
    }
}
