package com.superskidder.kylinops.backend.grpc;

/**
 * Enrollment message payloads shared by the node executor V2 gRPC transport
 * and the host agent certificate service.
 */
public final class EnrollMessages {
    private EnrollMessages() {
    }

    public record EnrollNodeRequest(String bootstrapToken, String organizationId, String nodeId, String hostname, String machineId, String agentVersion, String csrPem) {
    }

    public record EnrollNodeResponse(boolean accepted, String message, String nodeCertPem, String caCertPem, long expiresUnixTime, String serialNumber) {
    }
}
