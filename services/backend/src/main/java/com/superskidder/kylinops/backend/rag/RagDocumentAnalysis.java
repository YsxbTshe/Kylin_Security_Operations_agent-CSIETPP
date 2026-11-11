package com.superskidder.kylinops.backend.rag;

import java.util.List;

/**
 * 文档解析与摘要的结果，用于知识库的"解析可视化"：先展示真实抽取到的原文，再展示 LLM 归纳后的内容。
 *
 * <p>不落库。上传预览阶段只做解析和摘要，用户确认后才走正式入库接口，避免解析效果不佳的
 * 文件直接污染知识库。
 *
 * <p>{@code summaryStatus} 必须被界面如实呈现：{@code OK} 摘要可用、
 * {@code LLM_DISABLED} 未配置模型（此时 {@code summary} 与 {@code keyPoints} 为空，
 * 界面应显示原文而不是假装有摘要）、{@code FAILED} 调用失败。
 */
public record RagDocumentAnalysis(
        String fileName,
        int pageCount,
        /** 抽取到的原始字符数（截断前） */
        int extractedChars,
        /** 是否因超出单文档上限而被截断 */
        boolean truncated,
        String extractedText,
        String summaryStatus,
        String summary,
        List<String> keyPoints,
        /** 解析统计；如实反映哪些页用了视觉、描述了多少插图，界面不得一律显示成"已智能解析" */
        ParseStats parseStats
) {

    /**
     * @param visionAvailable  视觉模型是否已配置。未配置时扫描页会解析为空，必须让人看见
     * @param visionPages      整页走视觉的页数（文字层为空的扫描页）
     * @param describedImages  生成了描述的插图数（同内容图片只描述一次）
     * @param emptyPages       既无文字层又无法视觉解析的页数
     */
    public record ParseStats(
            boolean visionAvailable,
            int visionPages,
            int describedImages,
            int emptyPages,
            int documentCount
    ) {
        public static ParseStats none() {
            return new ParseStats(false, 0, 0, 0, 0);
        }
    }
}
