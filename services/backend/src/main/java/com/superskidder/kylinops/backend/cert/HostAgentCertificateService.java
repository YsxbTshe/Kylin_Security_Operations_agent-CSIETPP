package com.superskidder.kylinops.backend.cert;

import com.superskidder.kylinops.backend.grpc.EnrollMessages;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CRLConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HostAgentCertificateService {

    private final Path certDir;
    private final long caOverlapGraceSeconds;
    private final String ocspResponderUrl;
    private final List<String> serverDnsNames;
    private final List<String> serverIpAddresses;
    private X509Certificate caCertificate;
    private PrivateKey caPrivateKey;

    public HostAgentCertificateService(@Value("${app.host-agent.grpc.cert-dir:./data/host-agent-certs}") String certDir) {
        this(certDir, 7 * 24 * 60 * 60, "");
    }

    public HostAgentCertificateService(String certDir, long caOverlapGraceSeconds) {
        this(certDir, caOverlapGraceSeconds, "");
    }

    public HostAgentCertificateService(String certDir, long caOverlapGraceSeconds, String ocspResponderUrl) {
        this(certDir, caOverlapGraceSeconds, ocspResponderUrl, "localhost", "127.0.0.1");
    }

    @Autowired
    public HostAgentCertificateService(
            @Value("${app.host-agent.grpc.cert-dir:./data/host-agent-certs}") String certDir,
            @Value("${app.host-agent.grpc.ca-overlap-grace-seconds:604800}") long caOverlapGraceSeconds,
            @Value("${app.host-agent.grpc.ocsp-url:}") String ocspResponderUrl,
            @Value("${app.host-agent.grpc.server-dns-names:localhost}") String serverDnsNames,
            @Value("${app.host-agent.grpc.server-ip-addresses:127.0.0.1}") String serverIpAddresses
    ) {
        this.certDir = Path.of(certDir);
        this.caOverlapGraceSeconds = Math.max(0, caOverlapGraceSeconds);
        this.ocspResponderUrl = ocspResponderUrl == null ? "" : ocspResponderUrl.trim();
        this.serverDnsNames = splitNames(serverDnsNames, "localhost");
        this.serverIpAddresses = splitNames(serverIpAddresses, "127.0.0.1");
    }

    @PostConstruct
    public void initialize() throws Exception {
        Files.createDirectories(certDir);
        Path caCert = certDir.resolve("ca.crt");
        Path caKey = certDir.resolve("ca.key");
        if (Files.exists(caCert) && Files.exists(caKey)) {
            caCertificate = readCertificate(caCert);
            caPrivateKey = readPrivateKey(caKey);
            cleanupExpiredOverlapAuthorities();
            return;
        }
        KeyPair keyPair = generateRsaKeyPair();
        caPrivateKey = keyPair.getPrivate();
        caCertificate = selfSignCa(keyPair);
        writePem(caCert, caCertificate);
        writePem(caKey, caPrivateKey);
        cleanupExpiredOverlapAuthorities();
    }

    public EnrollMessages.EnrollNodeResponse signNodeCertificate(EnrollMessages.EnrollNodeRequest request) throws Exception {
        SignedNodeCertificate signed = signNodeCertificate(request.organizationId(), request.nodeId(), request.hostname(), request.csrPem());
        return new EnrollMessages.EnrollNodeResponse(true, "enrolled", signed.nodeCertPem(), signed.caCertPem(), signed.expiresUnixTime(), signed.serialNumber());
    }

    private SignedNodeCertificate signNodeCertificate(String organizationId, String nodeId, String hostname, String csrPem) throws Exception {
        PKCS10CertificationRequest csr = readCsr(csrPem);
        Instant now = Instant.now();
        Instant expiresAt = now.plus(30, ChronoUnit.DAYS);
        X500Name subject = new X500NameBuilder(BCStyle.INSTANCE)
                .addRDN(BCStyle.CN, nodeId)
                .addRDN(BCStyle.O, organizationId)
                .addRDN(BCStyle.OU, hostname == null || hostname.isBlank() ? "kylin-host-agent" : hostname)
                .build();
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                caIssuer(),
                new BigInteger(160, new SecureRandom()),
                Date.from(now.minus(1, ChronoUnit.MINUTES)),
                Date.from(expiresAt),
                subject,
                csr.getSubjectPublicKeyInfo()
        );
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
        // 机器身份的权威载体是 SAN，不是 CN——见 NodeIdentitySan 的说明。
        // 身份取值只来自服务端已校验过的 enroll 请求字段；CSR 自带的任何扩展都不会被复制到证书里
        // （X509v3CertificateBuilder 只取 CSR 的公钥），所以节点无法自选身份。
        // 非 critical：RFC 5280 §4.2.1.6 只要求 subject 为空时 SAN 必须 critical，这里 subject 非空。
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(
                new GeneralName(GeneralName.uniformResourceIdentifier, NodeIdentitySan.uri(organizationId, nodeId))
        ));
        addOcspAuthorityInfoAccess(builder);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(caPrivateKey);
        X509CertificateHolder holder = builder.build(signer);
        X509Certificate nodeCert = new JcaX509CertificateConverter().getCertificate(holder);
        return new SignedNodeCertificate(toPem(nodeCert), toPem(caCertificate), expiresAt.getEpochSecond(), nodeCert.getSerialNumber().toString(16));
    }

    public Path caCertPath() {
        return certDir.resolve("ca.crt");
    }

    public X509Certificate caCertificate() {
        return caCertificate;
    }

    public synchronized List<X509Certificate> trustedCaCertificates() throws Exception {
        cleanupExpiredOverlapAuthorities();
        List<X509Certificate> certificates = new ArrayList<>();
        certificates.add(caCertificate);
        for (CertificateAuthorityMaterial material : overlapCertificateAuthorities()) {
            certificates.add(material.certificate());
        }
        return List.copyOf(certificates);
    }

    public synchronized CertificateAuthorityInfo rotateCertificateAuthority() throws Exception {
        preserveCurrentCaForOverlap();
        KeyPair keyPair = generateRsaKeyPair();
        X509Certificate nextCa = selfSignCa(keyPair);
        writePem(certDir.resolve("ca.crt"), nextCa);
        writePem(certDir.resolve("ca.key"), keyPair.getPrivate());
        Files.deleteIfExists(certDir.resolve("server.crt"));
        Files.deleteIfExists(certDir.resolve("server.key"));
        caPrivateKey = keyPair.getPrivate();
        caCertificate = nextCa;
        cleanupExpiredOverlapAuthorities();
        return new CertificateAuthorityInfo(
                nextCa.getSubjectX500Principal().getName(),
                nextCa.getSerialNumber().toString(16),
                nextCa.getNotAfter().toInstant().getEpochSecond()
        );
    }

    public String caCertificatePem() throws IOException {
        return toPem(caCertificate);
    }

    public String revocationListPem(List<RevokedCertificate> revokedCertificates) throws Exception {
        return toPem(revocationList(revokedCertificates));
    }

    public X509CRL revocationList(List<RevokedCertificate> revokedCertificates) throws Exception {
        return revocationListFor(caCertificate, caPrivateKey, revokedCertificates);
    }

    public synchronized List<X509CRL> revocationLists(List<RevokedCertificate> revokedCertificates) throws Exception {
        cleanupExpiredOverlapAuthorities();
        List<X509CRL> result = new ArrayList<>();
        result.add(revocationListFor(caCertificate, caPrivateKey, revokedCertificates));
        for (CertificateAuthorityMaterial material : overlapCertificateAuthorities()) {
            result.add(revocationListFor(material.certificate(), material.privateKey(), revokedCertificates));
        }
        return List.copyOf(result);
    }

    private X509CRL revocationListFor(X509Certificate issuerCertificate, PrivateKey issuerPrivateKey, List<RevokedCertificate> revokedCertificates) throws Exception {
        Instant now = Instant.now();
        X509v2CRLBuilder builder = new X509v2CRLBuilder(
                issuer(issuerCertificate),
                Date.from(now)
        );
        builder.setNextUpdate(Date.from(now.plus(1, ChronoUnit.DAYS)));
        for (RevokedCertificate revoked : revokedCertificates == null ? List.<RevokedCertificate>of() : revokedCertificates) {
            if (revoked.serialNumber() == null || revoked.serialNumber().isBlank()) {
                continue;
            }
            builder.addCRLEntry(
                    new BigInteger(revoked.serialNumber().trim(), 16),
                    Date.from(revoked.revokedAt() == null ? now : revoked.revokedAt()),
                    CRLReason.privilegeWithdrawn
            );
        }
        return new JcaX509CRLConverter().getCRL(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerPrivateKey)));
    }

    public synchronized byte[] ocspResponse(byte[] requestBytes, List<CertificateStatusRecord> certificateStatuses) throws Exception {
        cleanupExpiredOverlapAuthorities();
        OCSPReq request = new OCSPReq(requestBytes);
        DigestCalculatorProvider digestProvider = new JcaDigestCalculatorProviderBuilder().build();
        List<CertificateAuthorityMaterial> issuers = new ArrayList<>();
        issuers.add(new CertificateAuthorityMaterial(caCertificate, caPrivateKey, 0));
        issuers.addAll(overlapCertificateAuthorities());
        CertificateAuthorityMaterial responder = ocspResponderIssuer(request, digestProvider, issuers);
        X509CertificateHolder issuerHolder = certificateHolder(responder.certificate());
        BasicOCSPRespBuilder responseBuilder = new BasicOCSPRespBuilder(issuerHolder.getSubjectPublicKeyInfo(), digestProvider.get(CertificateID.HASH_SHA1));
        Instant now = Instant.now();
        for (var requestItem : request.getRequestList()) {
            CertificateID certificateId = requestItem.getCertID();
            CertificateStatus status = ocspStatus(certificateId, issuerHolder, digestProvider, certificateStatuses, now);
            responseBuilder.addResponse(certificateId, status, Date.from(now), Date.from(now.plus(1, ChronoUnit.HOURS)));
        }
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(responder.privateKey());
        BasicOCSPResp basicResponse = responseBuilder.build(signer, new X509CertificateHolder[]{issuerHolder}, Date.from(now));
        return new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basicResponse).getEncoded();
    }

    public byte[] ocspErrorResponse(int status) throws IOException {
        try {
            return new OCSPRespBuilder().build(status, null).getEncoded();
        } catch (Exception exception) {
            throw new IOException("build OCSP error response", exception);
        }
    }

    public Path serverCertPath() throws Exception {
        Path cert = certDir.resolve("server.crt");
        Path key = certDir.resolve("server.key");
        if (Files.exists(cert) && Files.exists(key) && serverCertificateMatchesConfiguredNames(cert)) {
            return cert;
        }
        KeyPair keyPair = generateRsaKeyPair();
        X509Certificate serverCert = signServerCertificate(keyPair);
        writePem(cert, serverCert);
        writePem(key, keyPair.getPrivate());
        return cert;
    }

    public Path serverKeyPath() throws Exception {
        serverCertPath();
        return certDir.resolve("server.key");
    }

    private X509Certificate selfSignCa(KeyPair keyPair) throws Exception {
        Instant now = Instant.now();
        X500Name subject = new X500Name("CN=Kylin SafeOps Development CA,O=KylinOps");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger(160, new SecureRandom()),
                Date.from(now.minus(1, ChronoUnit.MINUTES)),
                Date.from(now.plus(3650, ChronoUnit.DAYS)),
                subject,
                keyPair.getPublic()
        );
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate())));
    }

    private X509Certificate signServerCertificate(KeyPair keyPair) throws Exception {
        Instant now = Instant.now();
        String commonName = serverDnsNames.isEmpty() ? "localhost" : serverDnsNames.get(0);
        X500Name subject = new X500Name("CN=" + commonName + ",O=KylinOps");
        X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                caIssuer(),
                new BigInteger(160, new SecureRandom()),
                Date.from(now.minus(1, ChronoUnit.MINUTES)),
                Date.from(now.plus(365, ChronoUnit.DAYS)),
                subject,
                keyPair.getPublic()
        );
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(serverSubjectAlternativeNames()));
        addOcspAuthorityInfoAccess(builder);
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(caPrivateKey)));
    }

    private GeneralName[] serverSubjectAlternativeNames() {
        List<GeneralName> names = new ArrayList<>();
        for (String dns : serverDnsNames) {
            names.add(new GeneralName(GeneralName.dNSName, dns));
        }
        for (String ip : serverIpAddresses) {
            names.add(new GeneralName(GeneralName.iPAddress, ip));
        }
        if (names.isEmpty()) {
            names.add(new GeneralName(GeneralName.dNSName, "localhost"));
            names.add(new GeneralName(GeneralName.iPAddress, "127.0.0.1"));
        }
        return names.toArray(GeneralName[]::new);
    }

    private boolean serverCertificateMatchesConfiguredNames(Path certPath) {
        try {
            X509Certificate certificate = readCertificate(certPath);
            var sans = certificate.getSubjectAlternativeNames();
            if (sans == null) {
                return false;
            }
            List<String> dns = new ArrayList<>();
            List<String> ips = new ArrayList<>();
            for (List<?> entry : sans) {
                if (entry.size() < 2 || !(entry.get(0) instanceof Integer type)) {
                    continue;
                }
                String value = String.valueOf(entry.get(1));
                if (type == 2) {
                    dns.add(value.toLowerCase(Locale.ROOT));
                } else if (type == 7) {
                    ips.add(value);
                }
            }
            for (String expected : serverDnsNames) {
                if (!dns.contains(expected.toLowerCase(Locale.ROOT))) {
                    return false;
                }
            }
            for (String expected : serverIpAddresses) {
                if (!ips.contains(expected)) {
                    return false;
                }
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static List<String> splitNames(String raw, String fallback) {
        List<String> names = new ArrayList<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !names.contains(trimmed)) {
                    names.add(trimmed);
                }
            }
        }
        if (names.isEmpty() && fallback != null && !fallback.isBlank()) {
            names.add(fallback);
        }
        return List.copyOf(names);
    }

    private void addOcspAuthorityInfoAccess(X509v3CertificateBuilder builder) throws Exception {
        if (ocspResponderUrl.isBlank()) {
            return;
        }
        builder.addExtension(
                Extension.authorityInfoAccess,
                false,
                new AuthorityInformationAccess(AccessDescription.id_ad_ocsp, new GeneralName(GeneralName.uniformResourceIdentifier, ocspResponderUrl))
        );
    }

    private CertificateAuthorityMaterial ocspResponderIssuer(OCSPReq request, DigestCalculatorProvider digestProvider, List<CertificateAuthorityMaterial> issuers) throws Exception {
        for (var requestItem : request.getRequestList()) {
            for (CertificateAuthorityMaterial issuer : issuers) {
                if (requestItem.getCertID().matchesIssuer(certificateHolder(issuer.certificate()), digestProvider)) {
                    return issuer;
                }
            }
        }
        return issuers.get(0);
    }

    private CertificateStatus ocspStatus(CertificateID certificateId, X509CertificateHolder issuerHolder, DigestCalculatorProvider digestProvider, List<CertificateStatusRecord> certificateStatuses, Instant now) throws Exception {
        if (!certificateId.matchesIssuer(issuerHolder, digestProvider)) {
            return new UnknownStatus();
        }
        String serialNumber = normalizeSerial(certificateId.getSerialNumber().toString(16));
        for (CertificateStatusRecord status : certificateStatuses == null ? List.<CertificateStatusRecord>of() : certificateStatuses) {
            if (!serialNumber.equals(normalizeSerial(status.serialNumber()))) {
                continue;
            }
            if ("REVOKED".equalsIgnoreCase(status.status())) {
                Instant revokedAt = status.revokedAt() == null ? now : status.revokedAt();
                return new RevokedStatus(Date.from(revokedAt), CRLReason.privilegeWithdrawn);
            }
            return CertificateStatus.GOOD;
        }
        return new UnknownStatus();
    }

    private X509CertificateHolder certificateHolder(X509Certificate certificate) throws CertificateEncodingException, IOException {
        return new X509CertificateHolder(certificate.getEncoded());
    }

    private String normalizeSerial(String serialNumber) {
        return serialNumber == null ? "" : serialNumber.trim().toLowerCase(Locale.ROOT).replaceFirst("^0+", "");
    }

    private X500Name caIssuer() {
        return issuer(caCertificate);
    }

    private X500Name issuer(X509Certificate certificate) {
        return X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded());
    }

    private void preserveCurrentCaForOverlap() throws IOException {
        if (caCertificate == null || caPrivateKey == null || caOverlapGraceSeconds <= 0) {
            return;
        }
        Files.createDirectories(overlapDir());
        long graceUntil = Instant.now().plusSeconds(caOverlapGraceSeconds).getEpochSecond();
        String prefix = "ca-" + safeSerial(caCertificate) + "-until-" + graceUntil;
        writePem(overlapDir().resolve(prefix + ".crt"), caCertificate);
        writePem(overlapDir().resolve(prefix + ".key"), caPrivateKey);
    }

    private List<CertificateAuthorityMaterial> overlapCertificateAuthorities() throws Exception {
        if (!Files.isDirectory(overlapDir())) {
            return List.of();
        }
        List<CertificateAuthorityMaterial> result = new ArrayList<>();
        try (var paths = Files.list(overlapDir())) {
            for (Path certPath : paths.filter(path -> path.getFileName().toString().endsWith(".crt")).toList()) {
                long graceUntil = overlapGraceUntil(certPath);
                if (graceUntil <= Instant.now().getEpochSecond()) {
                    continue;
                }
                Path keyPath = Path.of(certPath.toString().replaceFirst("\\.crt$", ".key"));
                if (!Files.exists(keyPath)) {
                    continue;
                }
                X509Certificate certificate = readCertificate(certPath);
                if (certificate.getNotAfter().toInstant().isBefore(Instant.now())) {
                    continue;
                }
                result.add(new CertificateAuthorityMaterial(certificate, readPrivateKey(keyPath), graceUntil));
            }
        }
        return result;
    }

    private void cleanupExpiredOverlapAuthorities() throws IOException {
        if (!Files.isDirectory(overlapDir())) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        try (var paths = Files.list(overlapDir())) {
            for (Path path : paths.toList()) {
                if (overlapGraceUntil(path) <= now) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private Path overlapDir() {
        return certDir.resolve("ca-overlap");
    }

    private long overlapGraceUntil(Path path) {
        String filename = path.getFileName().toString();
        int marker = filename.lastIndexOf("-until-");
        if (marker < 0) {
            return 0;
        }
        String value = filename.substring(marker + "-until-".length()).replaceFirst("\\.(crt|key)$", "");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private String safeSerial(X509Certificate certificate) {
        return certificate.getSerialNumber().toString(16).toLowerCase(Locale.ROOT).replaceAll("[^a-f0-9]", "");
    }

    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private PKCS10CertificationRequest readCsr(String csrPem) throws IOException {
        try (PEMParser parser = new PEMParser(new java.io.StringReader(csrPem))) {
            return (PKCS10CertificationRequest) parser.readObject();
        }
    }

    private X509Certificate readCertificate(Path path) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (var input = Files.newInputStream(path)) {
            return (X509Certificate) factory.generateCertificate(input);
        }
    }

    private PrivateKey readPrivateKey(Path path) throws Exception {
        try (PEMParser parser = new PEMParser(Files.newBufferedReader(path));) {
            Object object = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
            if (object instanceof PEMKeyPair keyPair) {
                return converter.getPrivateKey(keyPair.getPrivateKeyInfo());
            }
            return converter.getPrivateKey((org.bouncycastle.asn1.pkcs.PrivateKeyInfo) object);
        }
    }

    private void writePem(Path path, Object value) throws IOException {
        try (JcaPEMWriter writer = new JcaPEMWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))) {
            writer.writeObject(value);
        }
    }

    private String toPem(Object value) throws IOException {
        java.io.StringWriter buffer = new java.io.StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(buffer)) {
            writer.writeObject(value);
        }
        return buffer.toString();
    }

    private record SignedNodeCertificate(String nodeCertPem, String caCertPem, long expiresUnixTime, String serialNumber) {
    }

    public record RevokedCertificate(String serialNumber, Instant revokedAt, String reason) {
    }

    public record CertificateAuthorityInfo(String subject, String serialNumber, long expiresUnixTime) {
    }

    public record CertificateStatusRecord(String serialNumber, String status, Instant revokedAt, String revocationReason) {
    }

    private record CertificateAuthorityMaterial(X509Certificate certificate, PrivateKey privateKey, long graceUntilUnixTime) {
    }
}
