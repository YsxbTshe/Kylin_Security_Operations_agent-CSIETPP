package com.superskidder.kylinops.backend.iam;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AvatarStorageService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/png", "image/jpeg", "image/webp");
    private static final long MAX_AVATAR_SIZE = 2L * 1024 * 1024;

    private final Path avatarDirectory;
    private final String avatarUrlPrefix;

    public AvatarStorageService(
            @Value("${app.assets.avatar-dir}") String avatarDirectory,
            @Value("${app.assets.avatar-url-prefix}") String avatarUrlPrefix
    ) {
        this.avatarDirectory = Path.of(avatarDirectory).toAbsolutePath().normalize();
        this.avatarUrlPrefix = avatarUrlPrefix;
    }

    public String store(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像文件不能为空");
        }
        if (file.getSize() > MAX_AVATAR_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像文件不能超过 2 MB");
        }

        String contentType = file.getContentType();
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像仅支持 PNG、JPG、WEBP");
        }

        String extension = switch (contentType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> "";
        };
        String filename = UUID.randomUUID().toString().replace("-", "") + extension;

        byte[] content;
        try (InputStream inputStream = file.getInputStream()) {
            content = inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "头像上传失败");
        }
        if (!matchesImageMagic(contentType, content)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像文件内容与声明的类型不匹配");
        }

        try {
            Files.createDirectories(avatarDirectory);
            try (InputStream inputStream = new ByteArrayInputStream(content)) {
                Files.copy(inputStream, avatarDirectory.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "头像上传失败");
        }

        return avatarUrlPrefix + "/" + filename;
    }

    private boolean matchesImageMagic(String contentType, byte[] content) {
        if (content == null || content.length < 12) {
            return false;
        }
        return switch (contentType) {
            case "image/png" -> startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            case "image/jpeg" -> startsWith(content, 0xFF, 0xD8, 0xFF);
            case "image/webp" -> startsWith(content, 0x52, 0x49, 0x46, 0x46)
                    && content[8] == 0x57 && content[9] == 0x45 && content[10] == 0x42 && content[11] == 0x50;
            default -> false;
        };
    }

    private boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((content[index] & 0xFF) != (prefix[index] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    public Path directory() {
        return avatarDirectory;
    }

    public String urlPrefix() {
        return avatarUrlPrefix;
    }
}
