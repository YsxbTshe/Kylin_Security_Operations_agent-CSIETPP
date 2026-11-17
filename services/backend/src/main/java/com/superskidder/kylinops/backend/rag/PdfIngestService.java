package com.superskidder.kylinops.backend.rag;

import com.superskidder.kylinops.backend.llm.LlmCompletionService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * PDF 分页解析：文字层优先，视觉兜底。
 *
 * <p>为什么不一律走视觉：麒麟官方文档基本都有文字层，抽取瞬时且免费；一份 43 页白皮书全量
 * 走视觉约需 8.6 分钟、12 万 token。反过来也不能只靠文字层——它会丢掉表格结构，并且把插图
 * 整张丢弃（手册第 36 页那张"用户账户管理界面"截图对安全运维知识库是核心内容）。
 *
 * <p>因此按页分级：
 * <ol>
 *   <li>文字层字符数过少 → 判定为扫描页，整页渲染成图交给视觉模型；</li>
 *   <li>页内有"有内容的插图" → 只把插图交给视觉模型生成描述，正文仍用文字层；</li>
 *   <li>其余页 → 纯文字层，零成本。</li>
 * </ol>
 *
 * <p>插图描述会写进 Markdown 的 alt 文本。这一步是检索的关键：图片本身不可检索，
 * 但它的描述会参与 embedding 与 BM25，于是"怎么删除用户"才能命中那张截图。
 */
@Service
public class PdfIngestService {

    private static final Logger log = LoggerFactory.getLogger(PdfIngestService.class);

    /** 低于此字符数的页判定为扫描页（正常排版页远高于此值） */
    private static final int SCANNED_PAGE_TEXT_THRESHOLD = 40;
    /** 小于此像素面积的图判定为图标/装饰。实测麒麟手册 84% 的图是 68x97 的 logo */
    private static final int MIN_CONTENT_IMAGE_AREA = 40_000;
    /** 极端细长的图多为分隔线/页眉装饰 */
    private static final double MAX_ASPECT_RATIO = 12.0;
    /** 整页渲染的 DPI：够视觉模型读清中文小字，又不至于让请求体过大 */
    private static final float RENDER_DPI = 150f;

    private final RagAssetStorageService assetStorage;
    private final LlmCompletionService llmCompletionService;
    private final RagSettingsService settingsService;

    public PdfIngestService(
            RagAssetStorageService assetStorage,
            LlmCompletionService llmCompletionService,
            RagSettingsService settingsService
    ) {
        this.assetStorage = assetStorage;
        this.llmCompletionService = llmCompletionService;
        this.settingsService = settingsService;
    }

    /** 视觉模型配置在 RAG 设置里选，避免要求运维去改启动参数填一个自动生成的 id。 */
    private String visionConfigId() {
        return settingsService.current().visionConfigId();
    }

    /** 一页的解析结果。 */
    public record ParsedPage(int pageNumber, String markdown, String mode, int imageCount) {
    }

    /**
     * 解析结果。{@code visionPages} / {@code describedImages} 用于如实呈现"哪些页用了视觉"，
     * 不能让界面一律显示成"已智能解析"。
     */
    public record ParsedPdf(
            int pageCount,
            List<ParsedPage> pages,
            int visionPages,
            int describedImages,
            boolean visionAvailable
    ) {
    }

    /**
     * 解析结果缓存。
     *
     * <p>界面是"先预览、再确认入库"两步，同一份 PDF 会被解析两次；视觉调用按次计费，
     * 第二次纯属浪费。按文件内容哈希缓存，命中即秒回。
     *
     * <p>只缓存少量条目并设过期：解析结果含整本文档的文本，一份 400 页手册约几百 KB，
     * 无上限缓存会把堆吃满。
     */
    private static final int PARSE_CACHE_MAX_ENTRIES = 4;
    private static final long PARSE_CACHE_TTL_MILLIS = 30 * 60 * 1000L;

    private record CachedParse(ParsedPdf parsed, long createdAt) {
    }

    private final Map<String, CachedParse> parseCache =
            Collections.synchronizedMap(new LinkedHashMap<>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedParse> eldest) {
                    return size() > PARSE_CACHE_MAX_ENTRIES;
                }
            });

    /** 按 PDF 原始字节的哈希取缓存；未命中则解析并缓存。 */
    public ParsedPdf parseCached(byte[] pdfBytes, PDDocument document) {
        String key = assetStorage.sha256(pdfBytes);
        CachedParse cached = parseCache.get(key);
        if (cached != null && System.currentTimeMillis() - cached.createdAt() < PARSE_CACHE_TTL_MILLIS) {
            log.debug("PDF 解析缓存命中，跳过重复的视觉调用");
            return cached.parsed();
        }
        ParsedPdf parsed = parse(document);
        parseCache.put(key, new CachedParse(parsed, System.currentTimeMillis()));
        return parsed;
    }

    public ParsedPdf parse(PDDocument document) {
        String visionConfigId = visionConfigId();
        boolean visionAvailable = !visionConfigId.isBlank()
                && llmCompletionService.isVisionEnabled(visionConfigId);
        PDFRenderer renderer = new PDFRenderer(document);
        List<ParsedPage> pages = new ArrayList<>();
        // 同一张图（如每页重复的 logo）只描述一次，按内容哈希去重
        Set<String> describedHashes = new HashSet<>();
        int visionPages = 0;
        int describedImages = 0;

        for (int index = 0; index < document.getNumberOfPages(); index++) {
            int pageNumber = index + 1;
            String text = extractPageText(document, pageNumber);

            if (text.length() < SCANNED_PAGE_TEXT_THRESHOLD) {
                // 扫描页：没有文字层可用，只能整页交给视觉
                if (!visionAvailable) {
                    pages.add(new ParsedPage(pageNumber, "", "NO_TEXT_LAYER", 0));
                    continue;
                }
                String markdown = describeWholePage(renderer, index, visionConfigId);
                pages.add(new ParsedPage(pageNumber, markdown, "VISION_PAGE", 0));
                visionPages++;
                continue;
            }

            List<byte[]> figures = contentImagesOf(document.getPage(index));
            StringBuilder markdown = new StringBuilder(text);
            int described = 0;
            for (byte[] figure : figures) {
                String hash = assetStorage.sha256(figure);
                String url = assetStorage.store(figure, "png");
                String alt = "插图";
                if (visionAvailable && describedHashes.add(hash)) {
                    String generated = describeFigure(figure, visionConfigId);
                    if (!generated.isBlank()) {
                        alt = generated;
                        described++;
                    }
                }
                // 描述进入正文参与检索，URL 供界面渲染真实图片
                markdown.append("\n\n![").append(alt.replace("]", "）")).append("](").append(url).append(")");
            }
            describedImages += described;
            pages.add(new ParsedPage(pageNumber, markdown.toString(),
                    figures.isEmpty() ? "TEXT" : "TEXT_WITH_FIGURES", figures.size()));
        }
        return new ParsedPdf(document.getNumberOfPages(), pages, visionPages, describedImages, visionAvailable);
    }

    /** 页眉页脚：只含页码的行，混在正文里既难读也会污染 embedding */
    private static final Pattern PAGE_FOOTER = Pattern.compile(
            "^\\s*(第\\s*\\d+\\s*页\\s*/\\s*共\\s*\\d+\\s*页|\\d+\\s*/\\s*\\d+|[-–—]?\\s*\\d{1,4}\\s*[-–—]?)\\s*$");

    /** 结构行：列表项、编号标题等，必须自成一行，不能被并进上一段 */
    private static final Pattern STRUCTURAL_LINE = Pattern.compile(
            "^\\s*([●•◦▪·]|[-*+]\\s|\\(?\\d+[.)、]\\s*|第[一二三四五六七八九十百]+[章节部分]|\\d+(\\.\\d+)*\\s+\\S).*");

    /**
     * 把 PDF 抽取出的"视觉行"重排成段落。
     *
     * <p>PDFTextStripper 按版面换行输出，一行就是页面上的一行——中文更是会把词从中间截断
     * （"…高可用性产" / "品，能够给用户…"）。直接入库有两重损害：阅读时全是碎行；
     * 分片会在句子中间断开，页脚"第 35 页 / 共 40 页"也会进入向量。
     *
     * <p>判据用经典的行宽启发式：接近该页最大行宽的行是被强制折行的，应与下一行合并；
     * 明显短于最大行宽的行是段落自然结束。再叠加结构行识别，避免把列表项并进正文。
     */
    private String reflow(String pageText) {
        List<String> lines = new ArrayList<>();
        for (String raw : pageText.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || PAGE_FOOTER.matcher(line).matches()) {
                // 空行保留为段落分隔，页脚直接丢弃
                if (line.isEmpty()) {
                    lines.add("");
                }
                continue;
            }
            lines.add(line);
        }
        if (lines.isEmpty()) {
            return "";
        }
        // 该页的"满行"宽度：用较长行的分位数，避免被个别超长行带偏。
        // 用视觉宽度而非字符数——中文字符宽度是拉丁字符的两倍，按字符数判断会把
        // 命令、CVE 编号这类拉丁密集的半行误判成满行，错误并进下一段。
        List<Integer> widths = lines.stream().filter(line -> !line.isEmpty())
                .map(PdfIngestService::visualWidth).sorted().toList();
        if (widths.isEmpty()) {
            return "";
        }
        int fullWidth = widths.get((int) (widths.size() * 0.9));
        double joinThreshold = fullWidth * 0.85;

        List<String> paragraphs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                flush(paragraphs, current);
                continue;
            }
            boolean structural = STRUCTURAL_LINE.matcher(line).matches();
            if (structural && current.length() > 0) {
                flush(paragraphs, current);
            }
            append(current, line);
            String next = i + 1 < lines.size() ? lines.get(i + 1) : "";
            boolean nextStructural = !next.isEmpty() && STRUCTURAL_LINE.matcher(next).matches();
            // 未达满行宽度说明这一行是段落末尾；下一行是结构行时也断开
            if (visualWidth(line) < joinThreshold || next.isEmpty() || nextStructural || structural) {
                flush(paragraphs, current);
            }
        }
        flush(paragraphs, current);
        return String.join("\n\n", paragraphs);
    }

    /** 视觉宽度：CJK 及全角字符按 2 计，其余按 1 计。 */
    private static int visualWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += text.charAt(i) > 0x2E80 ? 2 : 1;
        }
        return width;
    }

    private void flush(List<String> paragraphs, StringBuilder current) {
        if (current.length() > 0) {
            paragraphs.add(current.toString());
            current.setLength(0);
        }
    }

    /** 拼接换行处：中文直接相连，拉丁字母之间补空格，否则会把两个英文单词粘死 */
    private void append(StringBuilder current, String line) {
        if (current.length() == 0) {
            current.append(line);
            return;
        }
        char last = current.charAt(current.length() - 1);
        char first = line.charAt(0);
        if (isLatinWordChar(last) && isLatinWordChar(first)) {
            current.append(' ');
        }
        current.append(line);
    }

    private boolean isLatinWordChar(char value) {
        return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') || (value >= '0' && value <= '9');
    }

    private String extractPageText(PDDocument document, int pageNumber) {
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            return reflow(stripper.getText(document));
        } catch (IOException exception) {
            log.warn("抽取第 {} 页文字层失败: {}", pageNumber, exception.getMessage());
            return "";
        }
    }

    /** 页内“有内容的”插图：过滤掉图标、装饰与极端细长的分隔条。 */
    private List<byte[]> contentImagesOf(PDPage page) {
        List<byte[]> images = new ArrayList<>();
        collectImages(page.getResources(), images, 0);
        return images;
    }

    private void collectImages(PDResources resources, List<byte[]> out, int depth) {
        // Form XObject 可以嵌套，限制深度避免异常文档导致无限递归
        if (resources == null || depth > 3) {
            return;
        }
        for (var name : resources.getXObjectNames()) {
            try {
                PDXObject xObject = resources.getXObject(name);
                if (xObject instanceof PDFormXObject form) {
                    collectImages(form.getResources(), out, depth + 1);
                    continue;
                }
                if (!(xObject instanceof PDImageXObject image)) {
                    continue;
                }
                int width = image.getWidth();
                int height = image.getHeight();
                if ((long) width * height < MIN_CONTENT_IMAGE_AREA) {
                    continue;
                }
                double ratio = (double) Math.max(width, height) / Math.max(1, Math.min(width, height));
                if (ratio > MAX_ASPECT_RATIO) {
                    continue;
                }
                byte[] png = toPng(image.getImage());
                if (png.length > 0) {
                    out.add(png);
                }
            } catch (IOException exception) {
                log.warn("读取页面图片对象失败: {}", exception.getMessage());
            }
        }
    }

    private String describeWholePage(PDFRenderer renderer, int pageIndex, String visionConfigId) {
        try {
            byte[] png = toPng(renderer.renderImageWithDPI(pageIndex, RENDER_DPI));
            if (png.length == 0) {
                return "";
            }
            return llmCompletionService.completeVision(visionConfigId,
                    "把这一页完整转成 Markdown，保留标题层级、列表与表格结构。只输出 Markdown，不要解释。",
                    List.of(new LlmCompletionService.VisionImage("image/png", png)));
        } catch (Exception exception) {
            log.warn("第 {} 页视觉解析失败，按空页处理: {}", pageIndex + 1, exception.getMessage());
            return "";
        }
    }

    private String describeFigure(byte[] png, String visionConfigId) {
        try {
            String description = llmCompletionService.completeVision(visionConfigId,
                    """
                    用一句中文描述这张运维文档插图的内容，供检索使用。
                    如果是界面截图，说明它是什么界面、有哪些关键选项或按钮；
                    如果是架构图或流程图，说明它表达的结构与关键节点。
                    只输出描述本身，不要引号、不要前缀。
                    """,
                    List.of(new LlmCompletionService.VisionImage("image/png", png)));
            return description.replace("\n", " ").trim();
        } catch (Exception exception) {
            log.warn("插图描述失败，按无描述处理: {}", exception.getMessage());
            return "";
        }
    }

    private byte[] toPng(BufferedImage image) {
        if (image == null) {
            return new byte[0];
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException exception) {
            log.warn("图片编码失败: {}", exception.getMessage());
            return new byte[0];
        }
    }
}
