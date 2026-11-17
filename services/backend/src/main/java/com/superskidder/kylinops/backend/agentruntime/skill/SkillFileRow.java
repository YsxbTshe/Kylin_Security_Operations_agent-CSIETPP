package com.superskidder.kylinops.backend.agentruntime.skill;

/** skill_file 行：版本内单个文件的内容与元数据。 */
public record SkillFileRow(
        String relativePath,
        byte[] content,
        String contentType,
        String contentSm3,
        long sizeBytes) {
}
