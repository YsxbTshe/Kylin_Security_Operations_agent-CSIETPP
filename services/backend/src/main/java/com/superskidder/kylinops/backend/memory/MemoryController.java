package com.superskidder.kylinops.backend.memory;

import com.superskidder.kylinops.backend.common.ApiResponse;
import com.superskidder.kylinops.backend.security.AccessControlService;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/memory")
public class MemoryController {

    private final GraphMemoryService memoryService;
    private final AccessControlService accessControlService;

    public MemoryController(GraphMemoryService memoryService, AccessControlService accessControlService) {
        this.memoryService = memoryService;
        this.accessControlService = accessControlService;
    }

    @PostMapping("/remember")
    public ApiResponse<String> remember(@RequestBody RememberRequest request) {
        accessControlService.requirePermission("platform:configure");
        String id = memoryService.remember(accessControlService.currentOrganizationId(),
                request.memoryType() == null ? "FACT" : request.memoryType(),
                request.title(), request.content(),
                request.importance() == null ? 3 : request.importance(),
                "MANUAL", null);
        return ApiResponse.success(id);
    }

    @PostMapping("/recall")
    public ApiResponse<List<MemoryHit>> recall(@RequestBody RecallRequest request) {
        accessControlService.requirePermission("assistant:use");
        MemoryRetrievalConfig config = switch (request.mode() == null ? "full" : request.mode()) {
            case "semantic" -> MemoryRetrievalConfig.semanticOnly();
            case "generative-agents" -> MemoryRetrievalConfig.generativeAgents();
            default -> MemoryRetrievalConfig.full();
        };
        List<MemoryHit> hits = memoryService.recall(accessControlService.currentOrganizationId(),
                request.query(), request.k() == null ? 8 : request.k(), config);
        return ApiResponse.success(hits);
    }

    public record RememberRequest(String memoryType, @NotBlank String title, @NotBlank String content,
                                  Integer importance) {
    }

    public record RecallRequest(@NotBlank String query, Integer k, String mode) {
    }
}
