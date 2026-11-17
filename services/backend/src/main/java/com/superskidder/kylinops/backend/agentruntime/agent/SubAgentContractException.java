package com.superskidder.kylinops.backend.agentruntime.agent;

/**
 * SubAgent 招募 / handoff 契约违反，携带结构化错误码（与工具 error_code 约定一致）。
 */
public class SubAgentContractException extends RuntimeException {

    private final String errorCode;

    public SubAgentContractException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
