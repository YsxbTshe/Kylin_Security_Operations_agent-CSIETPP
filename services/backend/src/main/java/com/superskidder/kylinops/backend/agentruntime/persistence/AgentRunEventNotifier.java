package com.superskidder.kylinops.backend.agentruntime.persistence;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
public class AgentRunEventNotifier {

    private final Map<String, CopyOnWriteArrayList<Consumer<AgentRunEvent>>> listeners = new ConcurrentHashMap<>();

    public AutoCloseable subscribe(String runId, Consumer<AgentRunEvent> listener) {
        listeners.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> {
            CopyOnWriteArrayList<Consumer<AgentRunEvent>> current = listeners.get(runId);
            if (current != null) {
                current.remove(listener);
                if (current.isEmpty()) {
                    listeners.remove(runId, current);
                }
            }
        };
    }

    public void publish(AgentRunEvent event) {
        for (Consumer<AgentRunEvent> listener : listeners.getOrDefault(event.runId(), new CopyOnWriteArrayList<>())) {
            listener.accept(event);
        }
    }
}
