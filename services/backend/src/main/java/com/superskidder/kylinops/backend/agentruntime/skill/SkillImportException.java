package com.superskidder.kylinops.backend.agentruntime.skill;

/** Skill 导入与校验失败的结构化错误；消息为固定措辞，不回显输入内容。 */
public class SkillImportException extends RuntimeException {

    public SkillImportException(String message) {
        super(message);
    }

    public SkillImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
