package com.superskidder.kylinops.backend.agentruntime.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.domain.ToolIdempotency;
import com.superskidder.kylinops.backend.rag.RagChunkItem;
import com.superskidder.kylinops.backend.rag.RagSearchContext;
import com.superskidder.kylinops.backend.rag.RagService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** knowledge.search 工具：元数据、参数校验（topK 越界拒绝）、RAG stub 片段返回与降级。 */
class KnowledgeSearchToolTest {

    private static final String TENANT_ID = "org-alpha";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private RagService ragService;
    private KnowledgeSearchTool tool;

    @BeforeEach
    void setUp() {
        ragService = mock(RagService.class);
        tool = new KnowledgeSearchTool(ragService, objectMapper);
    }

    @Test
    void toolMetadataIsReadOnlyR0Idempotent() {
        assertThat(tool.toolId()).isEqualTo("kylin.runtime.knowledge_search");
        assertThat(tool.readOnly()).isTrue();
        assertThat(tool.riskFloor()).isEqualTo("R0");
        assertThat(tool.idempotency()).isEqualTo(ToolIdempotency.IDEMPOTENT);
        assertThat(tool.alwaysRequireApproval()).isFalse();
        assertThat(tool.llmTool()).isNotNull();
        assertThat(tool.inputSchema().get("required")).isEqualTo(List.of("query"));
    }

    @Test
    void argumentValidationAppliesTopKBoundsAndDefaults() {
        Map<String, Object> defaults = tool.validateArguments(Map.of("query", "sshd 加固"));
        assertThat(defaults).containsEntry("topK", KnowledgeSearchTool.DEFAULT_TOP_K);
        assertThat(tool.validateArguments(Map.of("query", "sshd", "topK", 1)).get("topK")).isEqualTo(1);
        assertThat(tool.validateArguments(Map.of("query", "sshd", "topK", 10)).get("topK")).isEqualTo(10);

        assertThatThrownBy(() -> tool.validateArguments(Map.of("query", "sshd", "topK", 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("topK");
        assertThatThrownBy(() -> tool.validateArguments(Map.of("query", "sshd", "topK", 11)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validateArguments(Map.of("query", "sshd", "topK", "five")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validateArguments(Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("query");
        assertThatThrownBy(() -> tool.validateArguments(Map.of("query", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.validateArguments(Map.of("query", "sshd", "extra", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("extra");
        assertThatThrownBy(() -> tool.validateArguments(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchReturnsStructuredSnippetsFromRagStub() {
        String longContent = "麒麟系统安全运维要点 ".repeat(60);
        when(ragService.retrieveContext(eq(TENANT_ID), eq("sshd 加固"), any()))
                .thenReturn(new RagSearchContext(
                        List.of(
                                new RagChunkItem("chk-1", "doc-1", 0, "SSHD 加固指南", "TEXT", longContent, 0.91D),
                                new RagChunkItem("chk-2", "doc-2", 1, "", "PDF", "简短片段", 0.42D)),
                        List.of(), List.of(), List.of()));

        Map<String, Object> output = tool.search(TENANT_ID,
                tool.validateArguments(Map.of("query", "sshd 加固", "topK", 2)));

        assertThat(output.get("available")).isEqualTo(true);
        assertThat(output.get("resultCount")).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).get("source")).isEqualTo("SSHD 加固指南");
        assertThat(results.get(0).get("documentId")).isEqualTo("doc-1");
        assertThat((Double) results.get(0).get("score")).isEqualTo(0.91D);
        // 文本截断：超长正文只保留 500 字符 + 省略号
        String text = (String) results.get(0).get("text");
        assertThat(text).endsWith("…").hasSize(501);
        // 空标题回退为 documentId
        assertThat(results.get(1).get("source")).isEqualTo("doc-2");
        assertThat(output).doesNotContainKey("notice");
    }

    @Test
    void searchHonorsTopKLimit() {
        List<RagChunkItem> chunks = List.of(
                new RagChunkItem("chk-1", "doc-1", 0, "A", "TEXT", "a", 0.9D),
                new RagChunkItem("chk-2", "doc-1", 1, "A", "TEXT", "b", 0.8D),
                new RagChunkItem("chk-3", "doc-1", 2, "A", "TEXT", "c", 0.7D));
        when(ragService.retrieveContext(eq(TENANT_ID), any(), any()))
                .thenReturn(new RagSearchContext(chunks, List.of(), List.of(), List.of()));

        Map<String, Object> output = tool.search(TENANT_ID,
                tool.validateArguments(Map.of("query", "audit", "topK", 2)));
        assertThat(output.get("resultCount")).isEqualTo(2);
    }

    @Test
    void ragUnavailableDegradesToEmptyResultWithNotice() {
        when(ragService.retrieveContext(any(), any(), any()))
                .thenThrow(new IllegalStateException("embedding provider down"));

        Map<String, Object> output = tool.search(TENANT_ID,
                tool.validateArguments(Map.of("query", "sshd 加固")));

        assertThat(output.get("available")).isEqualTo(false);
        assertThat(output.get("resultCount")).isEqualTo(0);
        assertThat((List<?>) output.get("results")).isEmpty();
        assertThat(String.valueOf(output.get("notice"))).contains("不可用");
    }

    @Test
    void validateOutputRoundTripsStructuredResult() throws Exception {
        Map<String, Object> output = Map.of(
                "available", true, "query", "q", "resultCount", 1,
                "results", List.of(Map.of("source", "s", "score", 0.5D, "text", "t")));
        Map<String, Object> parsed = tool.validateOutput(objectMapper.writeValueAsString(output));
        assertThat(parsed.get("available")).isEqualTo(true);
        assertThat(parsed.get("resultCount")).isEqualTo(1);
        assertThat((List<?>) parsed.get("results")).hasSize(1);
        assertThat(tool.render(output)).containsEntry("type", "knowledge-search");
    }
}
