package com.superskidder.kylinops.backend.agentruntime.transport;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.agentruntime.security.NativeGuardProbeCounts;
import com.superskidder.kylinops.backend.crypto.Sm3;
import com.superskidder.kylinops.backend.nodeexecutor.v2.AckKind;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ControlStreamResponse;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ExecutionMode;
import com.superskidder.kylinops.backend.nodeexecutor.v2.InvocationAck;
import com.superskidder.kylinops.backend.nodeexecutor.v2.InvocationProtocolStatus;
import com.superskidder.kylinops.backend.nodeexecutor.v2.InvocationResult;
import com.superskidder.kylinops.backend.nodeexecutor.v2.LockMode;
import com.superskidder.kylinops.backend.nodeexecutor.v2.NodeCapabilities;
import com.superskidder.kylinops.backend.nodeexecutor.v2.NodeHeartbeat;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ReconcileInvocations;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ReconcileResult;
import com.superskidder.kylinops.backend.nodeexecutor.v2.ResourceLock;
import com.superskidder.kylinops.backend.nodeexecutor.v2.RiskCode;
import com.superskidder.kylinops.backend.nodeexecutor.v2.SubmitInvocation;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class NodeExecutorV2Registry {

    public static final int PROTOCOL_VERSION = 2;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<NodeInvocationResult>> pending = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbcTemplate;
    private final AgentRuntimeStore store;

    public NodeExecutorV2Registry(JdbcTemplate jdbcTemplate, AgentRuntimeStore store) {
        this.jdbcTemplate = jdbcTemplate;
        this.store = store;
    }

    public void connect(String tenantId, String nodeId, StreamObserver<ControlStreamResponse> observer,
                        long lastControlSequence, long lastNodeSequence) {
        Session session = new Session(tenantId, nodeId, observer, Math.max(0, lastControlSequence),
                Math.max(0, lastNodeSequence));
        Session previous = sessions.put(key(tenantId, nodeId), session);
        if (previous != null && previous.observer() != observer) {
            try {
                previous.observer().onCompleted();
            } catch (RuntimeException ignored) {
                // The superseded stream may already be closed.
            }
        }
        saveSession(tenantId, nodeId, "", "", "{}", session.controlSequence().get(),
                session.nodeSequence().get(), true, true);
        sendReconcile(session);
    }

    public void disconnect(String tenantId, String nodeId, StreamObserver<ControlStreamResponse> observer) {
        sessions.computeIfPresent(key(tenantId, nodeId), (ignored, current) -> current.observer() == observer ? null : current);
        jdbcTemplate.update("""
                update agent_runtime_node_session set connected = false, updated_at = ?
                where tenant_id = ? and node_id = ?
                """, java.sql.Timestamp.from(Instant.now()), tenantId, nodeId);
        jdbcTemplate.update("""
                update app_managed_host set grpc_connected = false where organization_id = ? and node_id = ?
                """, tenantId, nodeId);
    }

    public boolean isConnected(String tenantId, String nodeId) {
        return sessions.containsKey(key(tenantId, nodeId));
    }

    public void heartbeat(NodeHeartbeat heartbeat, StreamObserver<ControlStreamResponse> observer, long nodeSequence) {
        Session session = requireSession(heartbeat.getTenantId(), heartbeat.getNodeId(), observer,
                heartbeat.getLastControlPlaneSequence(), heartbeat.getLastNodeSequence());
        session.nodeSequence().accumulateAndGet(nodeSequence, Math::max);
        saveSession(heartbeat.getTenantId(), heartbeat.getNodeId(), heartbeat.getExecutorVersion(),
                heartbeat.getToolCatalogDigest(), "{}", session.controlSequence().get(),
                session.nodeSequence().get(), true, false);
        // 姿态按心跳原样落库：空串写 null（节点明确表示采集不到），DispatchGate 按 UNKNOWN
        // fail-closed；停止上报时旧值随 observed_at 老化为 STALE。
        String guardStatus = heartbeat.getNativeGuardStatus() == null
                || heartbeat.getNativeGuardStatus().isBlank() ? null : heartbeat.getNativeGuardStatus().trim();
        java.sql.Timestamp observedAt = heartbeat.hasNativeGuardObservedAt()
                ? java.sql.Timestamp.from(instant(heartbeat.getNativeGuardObservedAt())) : null;
        String guardJson = heartbeat.getNativeGuardJson() == null
                || heartbeat.getNativeGuardJson().isBlank() ? null : heartbeat.getNativeGuardJson().trim();
        NativeGuardProbeCounts probeCounts = NativeGuardProbeCounts.fromJson(guardJson);
        jdbcTemplate.update("""
                update agent_runtime_node_session
                set native_guard_status = ?, native_guard_observed_at = ?,
                    native_guard_json = ?, native_guard_critical_count = ?, native_guard_warning_count = ?
                where tenant_id = ? and node_id = ?
                """, guardStatus, observedAt, guardJson, probeCounts.critical(), probeCounts.warning(),
                heartbeat.getTenantId(), heartbeat.getNodeId());
        jdbcTemplate.update("""
                update app_managed_host set grpc_connected = true, grpc_connected_at = coalesce(grpc_connected_at, ?),
                    last_heartbeat_at = ?, status = 'ONLINE' where organization_id = ? and node_id = ?
                """, java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()),
                heartbeat.getTenantId(), heartbeat.getNodeId());
    }

    public void capabilities(NodeCapabilities capabilities, StreamObserver<ControlStreamResponse> observer,
                             long nodeSequence, String capabilitiesJson) {
        Session session = requireSession(capabilities.getTenantId(), capabilities.getNodeId(), observer, 0, 0);
        session.nodeSequence().accumulateAndGet(nodeSequence, Math::max);
        saveSession(capabilities.getTenantId(), capabilities.getNodeId(), capabilities.getExecutorVersion(), "",
                capabilitiesJson, session.controlSequence().get(), session.nodeSequence().get(), true, false);
    }

    public NodeInvocationResult dispatch(NodeDispatchRequest request) {
        Session session = sessions.get(key(request.tenantId(), request.nodeId()));
        if (session == null) {
            throw new IllegalStateException("Node Executor V2 is not connected: " + request.nodeId());
        }
        CompletableFuture<NodeInvocationResult> future = new CompletableFuture<>();
        if (pending.putIfAbsent(request.executionAttemptId(), future) != null) {
            throw new IllegalStateException("Execution attempt is already pending: " + request.executionAttemptId());
        }
        long sequence = session.controlSequence().incrementAndGet();
        RiskCode riskCode = riskCode(request.riskCode());
        SubmitInvocation.Builder submitBuilder = SubmitInvocation.newBuilder()
                .setTenantId(request.tenantId())
                .setRunId(request.runId())
                .setAgentInstanceId(request.agentInstanceId())
                // Go 侧把 handoff_id 映射为审批 claim 的 agent_invocation_id
                // （controlstreamv2 client：AgentInvocationID = submit.GetHandoffId()）。
                // 缺失会让特权助手对 13 个身份字段的复判永远失配，任何审批令牌都无法通过。
                .setHandoffId(request.agentInvocationId() == null ? "" : request.agentInvocationId())
                .setToolInvocationId(request.toolInvocationId())
                .setExecutionAttemptId(request.executionAttemptId())
                .setNodeId(request.nodeId())
                .setToolId(request.toolId())
                .setToolVersion(request.toolVersion())
                .setCanonicalArgumentsJson(request.canonicalArgumentsJson())
                .setArgumentsSm3(bytes(request.argumentsDigest()))
                .setInputSchemaSm3(bytes(request.inputSchemaDigest()))
                .setOutputSchemaSm3(bytes(request.outputSchemaDigest()))
                .setGrantSm3(bytes(request.grantDigest()))
                .setToolCatalogSm3(bytes(request.catalogDigest()))
                .setRiskCode(riskCode)
                .setExecutionMode(executionMode(request.executionMode()))
                .setReadOnly(request.readOnly())
                .setLockVersion(request.lockVersion())
                .setDeadline(timestamp(Instant.now().plus(request.timeout())));
        if (request.resourceLocks() != null) {
            for (NodeDispatchRequest.LockEntry lock : request.resourceLocks()) {
                submitBuilder.addResourceLocks(ResourceLock.newBuilder()
                        .setCanonicalResource(lock.resourceKey())
                        .setMode("WRITE_EXCLUSIVE".equals(lock.lockMode())
                                ? LockMode.LOCK_MODE_WRITE_EXCLUSIVE : LockMode.LOCK_MODE_READ_SHARED)
                        .build());
            }
        }
        if (request.approvalToken() != null && !request.approvalToken().isBlank()) {
            String encoded = request.approvalToken();
            String prefix = "kylin-approval-v1.";
            if (encoded.startsWith(prefix)) {
                try {
                    byte[] tokenBytes = java.util.Base64.getUrlDecoder().decode(encoded.substring(prefix.length()));
                    com.superskidder.kylinops.backend.nodeexecutor.v2.ApprovalToken approvalTokenProto =
                            com.superskidder.kylinops.backend.nodeexecutor.v2.ApprovalToken.parseFrom(tokenBytes);
                    submitBuilder.setApprovalToken(approvalTokenProto);
                } catch (com.google.protobuf.InvalidProtocolBufferException exception) {
                    throw new IllegalArgumentException("invalid approval token encoding", exception);
                }
            }
        }
        SubmitInvocation submit = submitBuilder.build();
        ControlStreamResponse response = ControlStreamResponse.newBuilder()
                .setProtocolVersion(PROTOCOL_VERSION)
                .setSequence(sequence)
                .setSentAt(timestamp(Instant.now()))
                .setSubmit(submit)
                .build();
        try {
            store.markAttemptSent(request.executionAttemptId(), sequence);
            send(session, response);
            saveSequences(session);
            return future.get(Math.max(1, request.timeout().toMillis()), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException exception) {
            throw new IllegalStateException("Node invocation timed out", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Node invocation interrupted", exception);
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new IllegalStateException("Node invocation failed", exception.getCause());
        } finally {
            pending.remove(request.executionAttemptId(), future);
        }
    }

    public void acknowledge(InvocationAck ack) {
        if (ack.getKind() == AckKind.ACK_KIND_RECEIVED || ack.getKind() == AckKind.ACK_KIND_DUPLICATE) {
            store.markAttemptAcknowledged(ack.getExecutionAttemptId(), instant(ack.getPersistedAt()));
        }
    }

    public void result(InvocationResult result) {
        boolean success = result.getStatus() == InvocationProtocolStatus.INVOCATION_PROTOCOL_STATUS_SUCCEEDED;
        String json = result.getStructuredResultJson();
        if (success && (result.getResultSm3().isEmpty()
                || !result.getResultSm3().equals(ByteString.copyFrom(HexFormat.of().parseHex(Sm3.hex(json)))))) {
            success = false;
            json = "";
            result = result.toBuilder().setErrorCode("RESULT_DIGEST_MISMATCH")
                    .setMessage("Node result SM3 digest does not match payload").setRetryable(false).build();
        }
        store.recordNodeResult(result.getExecutionAttemptId(), success, json, result.getErrorCode(),
                result.getMessage(), result.getRetryable());
        CompletableFuture<NodeInvocationResult> future = pending.get(result.getExecutionAttemptId());
        if (future != null) {
            future.complete(new NodeInvocationResult(success, json, result.getErrorCode(), result.getMessage(),
                    result.getRetryable()));
        }
    }

    public void reconcile(ReconcileResult reconcileResult) {
        reconcileResult.getInvocationsList().forEach(item -> {
            if (item.hasTerminalResult()) {
                result(item.getTerminalResult());
            }
        });
    }

    private Session requireSession(String tenantId, String nodeId, StreamObserver<ControlStreamResponse> observer,
                                   long lastControlSequence, long lastNodeSequence) {
        Session session = sessions.get(key(tenantId, nodeId));
        if (session == null || session.observer() != observer) {
            connect(tenantId, nodeId, observer, lastControlSequence, lastNodeSequence);
            session = sessions.get(key(tenantId, nodeId));
        }
        return session;
    }

    private void sendReconcile(Session session) {
        List<String> invocationIds = jdbcTemplate.query("""
                select id from tool_invocation
                where tenant_id = ? and target_node_id = ? and status in ('QUEUED', 'RUNNING', 'UNKNOWN')
                order by queued_at asc
                """, (rs, rowNum) -> rs.getString(1), session.tenantId(), session.nodeId());
        long sequence = session.controlSequence().incrementAndGet();
        ReconcileInvocations reconcile = ReconcileInvocations.newBuilder()
                .addAllToolInvocationIds(invocationIds)
                .setResumeAfterNodeSequence(session.nodeSequence().get())
                .build();
        send(session, ControlStreamResponse.newBuilder().setProtocolVersion(PROTOCOL_VERSION).setSequence(sequence)
                .setSentAt(timestamp(Instant.now())).setReconcile(reconcile).build());
        saveSequences(session);
    }

    private void send(Session session, ControlStreamResponse response) {
        try {
            synchronized (session.observer()) {
                session.observer().onNext(response);
            }
        } catch (RuntimeException exception) {
            disconnect(session.tenantId(), session.nodeId(), session.observer());
            throw exception;
        }
    }

    private void saveSequences(Session session) {
        jdbcTemplate.update("""
                update agent_runtime_node_session set last_control_sequence = ?, last_node_sequence = ?, updated_at = ?
                where tenant_id = ? and node_id = ?
                """, session.controlSequence().get(), session.nodeSequence().get(), java.sql.Timestamp.from(Instant.now()),
                session.tenantId(), session.nodeId());
    }

    private void saveSession(String tenantId, String nodeId, String executorVersion, String catalogDigest,
                             String capabilitiesJson, long controlSequence, long nodeSequence,
                             boolean connected, boolean connectedNow) {
        Instant now = Instant.now();
        int updated = jdbcTemplate.update("""
                update agent_runtime_node_session set protocol_version = 2, executor_version = coalesce(nullif(?, ''), executor_version),
                    tool_catalog_digest = coalesce(nullif(?, ''), tool_catalog_digest), capabilities_json = ?,
                    last_control_sequence = ?, last_node_sequence = ?, connected = ?,
                    connected_at = case when ? then ? else connected_at end,
                    last_heartbeat_at = ?, updated_at = ? where tenant_id = ? and node_id = ?
                """, executorVersion, catalogDigest, capabilitiesJson, controlSequence, nodeSequence, connected,
                connectedNow, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                tenantId, nodeId);
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into agent_runtime_node_session (
                        tenant_id, node_id, protocol_version, executor_version, tool_catalog_digest,
                        capabilities_json, last_control_sequence, last_node_sequence, connected,
                        connected_at, last_heartbeat_at, updated_at
                    ) values (?, ?, 2, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, tenantId, nodeId, executorVersion, catalogDigest, capabilitiesJson, controlSequence,
                    nodeSequence, connected, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now),
                    java.sql.Timestamp.from(now));
        }
    }

    private ByteString bytes(String hex) {
        return ByteString.copyFrom(HexFormat.of().parseHex(hex));
    }

    private RiskCode riskCode(String value) {
        return switch (value == null ? "" : value.trim().toUpperCase()) {
            case "R0" -> RiskCode.RISK_CODE_R0;
            case "R1" -> RiskCode.RISK_CODE_R1;
            case "R2" -> RiskCode.RISK_CODE_R2;
            case "R3" -> RiskCode.RISK_CODE_R3;
            case "R4" -> RiskCode.RISK_CODE_R4;
            case "R5" -> RiskCode.RISK_CODE_R5;
            default -> RiskCode.RISK_CODE_R0;
        };
    }

    private ExecutionMode executionMode(String value) {
        return switch (value == null ? "" : value.trim().toUpperCase()) {
            case "AUTO" -> ExecutionMode.EXECUTION_MODE_AUTO;
            case "CAUTIOUS" -> ExecutionMode.EXECUTION_MODE_CAUTIOUS;
            default -> ExecutionMode.EXECUTION_MODE_PLAN;
        };
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
    }

    private String key(String tenantId, String nodeId) {
        return tenantId.trim() + ":" + nodeId.trim();
    }

    private record Session(
            String tenantId,
            String nodeId,
            StreamObserver<ControlStreamResponse> observer,
            AtomicLong controlSequence,
            AtomicLong nodeSequence
    ) {
        Session(String tenantId, String nodeId, StreamObserver<ControlStreamResponse> observer,
                long controlSequence, long nodeSequence) {
            this(tenantId, nodeId, observer, new AtomicLong(controlSequence), new AtomicLong(nodeSequence));
        }
    }
}
