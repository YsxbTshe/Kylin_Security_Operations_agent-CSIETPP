package com.superskidder.kylinops.backend.cert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class NodeIdentitySanTest {

    @Test
    void shouldRoundTripPlainIdentifiers() {
        String uri = NodeIdentitySan.uri("org-alpha", "node-1");

        assertThat(uri).isEqualTo("kylin://node/org-alpha/node-1");
        assertThat(NodeIdentitySan.parse(uri)).contains(new NodeIdentitySan.NodeIdentity("org-alpha", "node-1"));
    }

    // 编码的意义：id 里出现分隔符也不能让两个不同的 (org,node) 撞成同一个 URI，
    // 否则 SAN 反而成了新的身份混淆面。
    @Test
    void shouldRoundTripIdentifiersContainingSeparatorsAndNonAscii() {
        String tricky = NodeIdentitySan.uri("org/one", "node 1/../node-2");
        assertThat(NodeIdentitySan.parse(tricky))
                .contains(new NodeIdentitySan.NodeIdentity("org/one", "node 1/../node-2"));

        String chinese = NodeIdentitySan.uri("租户-甲", "节点_1");
        assertThat(chinese).doesNotContain("租");
        assertThat(NodeIdentitySan.parse(chinese)).contains(new NodeIdentitySan.NodeIdentity("租户-甲", "节点_1"));

        assertThat(NodeIdentitySan.uri("a/b", "c")).isNotEqualTo(NodeIdentitySan.uri("a", "b/c"));
    }

    @Test
    void shouldRejectForeignOrMalformedUris() {
        assertThat(NodeIdentitySan.parse(null)).isEmpty();
        assertThat(NodeIdentitySan.parse("")).isEmpty();
        assertThat(NodeIdentitySan.parse("https://example.com/org/node")).isEmpty();
        assertThat(NodeIdentitySan.parse("kylin://node/only-one-segment")).isEmpty();
        assertThat(NodeIdentitySan.parse("kylin://node/org/node/extra")).isEmpty();
        assertThat(NodeIdentitySan.parse("kylin://node//node-1")).isEmpty();
        assertThat(NodeIdentitySan.parse("kylin://node/org-1/%zz")).isEmpty();
    }

    // scheme / authority 按 RFC 3986 大小写不敏感，path 保持大小写敏感。
    @Test
    void shouldTreatSchemeAndAuthorityCaseInsensitively() {
        assertThat(NodeIdentitySan.parse("KYLIN://NODE/org-1/node-1"))
                .contains(new NodeIdentitySan.NodeIdentity("org-1", "node-1"));
        assertThat(NodeIdentitySan.parse("kylin://node/Org-1/Node-1"))
                .contains(new NodeIdentitySan.NodeIdentity("Org-1", "Node-1"));
    }

    @Test
    void shouldRefuseToBuildUriForBlankIdentity() {
        assertThatThrownBy(() -> NodeIdentitySan.uri("", "node-1")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodeIdentitySan.uri("org-1", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NodeIdentitySan.uri(null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
