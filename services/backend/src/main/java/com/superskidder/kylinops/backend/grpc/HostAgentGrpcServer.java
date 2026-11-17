package com.superskidder.kylinops.backend.grpc;

import com.superskidder.kylinops.backend.cert.HostAgentCertificateService;
import com.superskidder.kylinops.backend.agentruntime.transport.NodeExecutorV2GrpcService;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import jakarta.annotation.PreDestroy;
import java.security.KeyStore;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CRL;
import java.security.cert.X509CertSelector;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.TrustManagerFactory;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class HostAgentGrpcServer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(HostAgentGrpcServer.class);

    private final boolean enabled;
    private final int controlPort;
    private final int enrollPort;
    private final HostAgentCertificateService certificateService;
    private final NodeExecutorV2GrpcService v2Service;
    private final HostAgentMtlsInterceptor mtlsInterceptor;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<Flyway> flywayProvider;
    private Server controlServer;
    private Server enrollServer;

    public HostAgentGrpcServer(
            @Value("${app.host-agent.grpc.enabled:true}") boolean enabled,
            @Value("${app.host-agent.grpc.control-port:9090}") int controlPort,
            @Value("${app.host-agent.grpc.enroll-port:9091}") int enrollPort,
            HostAgentCertificateService certificateService,
            NodeExecutorV2GrpcService v2Service,
            HostAgentMtlsInterceptor mtlsInterceptor,
            JdbcTemplate jdbcTemplate,
            ObjectProvider<Flyway> flywayProvider
    ) {
        this.enabled = enabled;
        this.controlPort = controlPort;
        this.enrollPort = enrollPort;
        this.certificateService = certificateService;
        this.v2Service = v2Service;
        this.mtlsInterceptor = mtlsInterceptor;
        this.jdbcTemplate = jdbcTemplate;
        this.flywayProvider = flywayProvider;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!enabled) {
            return;
        }
        // Ensure schema migrations finish before building the CRL-backed trust manager.
        flywayProvider.getIfAvailable();
        startServers();
    }

    public synchronized void reload() throws Exception {
        if (!enabled) {
            return;
        }
        stopServers();
        startServers();
        log.info("Host Agent gRPC servers reloaded after certificate authority change");
    }

    private void startServers() throws Exception {
        enrollServer = NettyServerBuilder.forPort(enrollPort)
                .sslContext(GrpcSslContexts.forServer(certificateService.serverCertPath().toFile(), certificateService.serverKeyPath().toFile()).build())
                .addService(v2Service.enrollServiceDefinition())
                .build()
                .start();
        controlServer = NettyServerBuilder.forPort(controlPort)
                .sslContext(GrpcSslContexts.forServer(certificateService.serverCertPath().toFile(), certificateService.serverKeyPath().toFile())
                        .trustManager(hostAgentTrustManagerFactory())
                        .clientAuth(ClientAuth.REQUIRE)
                        .build())
                .addService(ServerInterceptors.intercept(v2Service.controlServiceDefinition(), mtlsInterceptor))
                .build()
                .start();
        log.info("Host Agent gRPC servers started: enrollPort={} controlPort={}", enrollPort, controlPort);
    }


    private TrustManagerFactory hostAgentTrustManagerFactory() throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        int index = 0;
        for (var certificate : certificateService.trustedCaCertificates()) {
            trustStore.setCertificateEntry("host-agent-ca-" + index, certificate);
            index++;
        }

        PKIXBuilderParameters parameters = new PKIXBuilderParameters(trustStore, new X509CertSelector());
        parameters.setRevocationEnabled(true);
        List<X509CRL> crls = certificateService.revocationLists(loadRevokedCertificates());
        parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(crls)));

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("PKIX");
        trustManagerFactory.init(new CertPathTrustManagerParameters(parameters));
        return trustManagerFactory;
    }

    private List<HostAgentCertificateService.RevokedCertificate> loadRevokedCertificates() {
        return jdbcTemplate.query("""
                select cert_serial_number, cert_revoked_at, cert_revocation_reason
                from app_managed_host
                where cert_status = 'REVOKED'
                  and cert_serial_number is not null and cert_serial_number <> ''
                order by cert_revoked_at desc nulls last, id asc
                """, (rs, rowNum) -> new HostAgentCertificateService.RevokedCertificate(
                rs.getString("cert_serial_number"),
                rs.getTimestamp("cert_revoked_at") == null ? null : rs.getTimestamp("cert_revoked_at").toInstant(),
                rs.getString("cert_revocation_reason")
        ));
    }

    @PreDestroy
    public void stop() throws Exception {
        stopServers();
    }

    private synchronized void stopServers() throws Exception {
        if (controlServer != null) {
            controlServer.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            controlServer = null;
        }
        if (enrollServer != null) {
            enrollServer.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            enrollServer = null;
        }
    }
}
