package com.superskidder.kylinops.backend.agentruntime.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.superskidder.kylinops.backend.crypto.Sm3;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.api.Test;

class StreamingDigestTest {

    @Test
    void incrementalSingleByteWritesMatchOneShotDigest() throws IOException {
        byte[] content = randomContent(4096);
        StreamingDigest digest = new StreamingDigest();
        for (byte value : content) {
            digest.write(value);
        }

        assertThat(digest.byteCount()).isEqualTo(content.length);
        assertThat(digest.finish()).isEqualTo(Sm3.hex(content));
        assertThat(digest.hex()).isEqualTo(Sm3.hex(content));
    }

    @Test
    void chunkedWritesWithOffsetsMatchOneShotDigest() throws IOException {
        byte[] content = randomContent(10_000);
        StreamingDigest digest = new StreamingDigest();
        digest.write(content, 0, 1);
        digest.write(content, 1, 4_000);
        digest.write(content, 4_001, content.length - 4_001);

        assertThat(digest.finish()).isEqualTo(Sm3.hex(content));
        // finish 幂等，重复调用结果不变。
        assertThat(digest.finish()).isEqualTo(Sm3.hex(content));
    }

    @Test
    void closeFinalizesDigest() throws IOException {
        byte[] content = "kylin-security-ops-agent".getBytes(StandardCharsets.UTF_8);
        StreamingDigest digest = new StreamingDigest();
        digest.write(content);
        digest.close();

        assertThat(digest.hex()).isEqualTo(Sm3.hex(content));
    }

    @Test
    void writeAfterFinishIsRejected() throws IOException {
        StreamingDigest digest = new StreamingDigest();
        digest.write("abc".getBytes(StandardCharsets.UTF_8));
        digest.finish();

        assertThatThrownBy(() -> digest.write('d')).isInstanceOf(IOException.class);
    }

    @Test
    void hexBeforeFinishIsRejected() {
        StreamingDigest digest = new StreamingDigest();
        assertThatThrownBy(digest::hex).isInstanceOf(IllegalStateException.class);
    }

    private byte[] randomContent(int length) {
        byte[] content = new byte[length];
        new Random(length).nextBytes(content);
        return content;
    }
}
