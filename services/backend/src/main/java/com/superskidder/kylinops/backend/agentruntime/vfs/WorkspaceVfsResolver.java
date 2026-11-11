package com.superskidder.kylinops.backend.agentruntime.vfs;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code workspace://{agent_instance_id}/path}：只允许访问调用上下文自身 AgentInstance 的
 * workspace，跨 Agent 读取一律拒绝。底层 runner 尚未连通，读操作委托 {@link WorkspaceFileReader}，
 * 默认存根明确抛 UnsupportedOperationException；URI 解析与安全校验始终完整执行。
 */
@Component
public class WorkspaceVfsResolver implements VfsResolver {

    private final WorkspaceFileReader reader;

    @Autowired
    public WorkspaceVfsResolver() {
        this(new StubWorkspaceFileReader());
    }

    public WorkspaceVfsResolver(WorkspaceFileReader reader) {
        this.reader = reader;
    }

    @Override
    public String scheme() {
        return VfsUri.SCHEME_WORKSPACE;
    }

    @Override
    public VfsUri parse(String rawUri) {
        VfsUri uri = VfsUri.parse(rawUri);
        if (!scheme().equals(uri.scheme())) {
            throw new IllegalArgumentException("not a workspace vfs uri");
        }
        if (uri.path().isEmpty()) {
            throw new IllegalArgumentException("workspace vfs uri must include a relative path");
        }
        return uri;
    }

    @Override
    public VfsResource read(VfsUri uri, VfsContext context) {
        // resourceId 必须等于调用方自身的 agentInstanceId，跨 AgentInstance 访问拒绝。
        if (!uri.resourceId().equals(context.agentInstanceId())) {
            throw new IllegalArgumentException("workspace access denied: agent instance mismatch");
        }
        try {
            InputStream content = reader.read(uri.resourceId(), uri.path());
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("agentInstanceId", uri.resourceId());
            metadata.put("path", uri.path());
            return new VfsResource(uri, metadata, content);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read workspace file", exception);
        }
    }

    /** workspace 文件读取接口：由 workspace runner 连通后提供实现。 */
    public interface WorkspaceFileReader {

        InputStream read(String agentInstanceId, String relativePath) throws IOException;
    }

    /** runner 未连通前的存根：安全校验已完成，读取统一报不支持。 */
    public static final class StubWorkspaceFileReader implements WorkspaceFileReader {

        @Override
        public InputStream read(String agentInstanceId, String relativePath) {
            throw new UnsupportedOperationException("workspace runner is not connected");
        }
    }
}
