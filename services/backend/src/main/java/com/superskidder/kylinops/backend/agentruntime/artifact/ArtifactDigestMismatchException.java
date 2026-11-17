package com.superskidder.kylinops.backend.agentruntime.artifact;

/** 内容摘要与预期不符（疑似篡改或传输损坏）时拒绝发布。 */
public class ArtifactDigestMismatchException extends RuntimeException {

    private final String artifactId;
    private final String expectedSm3Hex;
    private final String actualSm3Hex;

    public ArtifactDigestMismatchException(String artifactId, String expectedSm3Hex, String actualSm3Hex) {
        super("artifact sm3 mismatch: " + artifactId);
        this.artifactId = artifactId;
        this.expectedSm3Hex = expectedSm3Hex;
        this.actualSm3Hex = actualSm3Hex;
    }

    public String artifactId() {
        return artifactId;
    }

    public String expectedSm3Hex() {
        return expectedSm3Hex;
    }

    public String actualSm3Hex() {
        return actualSm3Hex;
    }
}
