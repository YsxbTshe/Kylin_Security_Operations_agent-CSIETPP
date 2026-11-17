package com.superskidder.kylinops.backend.iam;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarStorageServiceTest {

    @Test
    void shouldStoreAvatarWithGeneratedSafeUrl() {
        AvatarStorageService storageService = new AvatarStorageService(
                "target/test-avatar-service",
                "/uploads/avatars"
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "../avatar.png",
                "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0}
        );

        String avatarUrl = storageService.store(file);

        assertThat(avatarUrl).startsWith("/uploads/avatars/");
        assertThat(avatarUrl).endsWith(".png");
        assertThat(avatarUrl).doesNotContain("..");
    }

    @Test
    void shouldRejectUnsupportedAvatarType() {
        AvatarStorageService storageService = new AvatarStorageService(
                "target/test-avatar-service",
                "/uploads/avatars"
        );
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.svg",
                "image/svg+xml",
                new byte[]{1, 2, 3}
        );

        assertThatThrownBy(() -> storageService.store(file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("头像仅支持 PNG、JPG、WEBP");
    }
}
