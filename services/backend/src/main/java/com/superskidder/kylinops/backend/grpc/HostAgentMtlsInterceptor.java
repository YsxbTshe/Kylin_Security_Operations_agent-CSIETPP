package com.superskidder.kylinops.backend.grpc;

import com.superskidder.kylinops.backend.cert.NodeIdentitySan;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import javax.naming.ldap.LdapName;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class HostAgentMtlsInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(HostAgentMtlsInterceptor.class);

    private static final Context.Key<HostAgentMtlsIdentity> IDENTITY = Context.key("host-agent-mtls-identity");

    private final JdbcTemplate jdbcTemplate;
    private final Counter cnFallbackCounter;
    private final Counter sanConflictCounter;

    public HostAgentMtlsInterceptor(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.cnFallbackCounter = Counter.builder("kylin.backend.mtls.identity.cn_fallback")
                .description("Peer certificates presented without a kylin URI SAN (legacy Subject DN CN/O path); "
                        + "reaching zero over a full certificate lifetime means the fallback can be removed")
                .register(meterRegistry);
        this.sanConflictCounter = Counter.builder("kylin.backend.mtls.identity.san_rejected")
                .description("Peer certificates rejected because they carry more than one kylin URI SAN identity")
                .register(meterRegistry);
    }

    public static HostAgentMtlsIdentity currentIdentity() {
        return IDENTITY.get();
    }

    /** 存量证书走 CN 回退的累计次数；运维据此判断"何时可以关掉回退"。 */
    public double cnFallbackCount() {
        return cnFallbackCounter.count();
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call,
            Metadata headers,
            ServerCallHandler<ReqT, RespT> next
    ) {
        HostAgentMtlsIdentity identity = extractIdentity(call);
        if (identity == null) {
            call.close(Status.UNAUTHENTICATED.withDescription("mTLS peer certificate is required"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        String method = call.getMethodDescriptor().getFullMethodName();
        if (!peerCertificateAllowed(method, identity)) {
            log.warn("closing ControlStream: mTLS peer certificate is revoked, expired, or not bound to this node; "
                            + "org={} node={} serial={} method={}",
                    identity.organizationId(), identity.nodeId(), identity.serialNumber(), method);
            call.close(Status.PERMISSION_DENIED.withDescription("mTLS peer certificate is revoked, expired, or not bound to this node"), new Metadata());
            return new ServerCall.Listener<>() {};
        }
        return Contexts.interceptCall(Context.current().withValue(IDENTITY, identity), call, headers, next);
    }

    private HostAgentMtlsIdentity extractIdentity(ServerCall<?, ?> call) {
        SSLSession session = call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
        if (session == null) {
            return null;
        }
        try {
            Certificate[] certificates = session.getPeerCertificates();
            if (certificates.length == 0 || !(certificates[0] instanceof X509Certificate certificate)) {
                return null;
            }
            return identityFromCertificate(certificate);
        } catch (SSLPeerUnverifiedException exception) {
            return null;
        }
    }

    /**
     * 从 peer 证书解析节点身份。**SAN 优先，CN/O 仅作为存量证书的显式回退。**
     *
     * <p>判定顺序与理由：
     * <ol>
     *   <li>证书带 <b>恰好一个</b> {@code kylin://node/...} URI SAN → 以 SAN 为准，
     *       DN 里的 CN/O 一律忽略。所以拿到一张 CN 被伪造成别的节点、但 SAN 指向自己的证书，
     *       也只能以 SAN 所写的身份接入，冒充不成立。</li>
     *   <li>证书带 <b>多个互不相同</b>的平台 URI SAN → 直接拒绝。多身份不是"取第一个"能安全处理的
     *       情形，只能是签发事故或构造出来的证书。</li>
     *   <li>证书 <b>没有</b>平台 URI SAN → 回退到 Subject DN 的 CN/O，同时打 WARN 并累加
     *       {@code kylin.backend.mtls.identity.cn_fallback}。回退存在的唯一原因是：本改动之前签发的
     *       现网证书里没有 SAN，直接切换会把全部存量节点踢下线（节点证书 30 天有效期，见
     *       {@code HostAgentCertificateService}）。</li>
     * </ol>
     *
     * <p><b>回退何时可以删除</b>：满足以下任一条件即可把第 3 步改成 {@code return null}——
     * (a) 全部存量节点已完成一次证书轮换 / re-enroll（即最后一张本改动前签发的证书过期，
     * 最迟为"升级上线时刻 + 30 天"）；或 (b) 该 counter 在一个完整的证书有效期窗口内保持为 0。
     * 删除时同步删掉 {@link #subjectValue} 与本方法的回退分支，并更新
     * {@code docs/01-架构设计/gRPC-mTLS通信方案.md} 的「已知安全边界」。
     */
    HostAgentMtlsIdentity identityFromCertificate(X509Certificate certificate) {
        String subject = certificate.getSubjectX500Principal().getName();
        List<NodeIdentitySan.NodeIdentity> sanIdentities = NodeIdentitySan.parseAll(certificate);
        if (sanIdentities.size() > 1) {
            sanConflictCounter.increment();
            log.warn("mTLS peer certificate carries {} conflicting node identity SANs ({}); rejecting. subject={} serial={}",
                    sanIdentities.size(), NodeIdentitySan.describe(sanIdentities), subject,
                    certificate.getSerialNumber().toString(16));
            return null;
        }

        String nodeId;
        String organizationId;
        if (sanIdentities.size() == 1) {
            NodeIdentitySan.NodeIdentity san = sanIdentities.get(0);
            organizationId = san.organizationId();
            nodeId = san.nodeId();
        } else {
            organizationId = subjectValue(subject, "O");
            nodeId = subjectValue(subject, "CN");
            cnFallbackCounter.increment();
            log.warn("mTLS peer certificate has no {}<org>/<node> SAN; falling back to legacy Subject DN CN/O identity "
                            + "(org={} node={} serial={}). Re-enroll this node so its certificate carries a SAN; "
                            + "the CN fallback is scheduled for removal.",
                    NodeIdentitySan.URI_PREFIX, organizationId, nodeId, certificate.getSerialNumber().toString(16));
        }
        if (nodeId.isBlank() || organizationId.isBlank()) {
            return null;
        }
        return new HostAgentMtlsIdentity(
                organizationId,
                nodeId,
                normalizeSerial(certificate.getSerialNumber().toString(16)),
                subject,
                certificate.getNotAfter().toInstant()
        );
    }

    boolean peerCertificateAllowed(String method, HostAgentMtlsIdentity identity) {
        if (identity.expiresAt() != null && identity.expiresAt().isBefore(Instant.now())) {
            return false;
        }
        List<CertificateState> rows = jdbcTemplate.query("""
                select cert_status, cert_serial_number, cert_expires_at
                from app_managed_host
                where organization_id = ? and node_id = ?
                order by id asc
                limit 1
                """, (rs, rowNum) -> new CertificateState(
                rs.getString("cert_status"),
                rs.getString("cert_serial_number"),
                rs.getTimestamp("cert_expires_at")
        ), identity.organizationId(), identity.nodeId());
        if (rows.isEmpty()) {
            log.warn("mTLS rejected: no app_managed_host row for org={} node={} serial={} method={}",
                    identity.organizationId(), identity.nodeId(), identity.serialNumber(), method);
            return false;
        }
        CertificateState state = rows.get(0);
        if (!state.matchesActive(identity.serialNumber())) {
            log.warn("mTLS rejected: certificate not bound org={} node={} presentedSerial={} storedStatus={} storedSerial={} method={}",
                    identity.organizationId(), identity.nodeId(), identity.serialNumber(),
                    state.status(), state.serialNumber(), method);
            return false;
        }
        return true;
    }

    /**
     * 遗留路径：只在证书没有平台 URI SAN 时使用。新证书的身份不经过这里。
     * 见 {@link #identityFromCertificate} 里关于何时删除回退的说明。
     */
    static String subjectValue(String subject, String key) {
        try {
            LdapName name = new LdapName(subject);
            for (var rdn : name.getRdns()) {
                if (key.equalsIgnoreCase(rdn.getType())) {
                    Object value = rdn.getValue();
                    return value == null ? "" : value.toString().trim();
                }
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    static String normalizeSerial(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toLowerCase();
    }

    private record CertificateState(String status, String serialNumber, Timestamp expiresAt) {
        boolean matchesActive(String currentSerialNumber) {
            if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) {
                return false;
            }
            if (!"ACTIVE".equalsIgnoreCase(status == null || status.isBlank() ? "ACTIVE" : status.trim())) {
                return false;
            }
            return !normalizeSerial(serialNumber).isBlank() && sameSerial(serialNumber, currentSerialNumber);
        }

        private boolean sameSerial(String left, String right) {
            return !normalizeSerial(left).isBlank() && normalizeSerial(left).equals(normalizeSerial(right));
        }
    }
}
