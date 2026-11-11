package com.superskidder.kylinops.backend.agentruntime.vfs;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * 单一 VFS scheme 的解析器：负责该 scheme 的 URI 解析/安全校验与读访问。
 * 三个 scheme 的解析器彼此独立、不共享路径状态。
 */
public interface VfsResolver {

    String scheme();

    /** 解析并安全校验本 scheme 的 URI；scheme 不符或路径违规即拒绝。 */
    VfsUri parse(String rawUri);

    /** 读访问入口：返回资源元数据与字节流，流由调用方负责关闭。 */
    VfsResource read(VfsUri uri, VfsContext context);

    /** 调用方身份上下文：租户隔离与 AgentInstance 归属判定的依据。 */
    record VfsContext(String tenantId, String agentInstanceId) {

        public VfsContext {
            if (tenantId == null || tenantId.isBlank() || agentInstanceId == null || agentInstanceId.isBlank()) {
                throw new IllegalArgumentException("vfs context requires tenantId and agentInstanceId");
            }
        }
    }

    /** 读取结果：元数据 + 内容流。 */
    record VfsResource(VfsUri uri, Map<String, Object> metadata, InputStream content) implements Closeable {

        @Override
        public void close() throws IOException {
            if (content != null) {
                content.close();
            }
        }
    }
}
