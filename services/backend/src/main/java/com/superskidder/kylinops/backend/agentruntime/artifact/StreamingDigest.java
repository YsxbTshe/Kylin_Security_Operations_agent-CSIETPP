package com.superskidder.kylinops.backend.agentruntime.artifact;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HexFormat;
import org.bouncycastle.crypto.digests.SM3Digest;

/**
 * 边写边算的 SM3 摘要流：大 artifact 落盘时无需整体读入内存即可得到内容摘要。
 * {@link #finish()}（或 {@link #close()}）之后摘要定型，可通过 {@link #hex()} 读取。
 */
public final class StreamingDigest extends OutputStream {

    private final SM3Digest digest = new SM3Digest();
    private long byteCount;
    private String hex;

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        digest.update((byte) b);
        byteCount++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        if (len > 0) {
            digest.update(b, off, len);
            byteCount += len;
        }
    }

    public long byteCount() {
        return byteCount;
    }

    /** 定型摘要并返回十六进制；重复调用返回同一结果。 */
    public String finish() {
        if (hex == null) {
            byte[] output = new byte[digest.getDigestSize()];
            digest.doFinal(output, 0);
            hex = HexFormat.of().formatHex(output);
        }
        return hex;
    }

    /** 仅在 {@link #finish()} 之后可用。 */
    public String hex() {
        if (hex == null) {
            throw new IllegalStateException("digest not finished yet");
        }
        return hex;
    }

    @Override
    public void close() {
        finish();
    }

    private void ensureOpen() throws IOException {
        if (hex != null) {
            throw new IOException("digest stream already finished");
        }
    }
}
