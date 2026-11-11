package com.superskidder.kylinops.backend.rag;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/rag")
public class RagController {

    private final RagService ragService;
    private final RagSettingsService settingsService;
    private final AccessControlService accessControlService;

    public RagController(
            RagService ragService,
            RagSettingsService settingsService,
            AccessControlService accessControlService
    ) {
        this.ragService = ragService;
        this.settingsService = settingsService;
        this.accessControlService = accessControlService;
    }

    @PostMapping("/query")
    public ApiResponse<RagQueryResponse> query(@Valid @RequestBody RagQueryRequest request) {
        accessControlService.requirePermission("assistant:use");
        return ApiResponse.success(ragService.query(
                accessControlService.currentOrganizationId(), request.question(), request.traceRequested()));
    }

    @GetMapping("/documents")
    public ApiResponse<List<RagDocumentItem>> listDocuments() {
        accessControlService.requirePermission("assistant:use");
        return ApiResponse.success(ragService.listDocuments(accessControlService.currentOrganizationId()));
    }

    @PostMapping("/documents")
    public ApiResponse<RagDocumentItem> createDocument(@Valid @RequestBody RagDocumentCreateRequest request) {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(ragService.createDocument(accessControlService.currentOrganizationId(), request));
    }

    @PostMapping("/documents/pdf")
    public ApiResponse<List<RagDocumentItem>> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title
    ) {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(ragService.uploadPdf(accessControlService.currentOrganizationId(), file, title));
    }

    /**
     * 解析预览：抽取 PDF 正文并归纳，但不落库。用于上传时先展示解析质量，由用户确认后再入库。
     */
    @PostMapping("/documents/pdf/analyze")
    public ApiResponse<RagDocumentAnalysis> analyzePdf(@RequestParam("file") MultipartFile file) {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(ragService.analyzePdf(file));
    }

    @PostMapping("/documents/{documentId}/summarize")
    public ApiResponse<RagDocumentAnalysis> summarizeDocument(@PathVariable String documentId) {
        accessControlService.requirePermission("assistant:use");
        return ApiResponse.success(ragService.summarizeDocument(accessControlService.currentOrganizationId(), documentId));
    }

    @GetMapping("/documents/{documentId}/chunks")
    public ApiResponse<List<RagChunkItem>> documentChunks(@PathVariable String documentId) {
        accessControlService.requirePermission("assistant:use");
        validateDocumentId(documentId);
        return ApiResponse.success(ragService.listDocumentChunks(accessControlService.currentOrganizationId(), documentId));
    }

    @PutMapping("/documents/{documentId}")
    public ApiResponse<RagDocumentItem> updateDocument(
            @PathVariable String documentId,
            @Valid @RequestBody RagDocumentCreateRequest request
    ) {
        accessControlService.requirePermission("platform:configure");
        validateDocumentId(documentId);
        return ApiResponse.success(ragService.updateDocument(accessControlService.currentOrganizationId(), documentId, request));
    }

    @DeleteMapping("/documents/{documentId}")
    public ApiResponse<Boolean> deleteDocument(@PathVariable String documentId) {
        accessControlService.requirePermission("platform:configure");
        validateDocumentId(documentId);
        ragService.deleteDocument(accessControlService.currentOrganizationId(), documentId);
        return ApiResponse.success(true);
    }

    @PostMapping("/documents/{documentId}/deposit")
    public ApiResponse<RagMemoryItem> depositDocument(@PathVariable String documentId) {
        accessControlService.requirePermission("platform:configure");
        validateDocumentId(documentId);
        return ApiResponse.success(ragService.depositDocument(accessControlService.currentOrganizationId(), documentId));
    }

    @GetMapping("/memories")
    public ApiResponse<List<RagMemoryItem>> listMemories() {
        accessControlService.requirePermission("assistant:use");
        return ApiResponse.success(ragService.listMemories(accessControlService.currentOrganizationId()));
    }

    @PostMapping("/memories")
    public ApiResponse<RagMemoryItem> createMemory(@Valid @RequestBody RagMemoryCreateRequest request) {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(ragService.createMemory(accessControlService.currentOrganizationId(), request));
    }

    @DeleteMapping("/memories/{memoryId}")
    public ApiResponse<Boolean> deleteMemory(@PathVariable String memoryId) {
        accessControlService.requirePermission("platform:configure");
        validateDocumentId(memoryId);
        ragService.deleteMemory(accessControlService.currentOrganizationId(), memoryId);
        return ApiResponse.success(true);
    }

    @GetMapping("/graph-edges")
    public ApiResponse<List<RagGraphEdgeItem>> listGraphEdges() {
        accessControlService.requirePermission("assistant:use");
        return ApiResponse.success(ragService.listGraphEdges(accessControlService.currentOrganizationId()));
    }

    @PostMapping("/graph-edges")
    public ApiResponse<RagGraphEdgeItem> createGraphEdge(@Valid @RequestBody RagGraphEdgeCreateRequest request) {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(ragService.createGraphEdge(accessControlService.currentOrganizationId(), request));
    }

    @PutMapping("/graph-edges/{edgeId}")
    public ApiResponse<RagGraphEdgeItem> updateGraphEdge(
            @PathVariable String edgeId,
            @Valid @RequestBody RagGraphEdgeCreateRequest request
    ) {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(ragService.updateGraphEdge(accessControlService.currentOrganizationId(), edgeId, request));
    }

    @DeleteMapping("/graph-edges/{edgeId}")
    public ApiResponse<Boolean> deleteGraphEdge(@PathVariable String edgeId) {
        accessControlService.requirePermission("platform:configure");
        ragService.deleteGraphEdge(accessControlService.currentOrganizationId(), edgeId);
        return ApiResponse.success(true);
    }

    @GetMapping("/graph")
    public ApiResponse<RagSubgraph> graph(
            @RequestParam(value = "nodeType", required = false) String nodeType,
            @RequestParam(value = "limit", required = false, defaultValue = "300") int limit
    ) {
        accessControlService.requirePermission("assistant:use");
        return ApiResponse.success(ragService.graph(accessControlService.currentOrganizationId(), nodeType, limit));
    }

    @GetMapping("/graph/neighbors")
    public ApiResponse<RagSubgraph> graphNeighbors(
            @RequestParam("node") String node,
            @RequestParam(value = "hops", required = false, defaultValue = "1") int hops
    ) {
        accessControlService.requirePermission("assistant:use");
        return ApiResponse.success(ragService.graphNeighbors(accessControlService.currentOrganizationId(), node, hops));
    }

    @PostMapping("/graph/edges")
    public ApiResponse<Boolean> createKgEdge(@Valid @RequestBody KgEdgeRequest request) {
        accessControlService.requirePermission("platform:configure");
        ragService.createKgEdge(accessControlService.currentOrganizationId(), request);
        return ApiResponse.success(true);
    }

    @PutMapping("/graph/edges/{edgeId}")
    public ApiResponse<Boolean> updateKgEdge(@PathVariable String edgeId, @Valid @RequestBody KgEdgeRequest request) {
        accessControlService.requirePermission("platform:configure");
        ragService.updateKgEdge(accessControlService.currentOrganizationId(), edgeId, request);
        return ApiResponse.success(true);
    }

    @DeleteMapping("/graph/edges/{edgeId}")
    public ApiResponse<Boolean> deleteKgEdge(@PathVariable String edgeId) {
        accessControlService.requirePermission("platform:configure");
        ragService.deleteKgEdge(accessControlService.currentOrganizationId(), edgeId);
        return ApiResponse.success(true);
    }

    /** 某个图谱节点的来源分片：真实抽取绑定，不是按名字二次检索。 */
    @GetMapping("/graph/nodes/{nodeId}/chunks")
    public ApiResponse<List<RagNodeChunkItem>> nodeChunks(
            @PathVariable String nodeId,
            @RequestParam(value = "limit", required = false, defaultValue = "10") int limit
    ) {
        accessControlService.requirePermission("assistant:use");
        return ApiResponse.success(ragService.nodeChunks(accessControlService.currentOrganizationId(), nodeId, limit));
    }

    @GetMapping("/graph/stats")
    public ApiResponse<java.util.Map<String, Object>> graphStats() {
        accessControlService.requirePermission("assistant:use");
        return ApiResponse.success(ragService.graphStats(accessControlService.currentOrganizationId()));
    }

    @PostMapping("/graph/rebuild")
    public ApiResponse<Boolean> graphRebuild() {
        accessControlService.requirePermission("platform:configure");
        ragService.rebuildGraph(accessControlService.currentOrganizationId());
        return ApiResponse.success(true);
    }

    @GetMapping("/health")
    public ApiResponse<RagHealthResponse> health() {
        accessControlService.requirePermission("assistant:use");
        return ApiResponse.success(ragService.health());
    }

    @GetMapping("/settings")
    public ApiResponse<RagSettingsView> settings() {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(settingsService.currentView());
    }

    @PutMapping("/settings")
    public ApiResponse<RagSettingsView> updateSettings(@Valid @RequestBody UpdateRagSettingsRequest request) {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(settingsService.update(accessControlService.currentUser().username(), request));
    }

    @PostMapping("/settings/test")
    public ApiResponse<RagSettingsTestResponse> testSettings() {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(settingsService.test());
    }

    @PostMapping("/reindex-external")
    public ApiResponse<Integer> reindexExternal() {
        accessControlService.requirePermission("platform:configure");
        return ApiResponse.success(ragService.reindexExternal(accessControlService.currentOrganizationId()));
    }

    @PostMapping("/rebuild")
    public ApiResponse<Boolean> rebuild() {
        accessControlService.requirePermission("platform:configure");
        ragService.rebuildAll(accessControlService.currentOrganizationId());
        return ApiResponse.success(true);
    }

    private void validateDocumentId(String documentId) {
        if (documentId == null || documentId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentId 不能为空");
        }
        if (documentId.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentId 长度不能超过64");
        }
    }
}
