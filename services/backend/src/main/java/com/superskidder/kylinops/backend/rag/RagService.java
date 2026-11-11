package com.superskidder.kylinops.backend.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.llm.ChatMessage;
import com.superskidder.kylinops.backend.llm.LlmCompletionService;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RagExternalIndexClient externalIndexClient;
    private final RagEmbeddingService embeddingService;
    private final RagSettingsService settingsService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final RagRerankService rerankService;
    private final LlmCompletionService llmCompletionService;
    private final PdfIngestService pdfIngestService;
    private final RagAssetStorageService assetStorage;
    private final RagVectorIndex vectorIndex;

    public RagService(
            JdbcTemplate jdbcTemplate,
            RagExternalIndexClient externalIndexClient,
            RagEmbeddingService embeddingService,
            RagSettingsService settingsService,
            KnowledgeGraphService knowledgeGraphService,
            RagRerankService rerankService,
            LlmCompletionService llmCompletionService,
            PdfIngestService pdfIngestService,
            RagAssetStorageService assetStorage,
            RagVectorIndex vectorIndex
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.externalIndexClient = externalIndexClient;
        this.embeddingService = embeddingService;
        this.settingsService = settingsService;
        this.knowledgeGraphService = knowledgeGraphService;
        this.rerankService = rerankService;
        this.llmCompletionService = llmCompletionService;
        this.pdfIngestService = pdfIngestService;
        this.assetStorage = assetStorage;
        this.vectorIndex = vectorIndex;
    }

    public RagQueryResponse query(String organizationId, String question) {
        return query(organizationId, question, false);
    }

    /**
     * @param withTrace 是否附带混合检索的可解释轨迹（各通道候选与 RRF 贡献）。
     *                  默认关闭：轨迹体积远大于结果本身，只有检索预览页需要。
     */
    public RagQueryResponse query(String organizationId, String question, boolean withTrace) {
        RetrievalConfig config = RetrievalConfig.defaults(settingsService.current().topK());
        Retrieval retrieval = retrieve(organizationId, question, config, withTrace);
        RagSearchContext context = retrieval.context();
        String answer = context.chunks().isEmpty() && context.memories().isEmpty() && context.graphEdges().isEmpty()
                ? "未检索到相关知识片段。"
                : "已检索到 %s 个分片、%s 条记忆、%s 条图谱关系，可用于增强 Agent 回答和审计说明。"
                        .formatted(context.chunks().size(), context.memories().size(), context.graphEdges().size());
        return new RagQueryResponse(answer, context.references(), context.chunks(), context.memories(),
                context.graphEdges(), retrieval.trace());
    }

    public RagSearchContext retrieveContext(String organizationId, String question) {
        return retrieveContext(organizationId, question, RetrievalConfig.defaults(settingsService.current().topK()));
    }

    /**
     * Three-channel hybrid retrieval fused with Reciprocal Rank Fusion (RRF):
     * vector (cosine over real embeddings) + keyword (Elasticsearch BM25, JVM keyword fallback)
     * + graph (k-hop neighborhood). The {@link RetrievalConfig} lets the benchmark toggle channels.
     */
    public RagSearchContext retrieveContext(String organizationId, String question, RetrievalConfig config) {
        return retrieve(organizationId, question, config, false).context();
    }

    /** 检索结果 + 可选的可解释轨迹。 */
    private record Retrieval(RagSearchContext context, RagRetrievalTrace trace) {
    }

    /** 一路通道的运行结果：候选按分数降序，附带该路的真实实现说明。 */
    private record ChannelRun(String key, String label, String detail, double weight, List<RagScoredId> hits) {
    }

    private Retrieval retrieve(String organizationId, String question, RetrievalConfig config, boolean withTrace) {
        ensureChunks(organizationId);
        Set<String> terms = tokenize(question);
        List<Double> queryEmbedding = embeddingService.embed(question);
        // PostgreSQL 形态下向量排序在库内完成，就不必把全库 embedding_json 拉进 JVM
        // （embedding_json 是分片行里最大的字段，384 维 ≈ 每片 8KB）。
        boolean vectorInDatabase = vectorIndex.databaseRankingAvailable();
        List<RagChunkRow> chunkRows = listChunkRows(organizationId, !vectorInDatabase);
        Map<String, RagChunkRow> rowById = new LinkedHashMap<>();
        for (RagChunkRow row : chunkRows) {
            rowById.put(row.id(), row);
        }

        List<ChannelRun> runs = new ArrayList<>();
        if (config.useVector()) {
            // detail 如实反映当前 embedding provider 与实际执行路径：本地哈希伪向量和真实语义
            // 向量的检索质量完全不是一回事，库内 pgvector 与 JVM 内存余弦也不是一回事，
            // 不能在界面上都写成"稠密向量"。
            RagSettings settings = settingsService.current();
            runs.add(new ChannelRun("vector", "向量", vectorIndex.describe(settings),
                    config.weightVector(), vectorRank(organizationId, chunkRows, queryEmbedding, config.candidateN())));
        }
        if (config.useKeyword()) {
            runs.add(keywordRun(organizationId, question, terms, chunkRows, config.candidateN(), config.weightKeyword()));
        }
        KnowledgeGraphService.GraphRetrieval graph = config.useGraph()
                ? knowledgeGraphService.graphRetrieve(organizationId, terms, queryEmbedding)
                : KnowledgeGraphService.GraphRetrieval.empty();
        if (config.useGraph()) {
            runs.add(new ChannelRun("graph", "图谱", "k-hop 邻域扩展",
                    config.weightGraph(), rankByScore(graph.chunkScores(), config.candidateN())));
        }

        Map<String, Double> fused = reciprocalRankFusion(runs, config.rrfK());
        List<String> fusedOrder = fused.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();

        // 精排：RRF 只看名次，重排用交叉编码器按查询与正文的真实匹配度重新打分。
        // 服务不可用时 rerankOrder 为空，整条链路退回融合顺序，不影响作答。
        RerankOutcome rerank = applyRerank(question, fusedOrder, rowById);
        List<String> finalOrder = rerank.order().isEmpty() ? fusedOrder : rerank.order();

        List<RagChunkItem> chunks = finalOrder.stream()
                .limit(config.topK())
                .map(rowById::get)
                .filter(Objects::nonNull)
                .map(row -> row.toItem(fused.getOrDefault(row.id(), 0.0D)))
                .toList();

        List<RagDocumentItem> references = findDocumentsByIds(organizationId, uniqueDocumentIds(chunks));
        List<RagMemoryItem> memories = searchMemories(organizationId, queryEmbedding, terms, settingsService.current().topK());
        List<RagGraphEdgeItem> graphEdges = config.useGraph()
                ? knowledgeGraphService.edgesAsItems(organizationId, graph.reachedNodeIds())
                : List.of();
        RagSearchContext context = new RagSearchContext(chunks, references, memories, graphEdges);

        RagRetrievalTrace trace = withTrace
                ? buildTrace(question, config, runs, fused, fusedOrder, rowById, rerank)
                : null;
        return new Retrieval(context, trace);
    }

    /** 重排的产出：重排后的分片顺序（为空表示未启用或已降级）与用于轨迹展示的元信息。 */
    private record RerankOutcome(List<String> order, String status, String model, int inputCount, Map<String, Double> relevance) {
    }

    private RerankOutcome applyRerank(String question, List<String> fusedOrder, Map<String, RagChunkRow> rowById) {
        RagSettings settings = settingsService.current();
        if (!rerankService.isEnabled(settings) || fusedOrder.isEmpty()) {
            return new RerankOutcome(List.of(), "DISABLED", settings.rerankerModel(), 0, Map.of());
        }
        List<String> candidateIds = fusedOrder.stream().limit(Math.max(1, settings.rerankerTopN())).toList();
        List<String> documents = candidateIds.stream()
                .map(rowById::get)
                .map(row -> row == null ? "" : row.title() + "\n" + row.content())
                .toList();
        RagRerankService.RerankResult result = rerankService.rerank(settings, question, documents);
        if (result.isEmpty()) {
            return new RerankOutcome(List.of(), "FALLBACK", settings.rerankerModel(), candidateIds.size(), Map.of());
        }
        List<String> reordered = new ArrayList<>();
        Map<String, Double> relevance = new LinkedHashMap<>();
        for (int index : result.order()) {
            String id = candidateIds.get(index);
            reordered.add(id);
            relevance.put(id, result.scores().getOrDefault(index, 0.0D));
        }
        // 未参与重排的尾部候选保持融合顺序接在后面，避免直接丢弃
        for (String id : fusedOrder) {
            if (!relevance.containsKey(id)) {
                reordered.add(id);
            }
        }
        return new RerankOutcome(reordered, "APPLIED", settings.rerankerModel(), candidateIds.size(), relevance);
    }

    private RagRetrievalTrace buildTrace(
            String question,
            RetrievalConfig config,
            List<ChannelRun> runs,
            Map<String, Double> fused,
            List<String> fusedOrder,
            Map<String, RagChunkRow> rowById,
            RerankOutcome rerank
    ) {
        List<RagRetrievalTrace.Channel> channels = runs.stream()
                .map(run -> {
                    List<RagRetrievalTrace.Candidate> candidates = new ArrayList<>();
                    for (int i = 0; i < run.hits().size(); i++) {
                        RagScoredId hit = run.hits().get(i);
                        RagChunkRow row = rowById.get(hit.id());
                        candidates.add(new RagRetrievalTrace.Candidate(
                                hit.id(),
                                row == null ? hit.id() : row.title(),
                                snippet(row),
                                i + 1,
                                hit.score()
                        ));
                    }
                    return new RagRetrievalTrace.Channel(run.key(), run.label(), run.detail(), run.weight(), candidates);
                })
                .toList();

        // 每条分片在各通道的 RRF 贡献，与 reciprocalRankFusion 用同一个公式，保证相加等于融合分
        Map<String, List<RagRetrievalTrace.Contribution>> contributions = new HashMap<>();
        for (ChannelRun run : runs) {
            for (int rank = 0; rank < run.hits().size(); rank++) {
                String id = run.hits().get(rank).id();
                contributions.computeIfAbsent(id, key -> new ArrayList<>())
                        .add(new RagRetrievalTrace.Contribution(run.key(), rank + 1, run.weight() / (config.rrfK() + rank + 1)));
            }
        }

        List<RagRetrievalTrace.Fused> fusedTrace = new ArrayList<>();
        for (int i = 0; i < fusedOrder.size(); i++) {
            String id = fusedOrder.get(i);
            RagChunkRow row = rowById.get(id);
            fusedTrace.add(new RagRetrievalTrace.Fused(
                    id,
                    row == null ? id : row.title(),
                    snippet(row),
                    i + 1,
                    fused.getOrDefault(id, 0.0D),
                    i < config.topK(),
                    contributions.getOrDefault(id, List.of())
            ));
        }

        return new RagRetrievalTrace(question, config.rrfK(), config.candidateN(), config.topK(),
                channels, fusedTrace, buildRerankTrace(config, fusedOrder, rowById, rerank));
    }

    private RagRetrievalTrace.Rerank buildRerankTrace(
            RetrievalConfig config,
            List<String> fusedOrder,
            Map<String, RagChunkRow> rowById,
            RerankOutcome rerank
    ) {
        if ("DISABLED".equals(rerank.status())) {
            return RagRetrievalTrace.Rerank.disabled();
        }
        if ("FALLBACK".equals(rerank.status())) {
            return RagRetrievalTrace.Rerank.fallback(rerank.model(), rerank.inputCount());
        }
        Map<String, Integer> fusedRanks = new HashMap<>();
        for (int i = 0; i < fusedOrder.size(); i++) {
            fusedRanks.put(fusedOrder.get(i), i + 1);
        }
        List<RagRetrievalTrace.RerankedItem> items = new ArrayList<>();
        int rank = 0;
        for (Map.Entry<String, Double> entry : rerank.relevance().entrySet()) {
            String id = entry.getKey();
            RagChunkRow row = rowById.get(id);
            rank++;
            items.add(new RagRetrievalTrace.RerankedItem(
                    id,
                    row == null ? id : row.title(),
                    snippet(row),
                    rank,
                    fusedRanks.getOrDefault(id, 0),
                    entry.getValue(),
                    rank <= config.topK()
            ));
        }
        return new RagRetrievalTrace.Rerank("APPLIED", rerank.model(), rerank.inputCount(), items);
    }

    private static final int SNIPPET_LENGTH = 120;

    private String snippet(RagChunkRow row) {
        if (row == null || row.content() == null) {
            return "";
        }
        String text = row.content().strip().replaceAll("\\s+", " ");
        return text.length() <= SNIPPET_LENGTH ? text : text.substring(0, SNIPPET_LENGTH) + "…";
    }

    /**
     * 向量通道：PostgreSQL 上走 pgvector 库内检索（HNSW + 余弦距离），H2 上退回 JVM 内存余弦。
     * 两条路径同口径（余弦、同排序规则、同过滤阈值），对照见 {@code RagPgVectorParityIT}。
     */
    private List<RagScoredId> vectorRank(String organizationId, List<RagChunkRow> rows, List<Double> queryEmbedding, int n) {
        if (vectorIndex.databaseRankingAvailable()) {
            return vectorIndex.rankInDatabase(organizationId, queryEmbedding, n);
        }
        return vectorIndex.rankInMemory(
                rows.stream().map(row -> new RagVectorIndex.ChunkVector(row.id(), row.embedding())).toList(),
                queryEmbedding, n);
    }

    private ChannelRun keywordRun(String organizationId, String question, Set<String> terms, List<RagChunkRow> rows, int n, double weight) {
        List<RagScoredId> esHits = externalIndexClient.searchChunks(organizationId, question, n);
        if (!esHits.isEmpty()) {
            return new ChannelRun("keyword", "关键词", "Elasticsearch BM25", weight, esHits);
        }
        // Fallback when Elasticsearch is disabled (e.g. the lightweight 155 deployment): in-JVM term overlap.
        List<RagScoredId> local = rows.stream()
                .map(row -> new RagScoredId(row.id(), keywordScore(row.title() + " " + row.content(), terms)))
                .filter(scored -> scored.score() > 0.0D)
                .sorted(Comparator.comparingDouble(RagScoredId::score).reversed())
                .limit(n)
                .toList();
        return new ChannelRun("keyword", "关键词", "JVM 词项重合（Elasticsearch 未启用时的降级实现）", weight, local);
    }

    private List<RagScoredId> rankByScore(Map<String, Double> scores, int n) {
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(n)
                .map(entry -> new RagScoredId(entry.getKey(), entry.getValue()))
                .toList();
    }

    private Map<String, Double> reciprocalRankFusion(List<ChannelRun> runs, int rrfK) {
        Map<String, Double> fused = new HashMap<>();
        for (ChannelRun run : runs) {
            List<RagScoredId> hits = run.hits();
            for (int rank = 0; rank < hits.size(); rank++) {
                fused.merge(hits.get(rank).id(), run.weight() / (rrfK + rank + 1), Double::sum);
            }
        }
        return fused;
    }

    public record RetrievalConfig(
            boolean useVector,
            boolean useKeyword,
            boolean useGraph,
            double weightVector,
            double weightKeyword,
            double weightGraph,
            int rrfK,
            int candidateN,
            int topK
    ) {
        public static RetrievalConfig defaults(int topK) {
            return new RetrievalConfig(true, true, true, 1.0D, 1.0D, 0.8D, 60, 50, topK);
        }
    }

    public List<RagDocumentItem> search(String organizationId, String question) {
        return retrieveContext(organizationId, question).references();
    }

    public List<RagDocumentItem> listDocuments(String organizationId) {
        return jdbcTemplate.query("""
                select id, title, source_type, content, source_url, source_file_name
                from app_rag_document
                where organization_id = ?
                order by created_at desc, id asc
                """, (resultSet, rowNum) -> toItem(
                resultSet.getString("id"),
                resultSet.getString("title"),
                resultSet.getString("source_type"),
                resultSet.getString("content"),
                resultSet.getString("source_url"),
                resultSet.getString("source_file_name")
        ), organizationId);
    }

    public RagDocumentItem createDocument(String organizationId, RagDocumentCreateRequest request) {
        return createDocument(organizationId, request, "", "");
    }

    /**
     * @param sourceUrl      原始文件地址；文本录入传空
     * @param sourceFileName 原始文件名，用于界面展示
     */
    public RagDocumentItem createDocument(
            String organizationId, RagDocumentCreateRequest request, String sourceUrl, String sourceFileName) {
        String id = "rag-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                insert into app_rag_document (id, organization_id, title, source_type, content, embedding_text,
                    source_url, source_file_name, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                organizationId,
                request.title().trim(),
                request.sourceType().trim().toUpperCase(Locale.ROOT),
                request.content().trim(),
                request.content().trim(),
                sourceUrl == null ? "" : sourceUrl,
                sourceFileName == null ? "" : sourceFileName,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now)
        );
        RagDocumentItem document = getDocument(organizationId, id);
        rebuildDocumentChunks(organizationId, document);
        externalIndexClient.indexDocument(organizationId, document);
        knowledgeGraphService.buildForDocument(organizationId, id);
        return document;
    }

    private static final int MAX_DOCUMENT_CHARS = 12000;

    /**
     * 解析预览：分页解析（文字层优先、视觉兜底），归纳后返回，但不落库。
     * 用户看过解析质量与"哪些页用了视觉"再决定是否入库。
     */
    public RagDocumentAnalysis analyzePdf(MultipartFile file) {
        byte[] bytes = readPdfBytes(file);
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PdfIngestService.ParsedPdf parsed = pdfIngestService.parseCached(bytes, document);
            String full = joinPages(parsed);
            if (full.isBlank()) {
                throw new AppException(HttpStatus.BAD_REQUEST,
                        parsed.visionAvailable()
                                ? "PDF 未解析到任何内容"
                                : "PDF 无文字层且未配置视觉模型，无法解析（扫描件需要配置视觉模型）");
            }
            Summary summary = summarize(full);
            int emptyPages = (int) parsed.pages().stream().filter(page -> page.markdown().isBlank()).count();
            return new RagDocumentAnalysis(
                    file.getOriginalFilename(),
                    parsed.pageCount(),
                    full.length(),
                    false,
                    truncate(full),
                    summary.status(),
                    summary.summary(),
                    summary.keyPoints(),
                    new RagDocumentAnalysis.ParseStats(
                            parsed.visionAvailable(), parsed.visionPages(), parsed.describedImages(),
                            emptyPages, groupPages(parsed).size())
            );
        } catch (IOException exception) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PDF 文本解析失败");
        }
    }

    /**
     * 入库：按页分组成多个文档，而不是截断到 12000 字符。
     *
     * <p>此前 43 页的白皮书抽出 34,756 字符，直接截断意味着约 2/3 内容被静默丢弃。
     */
    public List<RagDocumentItem> uploadPdf(String organizationId, MultipartFile file, String title) {
        byte[] bytes = readPdfBytes(file);
        String baseTitle = title == null || title.isBlank() ? file.getOriginalFilename() : title.trim();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PdfIngestService.ParsedPdf parsed = pdfIngestService.parseCached(bytes, document);
            List<PageGroup> groups = groupPages(parsed);
            if (groups.isEmpty()) {
                throw new AppException(HttpStatus.BAD_REQUEST, "PDF 未解析到任何内容");
            }
            // 原件同样落盘：解析出的文本丢失了排版、插图位置与页码，
            // 被追问"这段话在原文哪一页"时只有原件能回答
            String sourceUrl = assetStorage.store(bytes, "pdf");
            String sourceFileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            List<RagDocumentItem> created = new ArrayList<>();
            for (PageGroup group : groups) {
                String suffix = group.firstPage() == group.lastPage()
                        ? " — 第 %d 页".formatted(group.firstPage())
                        : " — 第 %d-%d 页".formatted(group.firstPage(), group.lastPage());
                String documentTitle = (baseTitle + suffix);
                if (documentTitle.length() > 255) {
                    documentTitle = documentTitle.substring(0, 255);
                }
                created.add(createDocument(organizationId,
                        new RagDocumentCreateRequest(documentTitle, "PDF", group.text()),
                        sourceUrl, sourceFileName));
            }
            return created;
        } catch (IOException exception) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PDF 文本解析失败");
        }
    }

    private record PageGroup(int firstPage, int lastPage, String text) {
    }

    /** 连续页面合并到接近单文档上限为止，保持页序且不跨文件混排。 */
    private List<PageGroup> groupPages(PdfIngestService.ParsedPdf parsed) {
        List<PageGroup> groups = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int first = 0;
        int last = 0;
        for (PdfIngestService.ParsedPage page : parsed.pages()) {
            if (page.markdown().isBlank()) {
                continue;
            }
            String block = page.markdown();
            if (buffer.length() > 0 && buffer.length() + block.length() + 2 > MAX_DOCUMENT_CHARS) {
                groups.add(new PageGroup(first, last, buffer.toString()));
                buffer.setLength(0);
            }
            if (buffer.length() == 0) {
                first = page.pageNumber();
            }
            if (buffer.length() > 0) {
                buffer.append("\n\n");
            }
            // 单页本身就超上限时硬切，避免整页被丢弃
            buffer.append(block.length() > MAX_DOCUMENT_CHARS ? block.substring(0, MAX_DOCUMENT_CHARS) : block);
            last = page.pageNumber();
        }
        if (buffer.length() > 0) {
            groups.add(new PageGroup(first, last, buffer.toString()));
        }
        return groups;
    }

    private String joinPages(PdfIngestService.ParsedPdf parsed) {
        return parsed.pages().stream()
                .map(PdfIngestService.ParsedPage::markdown)
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    private byte[] readPdfBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PDF 文件不能为空");
        }
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!filename.endsWith(".pdf")) {
            throw new AppException(HttpStatus.BAD_REQUEST, "仅支持上传 PDF 文件");
        }
        byte[] bytes;
        try (InputStream inputStream = file.getInputStream()) {
            bytes = inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PDF 读取失败");
        }
        if (bytes.length < 5
                || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F' || bytes[4] != '-') {
            throw new AppException(HttpStatus.BAD_REQUEST, "文件内容不是有效的 PDF");
        }
        return bytes;
    }

    /** 对已入库文档做归纳，用于知识库里的"原文 → 摘要"对照。 */
    public RagDocumentAnalysis summarizeDocument(String organizationId, String documentId) {
        RagDocumentItem document = getDocument(organizationId, documentId);
        String text = document.content() == null ? "" : document.content();
        Summary summary = summarize(text);
        return new RagDocumentAnalysis(
                document.title(),
                0,
                text.length(),
                false,
                text,
                summary.status(),
                summary.summary(),
                summary.keyPoints(),
                RagDocumentAnalysis.ParseStats.none()
        );
    }

    private record Summary(String status, String summary, List<String> keyPoints) {
    }

    /** 送给模型的正文上限：摘要用不到全文，且要控制单次调用的时延与开销。 */
    private static final int SUMMARY_INPUT_CHARS = 6000;

    private Summary summarize(String text) {
        if (!llmCompletionService.isEnabled()) {
            // 未配置模型时明确回报状态，由界面显示原文，不编造摘要
            return new Summary("LLM_DISABLED", "", List.of());
        }
        String input = text.length() > SUMMARY_INPUT_CHARS ? text.substring(0, SUMMARY_INPUT_CHARS) : text;
        try {
            String json = llmCompletionService.completeJson(List.of(
                    new ChatMessage("system", """
                            你是麒麟操作系统运维知识库的整理助手。请把用户提供的文档正文归纳成便于检索和复用的内容。
                            只依据原文，不要补充原文没有的信息；原文信息不足时如实说明。
                            仅输出 JSON，格式为：
                            {"summary": "一段不超过 200 字的中文概述", "keyPoints": ["要点1", "要点2"]}
                            keyPoints 给 3 到 6 条，每条不超过 40 字，优先保留命令、配置项、文件路径等可操作信息。
                            """),
                    new ChatMessage("user", input)
            ));
            JsonNode root = new ObjectMapper().readTree(json);
            String summary = root.path("summary").asText("").trim();
            List<String> keyPoints = new ArrayList<>();
            for (JsonNode item : root.path("keyPoints")) {
                String point = item.asText("").trim();
                if (!point.isBlank()) {
                    keyPoints.add(point);
                }
            }
            if (summary.isBlank() && keyPoints.isEmpty()) {
                return new Summary("FAILED", "", List.of());
            }
            return new Summary("OK", summary, keyPoints);
        } catch (Exception exception) {
            log.warn("文档摘要失败: {}", exception.getMessage());
            return new Summary("FAILED", "", List.of());
        }
    }

    private String truncate(String text) {
        return text.length() > MAX_DOCUMENT_CHARS ? text.substring(0, MAX_DOCUMENT_CHARS) : text;
    }

    /**
     * 某文档的全部分片，按序返回。
     *
     * <p>知识库此前只能看到文档，看不到它被切成了什么样——而检索、图谱抽取、证据回链
     * 全都发生在分片粒度上。缺这个视图，从图谱跳过来就没有落点。
     */
    public List<RagChunkItem> listDocumentChunks(String organizationId, String documentId) {
        return jdbcTemplate.query("""
                select id, document_id, chunk_index, title, source_type, content
                from app_rag_chunk
                where organization_id = ? and document_id = ?
                order by chunk_index
                """, (rs, n) -> new RagChunkItem(
                rs.getString("id"),
                rs.getString("document_id"),
                rs.getInt("chunk_index"),
                rs.getString("title"),
                rs.getString("source_type"),
                rs.getString("content"),
                0.0D
        ), organizationId, documentId);
    }

    public RagDocumentItem updateDocument(String organizationId, String documentId, RagDocumentCreateRequest request) {
        int updated = jdbcTemplate.update("""
                update app_rag_document
                set title = ?, source_type = ?, content = ?, embedding_text = ?, updated_at = ?
                where organization_id = ? and id = ?
                """,
                request.title().trim(),
                request.sourceType().trim().toUpperCase(Locale.ROOT),
                request.content().trim(),
                request.content().trim(),
                Timestamp.valueOf(LocalDateTime.now()),
                organizationId,
                documentId
        );
        if (updated == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "知识片段不存在");
        }
        RagDocumentItem document = getDocument(organizationId, documentId);
        rebuildDocumentChunks(organizationId, document);
        externalIndexClient.indexDocument(organizationId, document);
        knowledgeGraphService.buildForDocument(organizationId, documentId);
        return document;
    }

    public void deleteDocument(String organizationId, String documentId) {
        int deleted = jdbcTemplate.update(
                "delete from app_rag_document where organization_id = ? and id = ?",
                organizationId,
                documentId
        );
        if (deleted == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "知识片段不存在");
        }
        externalIndexClient.deleteDocument(organizationId, documentId);
    }

    public List<RagMemoryItem> listMemories(String organizationId) {
        return jdbcTemplate.query("""
                select id, memory_type, title, content, importance, source_document_id
                from app_rag_memory
                where organization_id = ?
                order by importance desc, updated_at desc
                """, (resultSet, rowNum) -> new RagMemoryItem(
                resultSet.getString("id"),
                resultSet.getString("memory_type"),
                resultSet.getString("title"),
                resultSet.getString("content"),
                resultSet.getInt("importance"),
                resultSet.getString("source_document_id")
        ), organizationId);
    }

    public RagMemoryItem createMemory(String organizationId, RagMemoryCreateRequest request) {
        String id = "mem-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        LocalDateTime now = LocalDateTime.now();
        String content = request.content().trim();
        jdbcTemplate.update("""
                insert into app_rag_memory (
                    id, organization_id, memory_type, title, content, importance, source_document_id,
                    embedding_json, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                organizationId,
                request.memoryType().trim().toUpperCase(Locale.ROOT),
                request.title().trim(),
                content,
                request.importance(),
                blankToNull(request.sourceDocumentId()),
                embeddingService.toJson(embeddingService.embed(request.title().trim() + "\n" + content)),
                Timestamp.valueOf(now),
                Timestamp.valueOf(now)
        );
        RagMemoryItem memory = getMemory(organizationId, id);
        externalIndexClient.storeMemory(organizationId, memory);
        return memory;
    }

    public void deleteMemory(String organizationId, String memoryId) {
        int deleted = jdbcTemplate.update(
                "delete from app_rag_memory where organization_id = ? and id = ?",
                organizationId,
                memoryId
        );
        if (deleted == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "记忆不存在");
        }
    }

    public List<RagGraphEdgeItem> listGraphEdges(String organizationId) {
        return jdbcTemplate.query("""
                select id, source_node, relation, target_node, evidence, source_document_id
                from app_rag_graph_edge
                where organization_id = ?
                order by created_at desc
                """, (resultSet, rowNum) -> new RagGraphEdgeItem(
                resultSet.getString("id"),
                resultSet.getString("source_node"),
                resultSet.getString("relation"),
                resultSet.getString("target_node"),
                resultSet.getString("evidence"),
                resultSet.getString("source_document_id")
        ), organizationId);
    }

    public RagGraphEdgeItem createGraphEdge(String organizationId, RagGraphEdgeCreateRequest request) {
        String id = "edge-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update("""
                insert into app_rag_graph_edge (
                    id, organization_id, source_node, relation, target_node, evidence, source_document_id, created_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                organizationId,
                request.sourceNode().trim(),
                request.relation().trim().toUpperCase(Locale.ROOT),
                request.targetNode().trim(),
                blankToNull(request.evidence()),
                blankToNull(request.sourceDocumentId()),
                Timestamp.valueOf(LocalDateTime.now())
        );
        return getGraphEdge(organizationId, id);
    }

    public RagGraphEdgeItem updateGraphEdge(String organizationId, String edgeId, RagGraphEdgeCreateRequest request) {
        int updated = jdbcTemplate.update("""
                update app_rag_graph_edge
                set source_node = ?, relation = ?, target_node = ?, evidence = ?, source_document_id = ?
                where organization_id = ? and id = ?
                """,
                request.sourceNode().trim(),
                request.relation().trim().toUpperCase(Locale.ROOT),
                request.targetNode().trim(),
                blankToNull(request.evidence()),
                blankToNull(request.sourceDocumentId()),
                organizationId,
                edgeId
        );
        if (updated == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "知识图谱关系不存在");
        }
        return getGraphEdge(organizationId, edgeId);
    }

    public void deleteGraphEdge(String organizationId, String edgeId) {
        int deleted = jdbcTemplate.update(
                "delete from app_rag_graph_edge where organization_id = ? and id = ?", organizationId, edgeId);
        if (deleted == 0) {
            throw new AppException(HttpStatus.NOT_FOUND, "知识图谱关系不存在");
        }
    }

    public void createKgEdge(String organizationId, KgEdgeRequest request) {
        knowledgeGraphService.createEdgeByName(organizationId, request.sourceNode(), request.relation(),
                request.targetNode(), request.evidence());
    }

    public void updateKgEdge(String organizationId, String edgeId, KgEdgeRequest request) {
        knowledgeGraphService.updateEdge(organizationId, edgeId, request.relation(), request.evidence());
    }

    public void deleteKgEdge(String organizationId, String edgeId) {
        knowledgeGraphService.deleteEdge(organizationId, edgeId);
    }

    /** 图谱节点的来源分片（证据回链）。 */
    public List<RagNodeChunkItem> nodeChunks(String organizationId, String nodeId, int limit) {
        return knowledgeGraphService.nodeChunks(organizationId, nodeId, limit);
    }

    public RagSubgraph graph(String organizationId, String nodeType, int limit) {
        return knowledgeGraphService.fullGraph(organizationId, nodeType, limit <= 0 ? 300 : limit);
    }

    public RagSubgraph graphNeighbors(String organizationId, String node, int hops) {
        return knowledgeGraphService.neighbors(organizationId, node, hops <= 0 ? 1 : hops);
    }

    public Map<String, Object> graphStats(String organizationId) {
        return knowledgeGraphService.stats(organizationId);
    }

    public void rebuildGraph(String organizationId) {
        for (RagDocumentItem document : listDocuments(organizationId)) {
            knowledgeGraphService.buildForDocument(organizationId, document.id());
        }
    }

    public RagMemoryItem depositDocument(String organizationId, String documentId) {
        RagDocumentItem document = getDocument(organizationId, documentId);
        String content = document.content().trim();
        String memoryContent = content.length() > 1200 ? content.substring(0, 1200) : content;
        return createMemory(organizationId, new RagMemoryCreateRequest(
                "DOCUMENT_SUMMARY",
                document.title(),
                memoryContent,
                3,
                document.id()
        ));
    }

    public RagHealthResponse health() {
        boolean enabled = externalIndexClient.externalIndexEnabled();
        return new RagHealthResponse(enabled, !enabled || externalIndexClient.healthCheck());
    }

    /**
     * 只重建外部索引（Elasticsearch / Milvus / Redis），不重算向量、不重抽图谱。
     *
     * 用于"先有知识库、后启用外部索引"的场景：走 {@link #rebuildAll} 会连带调用
     * embedding 与 LLM 接口，对已经建好的库来说是纯粹的时间与费用浪费。
     */
    public int reindexExternal(String organizationId) {
        Map<String, List<RagChunkItem>> chunksByDocument = new LinkedHashMap<>();
        for (RagChunkRow row : listChunkRows(organizationId, false)) {
            chunksByDocument.computeIfAbsent(row.documentId(), key -> new ArrayList<>()).add(row.toItem(0.0D));
        }
        int indexed = 0;
        for (RagDocumentItem document : listDocuments(organizationId)) {
            List<RagChunkItem> chunks = chunksByDocument.getOrDefault(document.id(), List.of());
            // chunk 粒度写 ES；文档粒度写 Milvus/Redis（各自按开关生效）
            externalIndexClient.indexChunks(organizationId, document.id(), chunks);
            externalIndexClient.indexDocument(organizationId, document);
            indexed += chunks.size();
        }
        return indexed;
    }

    public void rebuildAll(String organizationId) {
        List<RagDocumentItem> documents = listDocuments(organizationId);
        for (RagDocumentItem document : documents) {
            rebuildDocumentChunks(organizationId, document);
            externalIndexClient.indexDocument(organizationId, document);
            knowledgeGraphService.buildForDocument(organizationId, document.id());
        }
    }

    private void ensureChunks(String organizationId) {
        List<RagDocumentItem> missing = jdbcTemplate.query("""
                select d.id, d.title, d.source_type, d.content, d.source_url, d.source_file_name
                from app_rag_document d
                where d.organization_id = ?
                  and not exists (
                      select 1 from app_rag_chunk c where c.document_id = d.id
                  )
                """, (resultSet, rowNum) -> toItem(
                resultSet.getString("id"),
                resultSet.getString("title"),
                resultSet.getString("source_type"),
                resultSet.getString("content"),
                resultSet.getString("source_url"),
                resultSet.getString("source_file_name")
        ), organizationId);
        for (RagDocumentItem document : missing) {
            rebuildDocumentChunks(organizationId, document);
        }
    }

    private void rebuildDocumentChunks(String organizationId, RagDocumentItem document) {
        jdbcTemplate.update("delete from app_rag_chunk where document_id = ?", document.id());
        RagSettings settings = settingsService.current();
        List<String> chunks = splitChunks(document.content(), settings.chunkSize(), settings.chunkOverlap());
        LocalDateTime now = LocalDateTime.now();
        List<RagChunkItem> chunkItems = new ArrayList<>();
        List<RagVectorIndex.ChunkVector> vectors = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            String content = chunks.get(index);
            String chunkId = "chk-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            List<Double> embedding = embeddingService.embed(document.title() + "\n" + content);
            jdbcTemplate.update("""
                    insert into app_rag_chunk (
                        id, organization_id, document_id, chunk_index, title, source_type, content,
                        embedding_json, token_count, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    chunkId,
                    organizationId,
                    document.id(),
                    index,
                    document.title(),
                    document.sourceType(),
                    content,
                    embeddingService.toJson(embedding),
                    tokenize(content).size(),
                    Timestamp.valueOf(now)
            );
            chunkItems.add(new RagChunkItem(chunkId, document.id(), index, document.title(), document.sourceType(), content, 0.0D));
            vectors.add(new RagVectorIndex.ChunkVector(chunkId, embedding));
        }
        // embedding_json 始终写（H2 降级路径与跨库迁移都要读它）；PostgreSQL 上再批量灌进
        // pgvector 列，两者同源于同一次 embed 调用，不会出现"两套向量对不上"。
        vectorIndex.store(vectors);
        externalIndexClient.indexChunks(organizationId, document.id(), chunkItems);
    }

    private List<String> splitChunks(String content, int chunkSize, int overlap) {
        String normalized = content == null ? "" : content.trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        int step = Math.max(1, chunkSize - overlap);
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + chunkSize);
            chunks.add(normalized.substring(start, end).trim());
            if (end == normalized.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }

    private List<RagChunkRow> listChunkRows(String organizationId) {
        return listChunkRows(organizationId, true);
    }

    /**
     * @param withEmbedding 是否连 embedding_json 一起取。只有 JVM 内存余弦这条降级路径需要它；
     *                      pgvector 路径下向量排序在库内完成，取回来纯属浪费内存与带宽。
     */
    private List<RagChunkRow> listChunkRows(String organizationId, boolean withEmbedding) {
        String sql = """
                select id, document_id, chunk_index, title, source_type, content%s
                from app_rag_chunk
                where organization_id = ?
                """.formatted(withEmbedding ? ", embedding_json" : "");
        return jdbcTemplate.query(sql, (resultSet, rowNum) -> new RagChunkRow(
                resultSet.getString("id"),
                resultSet.getString("document_id"),
                resultSet.getInt("chunk_index"),
                resultSet.getString("title"),
                resultSet.getString("source_type"),
                resultSet.getString("content"),
                withEmbedding ? embeddingService.fromJson(resultSet.getString("embedding_json")) : List.of()
        ), organizationId);
    }

    private List<RagMemoryItem> searchMemories(String organizationId, List<Double> queryEmbedding, Set<String> terms, int topK) {
        List<RagMemoryRow> rows = jdbcTemplate.query("""
                select id, memory_type, title, content, importance, source_document_id, embedding_json
                from app_rag_memory
                where organization_id = ?
                """, (resultSet, rowNum) -> new RagMemoryRow(
                new RagMemoryItem(
                        resultSet.getString("id"),
                        resultSet.getString("memory_type"),
                        resultSet.getString("title"),
                        resultSet.getString("content"),
                        resultSet.getInt("importance"),
                        resultSet.getString("source_document_id")
                ),
                resultSet.getString("embedding_json")
        ), organizationId);

        return rows.stream()
                .map(row -> row.withScore(memoryScore(row, queryEmbedding, terms)))
                .filter(row -> terms.isEmpty() || row.score() > 0.0D)
                .sorted(Comparator.comparingDouble(RagMemoryRow::score).reversed())
                .limit(Math.min(5, topK))
                .map(RagMemoryRow::item)
                .toList();
    }

    private double memoryScore(RagMemoryRow row, List<Double> queryEmbedding, Set<String> terms) {
        List<Double> embedding = row.embeddingJson() == null || row.embeddingJson().isBlank()
                ? embeddingService.embed(row.item().title() + "\n" + row.item().content())
                : embeddingService.fromJson(row.embeddingJson());
        double vectorScore = Math.max(0.0D, embeddingService.cosine(queryEmbedding, embedding));
        double keywordScore = keywordScore(row.item().title() + " " + row.item().content(), terms);
        double importance = Math.min(1.0D, Math.max(0.0D, row.item().importance() / 5.0D));
        return vectorScore * 0.65D + keywordScore * 0.25D + importance * 0.10D;
    }

    private Set<String> tokenize(String question) {
        return Arrays.stream((question == null ? "" : question).toLowerCase(Locale.ROOT).split("[^a-z0-9\\u4e00-\\u9fa5]+"))
                .filter(term -> term.length() >= 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private double keywordScore(String text, Set<String> terms) {
        if (terms.isEmpty()) {
            return 0.0D;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String term : terms) {
            if (haystack.contains(term)) {
                hits++;
            }
        }
        return (double) hits / (double) terms.size();
    }

    private RagDocumentItem getDocument(String organizationId, String documentId) {
        List<RagDocumentItem> rows = jdbcTemplate.query("""
                select id, title, source_type, content, source_url, source_file_name
                from app_rag_document
                where organization_id = ? and id = ?
                """, (resultSet, rowNum) -> toItem(
                resultSet.getString("id"),
                resultSet.getString("title"),
                resultSet.getString("source_type"),
                resultSet.getString("content"),
                resultSet.getString("source_url"),
                resultSet.getString("source_file_name")
        ), organizationId, documentId);

        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "知识片段不存在");
        }
        return rows.get(0);
    }

    private RagMemoryItem getMemory(String organizationId, String memoryId) {
        List<RagMemoryItem> rows = jdbcTemplate.query("""
                select id, memory_type, title, content, importance, source_document_id
                from app_rag_memory
                where organization_id = ? and id = ?
                """, (resultSet, rowNum) -> new RagMemoryItem(
                resultSet.getString("id"),
                resultSet.getString("memory_type"),
                resultSet.getString("title"),
                resultSet.getString("content"),
                resultSet.getInt("importance"),
                resultSet.getString("source_document_id")
        ), organizationId, memoryId);
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "记忆不存在");
        }
        return rows.get(0);
    }

    private RagGraphEdgeItem getGraphEdge(String organizationId, String edgeId) {
        List<RagGraphEdgeItem> rows = jdbcTemplate.query("""
                select id, source_node, relation, target_node, evidence, source_document_id
                from app_rag_graph_edge
                where organization_id = ? and id = ?
                """, (resultSet, rowNum) -> new RagGraphEdgeItem(
                resultSet.getString("id"),
                resultSet.getString("source_node"),
                resultSet.getString("relation"),
                resultSet.getString("target_node"),
                resultSet.getString("evidence"),
                resultSet.getString("source_document_id")
        ), organizationId, edgeId);
        if (rows.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "知识图谱关系不存在");
        }
        return rows.get(0);
    }

    private List<RagDocumentItem> findDocumentsByIds(String organizationId, List<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<String, RagDocumentItem> documentsById = new LinkedHashMap<>();
        List<RagDocumentItem> documents = jdbcTemplate.query("""
                select id, title, source_type, content, source_url, source_file_name
                from app_rag_document
                where organization_id = ?
                """, (resultSet, rowNum) -> toItem(
                resultSet.getString("id"),
                resultSet.getString("title"),
                resultSet.getString("source_type"),
                resultSet.getString("content"),
                resultSet.getString("source_url"),
                resultSet.getString("source_file_name")
        ), organizationId);
        for (RagDocumentItem document : documents) {
            documentsById.put(document.id(), document);
        }
        List<RagDocumentItem> ordered = new ArrayList<>();
        for (String id : ids) {
            RagDocumentItem document = documentsById.get(id);
            if (document != null) {
                ordered.add(document);
            }
        }
        return ordered;
    }

    private List<String> uniqueDocumentIds(List<RagChunkItem> chunks) {
        return chunks.stream()
                .map(RagChunkItem::documentId)
                .distinct()
                .toList();
    }

    private RagDocumentItem toItem(
            String id, String title, String sourceType, String content, String sourceUrl, String sourceFileName) {
        return new RagDocumentItem(id, title, sourceType, content,
                sourceUrl == null ? "" : sourceUrl, sourceFileName == null ? "" : sourceFileName);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record RagChunkRow(
            String id,
            String documentId,
            int chunkIndex,
            String title,
            String sourceType,
            String content,
            List<Double> embedding
    ) {
        private RagChunkItem toItem(double score) {
            return new RagChunkItem(id, documentId, chunkIndex, title, sourceType, content, score);
        }
    }

    private record RagMemoryRow(RagMemoryItem item, String embeddingJson, double score) {
        private RagMemoryRow(RagMemoryItem item, String embeddingJson) {
            this(item, embeddingJson, 0.0D);
        }

        private RagMemoryRow withScore(double score) {
            return new RagMemoryRow(item, embeddingJson, score);
        }
    }
}
