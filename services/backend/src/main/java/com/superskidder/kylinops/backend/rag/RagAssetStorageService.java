package com.superskidder.kylinops.backend.rag;

import com.superskidder.kylinops.backend.common.AppException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 知识库图片资产存储：PDF 里抽出的插图落盘，并通过静态资源前缀对外提供。
 *
 * <p>按内容哈希命名，天然去重——麒麟手册每页都嵌了同一张 68x97 的 logo（457 张图里有 388 张是它），
 * 按内容寻址后只会落一份，也不会被重复送去做视觉描述。
 *
 * <p>文件名只由内容哈希决定，不含用户可控字符串，避免路径穿越；写入前也会校验最终路径仍在
 * 资产目录内。
 */
@Service
public class RagAssetStorageService {

    private final Path directory;
    private final String urlPrefix;

    public RagAssetStorageService(
            @Value("${app.assets.rag-asset-dir:./data/rag-assets}") String assetDir,
            @Value("${app.assets.rag-asset-url-prefix:/uploads/rag-assets}") String urlPrefix
    ) {
        this.directory = Paths.get(assetDir).toAbsolutePath().normalize();
        this.urlPrefix = urlPrefix.endsWith("/") ? urlPrefix.substring(0, urlPrefix.length() - 1) : urlPrefix;
        try {
            Files.createDirectories(this.directory);
        } catch (IOException exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "创建知识库资产目录失败");
        }
    }

    /**
     * 按内容哈希存图，返回可访问的 URL。同样内容重复调用只落一份。
     *
     * @param extension 不含点的扩展名，如 {@code png}
     */
    public String store(byte[] data, String extension) {
        String safeExtension = extension == null || !extension.matches("[a-z0-9]{2,5}") ? "png" : extension;
        String hash = sha256(data);
        Path target = directory.resolve(hash + "." + safeExtension).normalize();
        if (!target.startsWith(directory)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "非法的资产路径");
        }
        try {
            if (!Files.exists(target)) {
                Files.write(target, data);
            }
        } catch (IOException exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "写入知识库资产失败");
        }
        return urlPrefix + "/" + target.getFileName();
    }

    /** 内容哈希，调用方可用它在送视觉模型前先做去重。 */
    public String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception exception) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "计算资产哈希失败");
        }
    }

    public Path directory() {
        return directory;
    }

    public String urlPrefix() {
        return urlPrefix;
    }
}
