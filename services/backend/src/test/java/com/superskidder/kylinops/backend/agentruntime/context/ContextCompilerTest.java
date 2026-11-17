package com.superskidder.kylinops.backend.agentruntime.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextCompilerTest {

    @Test
    void keepsStablePrefixDeterministicAndMovesLiveStateToDynamicSuffix() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        ContextCompiler compiler = new ContextCompiler(new ObjectMapper(), metrics);
        ContextCompiler.CompileRequest first = request("查询 nginx 状态", Map.of("nodeId", "node-1"));
        ContextCompiler.CompileRequest second = request("查询 sshd 状态", Map.of("nodeId", "node-2"));

        CompiledContext left = compiler.compile(first);
        CompiledContext right = compiler.compile(second);

        assertThat(left.stablePrefixDigest()).isEqualTo(right.stablePrefixDigest());
        assertThat(left.dynamicSuffixDigest()).isNotEqualTo(right.dynamicSuffixDigest());
        assertThat(left.localPrefixCacheHit()).isFalse();
        assertThat(right.localPrefixCacheHit()).isTrue();
        assertThat(left.stablePrefix().stream().map(PromptSegment::content).toList())
                .noneMatch(text -> text.contains("node-1") || text.contains("查询 nginx"));
        assertThat(metrics.get("kylin.agent.context.prefix.cache").tag("result", "hit").counter().count())
                .isEqualTo(1.0);
    }

    private ContextCompiler.CompileRequest request(String objective, Map<String, Object> target) {
        return new ContextCompiler.CompileRequest(objective, "PLAN", target, Map.of("readOnly", true),
                "Root task definition", List.of(Map.of("name", "kylin.service.status")), "catalog-digest");
    }
}
