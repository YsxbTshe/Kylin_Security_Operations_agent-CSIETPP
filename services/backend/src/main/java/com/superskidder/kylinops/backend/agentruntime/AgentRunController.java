package com.superskidder.kylinops.backend.agentruntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superskidder.kylinops.backend.agentruntime.agent.SubAgentLifecycleService;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEvent;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRunEventNotifier;
import com.superskidder.kylinops.backend.agentruntime.persistence.AgentRuntimeStore;
import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.common.AppException;
import com.superskidder.kylinops.backend.security.AccessControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v2/agent-runs")
public class AgentRunController {

    private final AgentRuntimeStore store;
    private final AgentRunEventNotifier notifier;
    private final AccessControlService accessControl;
    private final SubAgentLifecycleService subAgentLifecycle;
    private final ObjectMapper objectMapper;

    public AgentRunController(AgentRuntimeStore store, AgentRunEventNotifier notifier,
                              AccessControlService accessControl, SubAgentLifecycleService subAgentLifecycle,
                              ObjectMapper objectMapper) {
        this.store = store;
        this.notifier = notifier;
        this.accessControl = accessControl;
        this.subAgentLifecycle = subAgentLifecycle;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ApiResponse<AgentRuntimeStore.CreatedRun> create(@Valid @RequestBody CreateAgentRunRequest request) {
        accessControl.requirePermission("assistant:use");
        String tenantId = accessControl.currentOrganizationId();
        String userId = accessControl.currentUser().userId();
        String mode = request.executionMode() != null ? request.executionMode().toUpperCase() : "PLAN";
        if ("PLAN".equals(mode) && request.targetScope() == null) {
            return ApiResponse.success(store.createReadOnlyRun(
                    tenantId, userId, request.objective(),
                    request.nodeId(), request.service(), request.modelProfileId(), request.reasoningLevel()));
        }
        // 目标服务可空（由意图推断，不再强制）；Map.of 不接受 null 值，故空串兜底。
        Map<String, Object> targetScope = request.targetScope() != null ? request.targetScope()
                : Map.of("nodeId", request.nodeId() == null ? "" : request.nodeId(),
                        "service", request.service() == null ? "" : request.service());
        Map<String, Object> grant = request.grant() != null ? request.grant()
                : Map.of("readOnly", "PLAN".equals(mode));
        return ApiResponse.success(store.createWriteRun(
                tenantId, userId, request.objective(), mode,
                targetScope, grant, request.modelProfileId(), request.reasoningLevel()));
    }

    @GetMapping("/{runId}")
    public ApiResponse<AgentRuntimeStore.RunView> get(@PathVariable String runId) {
        accessControl.requirePermission("agent:read");
        return ApiResponse.success(requireRun(runId));
    }

    /** 取消 run；级联取消其未完成子 run 与 OPEN handoff，并归还子预算。 */
    @PostMapping("/{runId}/cancel")
    public ApiResponse<SubAgentLifecycleService.CancelReport> cancel(@PathVariable String runId) {
        accessControl.requirePermission("assistant:use");
        requireRun(runId);
        String userId = accessControl.currentUser().userId();
        return ApiResponse.success(subAgentLifecycle.cancelRun(runId, "HUMAN", userId, "cancelled by user"));
    }

    @GetMapping(path = "/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(
            @PathVariable String runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId
    ) {
        accessControl.requirePermission("agent:read");
        AgentRuntimeStore.RunView view = requireRun(runId);
        String tenantId = accessControl.currentOrganizationId();
        long after = parseLastEventId(lastEventId);
        SseEmitter emitter = new SseEmitter(0L);
        Object lock = new Object();
        AtomicBoolean replaying = new AtomicBoolean(true);
        AtomicBoolean terminalObserved = new AtomicBoolean(isTerminal(view.status()));
        AtomicLong sent = new AtomicLong(after);
        List<AgentRunEvent> buffered = new ArrayList<>();
        AtomicReference<AutoCloseable> subscription = new AtomicReference<>();

        subscription.set(notifier.subscribe(runId, event -> {
            try {
                synchronized (lock) {
                    terminalObserved.compareAndSet(false, isTerminalEvent(event));
                    if (replaying.get()) {
                        buffered.add(event);
                    } else {
                        sendIfNew(emitter, sent, event);
                        if (isTerminalEvent(event)) {
                            close(subscription.get());
                            emitter.complete();
                        }
                    }
                }
            } catch (RuntimeException exception) {
                close(subscription.get());
                emitter.completeWithError(exception);
            }
        }));
        try {
            long cursor = after;
            while (true) {
                List<AgentRunEvent> page = store.eventsAfter(tenantId, runId, cursor, 1_000);
                for (AgentRunEvent event : page) {
                    sendIfNew(emitter, sent, event);
                    terminalObserved.compareAndSet(false, isTerminalEvent(event));
                    cursor = event.sequence();
                }
                if (page.size() < 1_000) {
                    break;
                }
            }
            synchronized (lock) {
                buffered.stream().sorted(Comparator.comparingLong(AgentRunEvent::sequence))
                        .forEach(event -> sendIfNew(emitter, sent, event));
                buffered.clear();
                replaying.set(false);
            }
            if (terminalObserved.get()) {
                close(subscription.get());
                emitter.complete();
            }
        } catch (RuntimeException exception) {
            close(subscription.get());
            emitter.completeWithError(exception);
            return emitter;
        }
        emitter.onCompletion(() -> close(subscription.get()));
        emitter.onTimeout(() -> close(subscription.get()));
        emitter.onError(ignored -> close(subscription.get()));
        return emitter;
    }

    private void sendIfNew(SseEmitter emitter, AtomicLong sent, AgentRunEvent event) {
        if (event.sequence() <= sent.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.sequence()))
                    .name(event.eventType())
                    .data(Map.of(
                            "eventId", event.eventId(),
                            "runId", event.runId(),
                            "sequence", event.sequence(),
                            "eventType", event.eventType(),
                            "actorType", event.actorType(),
                            "actorId", event.actorId() == null ? "" : event.actorId(),
                            "payload", objectMapper.readValue(event.payloadJson(), Object.class),
                            "eventHash", event.eventHash(),
                            "createdAt", event.createdAt().toString()
                    )));
            sent.set(event.sequence());
        } catch (IOException exception) {
            throw new IllegalStateException("SSE client disconnected", exception);
        }
    }

    private AgentRuntimeStore.RunView requireRun(String runId) {
        return store.findRun(accessControl.currentOrganizationId(), runId)
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "AgentRun 不存在"));
    }

    private long parseLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException exception) {
            throw new AppException(HttpStatus.BAD_REQUEST, "Last-Event-ID 必须是事件 sequence");
        }
    }

    private boolean isTerminal(String status) {
        return List.of("COMPLETED", "FAILED", "CANCELLED").contains(status);
    }

    private boolean isTerminalEvent(AgentRunEvent event) {
        return List.of("RUN_COMPLETED", "RUN_FAILED", "RUN_CANCELLED").contains(event.eventType());
    }

    private void close(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Subscription cleanup is best-effort.
        }
    }

    public record CreateAgentRunRequest(
            @NotBlank @Size(max = 4000) String objective,
            @Size(max = 128) String nodeId,
            @Pattern(regexp = "[A-Za-z0-9@._-]{1,128}") String service,
            @Pattern(regexp = "(?i)PLAN|CAUTIOUS|AUTO") String executionMode,
            Map<String, Object> targetScope,
            Map<String, Object> grant,
            @Size(max = 64) String modelProfileId,
            @Pattern(regexp = "(?i)OFF|LOW|MEDIUM|HIGH") String reasoningLevel
    ) {}
}
