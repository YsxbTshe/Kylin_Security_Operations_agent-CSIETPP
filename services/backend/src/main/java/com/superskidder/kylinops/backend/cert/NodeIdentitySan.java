package com.superskidder.kylinops.backend.cert;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 节点机器身份在证书里的权威载体：subjectAltName 的 URI 项。
 *
 * <p>为什么不继续用 Subject DN 的 CN/O：CN 是给人读的显示名，不是机器身份字段，RFC 6125 §6.4.4
 * 已经明确把它从主机名校验里废弃（"CN-ID" 仅作为无 SAN 时的遗留兼容）。只要 CA 曾经签发过任意
 * 一张 CN 相同的证书（比如给同名的测试节点、或人工签的运维证书），持有者就能冒充该节点。
 * SAN 是 X.509 里唯一为"这张证书代表哪个实体"设计的扩展，签发侧一次写死、校验侧只认它。
 *
 * <p>为什么选 URI 而不是 otherName / DNS：
 * <ul>
 *   <li><b>otherName</b> 语义最贴切，但需要自定义 OID，且 Java 的
 *       {@link X509Certificate#getSubjectAlternativeNames()} 对 otherName 只回吐原始 DER 字节
 *       （type 0 + byte[]），校验侧要自己拆 ASN.1，多一段容易写错的解析代码，收益却只是"更正统"。</li>
 *   <li><b>DNS</b> 受 DNS label 字符集限制（仅 LDH），organization_id / node_id 里的下划线、中文、
 *       大小写都会被迫做有损转换，转换本身就是身份混淆的来源。</li>
 *   <li><b>URI</b> 在 Java（type 6，直接给 String）与 Go（{@code x509.Certificate.URIs}）两侧都是
 *       一等公民，无需手写 ASN.1；SPIFFE 的 {@code spiffe://} 也正是走这条路，是当前工业界
 *       表达机器身份的主流形态。</li>
 * </ul>
 *
 * <p>形态：{@code kylin://node/<organization_id>/<node_id>}，两段路径各自百分号编码，
 * 因此 id 里出现 {@code /}、空格、非 ASCII 都不会破坏边界，也不会出现两个不同 (org,node)
 * 编码成同一个 URI 的歧义。
 */
public final class NodeIdentitySan {

    /** URI SAN 的固定前缀。authority 用常量 {@code node} 而不是把 org 放上去——authority 的
     *  字符集比 path 更严，把租户 id 塞进去会在 id 含下划线/非 ASCII 时构造出非法 URI。 */
    public static final String URI_PREFIX = "kylin://node/";

    private NodeIdentitySan() {
    }

    /** 签发侧：把节点身份编码成 URI SAN 值。 */
    public static String uri(String organizationId, String nodeId) {
        String organization = organizationId == null ? "" : organizationId.trim();
        String node = nodeId == null ? "" : nodeId.trim();
        if (organization.isEmpty() || node.isEmpty()) {
            throw new IllegalArgumentException("node identity SAN requires non-blank organizationId and nodeId");
        }
        return URI_PREFIX + encodeSegment(organization) + "/" + encodeSegment(node);
    }

    /** 校验侧：解析单个 URI SAN 值；不是本平台的 URI 则返回空。 */
    public static Optional<NodeIdentity> parse(String uriValue) {
        if (uriValue == null) {
            return Optional.empty();
        }
        String value = uriValue.trim();
        // scheme 与 authority 按 RFC 3986 §3.1/§3.2.2 大小写不敏感，path 大小写敏感。
        if (value.length() <= URI_PREFIX.length()
                || !value.substring(0, URI_PREFIX.length()).toLowerCase(Locale.ROOT).equals(URI_PREFIX)) {
            return Optional.empty();
        }
        String[] segments = value.substring(URI_PREFIX.length()).split("/", -1);
        if (segments.length != 2) {
            return Optional.empty();
        }
        String organization = decodeSegment(segments[0]);
        String node = decodeSegment(segments[1]);
        if (organization == null || node == null || organization.isBlank() || node.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new NodeIdentity(organization, node));
    }

    /**
     * 从证书里取出全部本平台 URI SAN 身份（去重）。
     *
     * <p>返回 list 而不是 Optional 是刻意的：一张证书带两个互不相同的节点身份是签发事故或攻击，
     * 调用方必须能看见"多于一个"这件事并拒绝，而不是被静默取首项。
     */
    public static List<NodeIdentity> parseAll(X509Certificate certificate) {
        if (certificate == null) {
            return List.of();
        }
        Collection<List<?>> names;
        try {
            names = certificate.getSubjectAlternativeNames();
        } catch (CertificateParsingException exception) {
            // SAN 扩展存在但畸形：当作"读不到 SAN"，交给调用方的回退与告警路径处理。
            return List.of();
        }
        if (names == null) {
            return List.of();
        }
        Set<NodeIdentity> identities = new LinkedHashSet<>();
        for (List<?> entry : names) {
            if (entry == null || entry.size() < 2) {
                continue;
            }
            // GeneralName.uniformResourceIdentifier == 6
            if (!(entry.get(0) instanceof Integer type) || type != 6) {
                continue;
            }
            if (!(entry.get(1) instanceof String value)) {
                continue;
            }
            parse(value).ifPresent(identities::add);
        }
        return List.copyOf(identities);
    }

    private static String encodeSegment(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 8);
        for (byte raw : value.getBytes(StandardCharsets.UTF_8)) {
            int octet = raw & 0xFF;
            if (unreserved(octet)) {
                builder.append((char) octet);
            } else {
                builder.append('%').append(String.format("%02X", octet));
            }
        }
        return builder.toString();
    }

    /** 与 {@link #encodeSegment} 严格配对的解码；非法百分号序列返回 null（判为无效 SAN）。 */
    private static String decodeSegment(String value) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '%') {
                if (character > 0x7F) {
                    return null;
                }
                buffer.write(character);
                continue;
            }
            if (index + 2 >= value.length()) {
                return null;
            }
            int high = Character.digit(value.charAt(index + 1), 16);
            int low = Character.digit(value.charAt(index + 2), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            buffer.write((high << 4) | low);
            index += 2;
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private static boolean unreserved(int octet) {
        return (octet >= 'A' && octet <= 'Z')
                || (octet >= 'a' && octet <= 'z')
                || (octet >= '0' && octet <= '9')
                || octet == '-' || octet == '.' || octet == '_' || octet == '~';
    }

    public record NodeIdentity(String organizationId, String nodeId) {
    }

    /** 便于调用方直接拿到一个可读的诊断串（日志用，不参与判定）。 */
    public static String describe(List<NodeIdentity> identities) {
        List<String> rendered = new ArrayList<>(identities.size());
        for (NodeIdentity identity : identities) {
            rendered.add(uri(identity.organizationId(), identity.nodeId()));
        }
        return String.join(", ", rendered);
    }
}
