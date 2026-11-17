package com.superskidder.kylinops.backend.rag;

public record RagDocumentItem(
        String id,
        String title,
        String sourceType,
        String content,
        /** 原始文件地址（如上传的 PDF）；文本录入的文档为空 */
        String sourceUrl,
        String sourceFileName
) {
}
