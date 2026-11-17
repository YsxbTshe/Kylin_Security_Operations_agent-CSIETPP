package com.superskidder.kylinops.backend.cert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.superskidder.kylinops.backend.grpc.EnrollMessages;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.AccessDescription;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPReqBuilder;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostAgentCertificateServiceTest {

    @TempDir
    Path certDir;

    @Test
    void serverCertificateIssuerMatchesCaSubject() throws Exception {
        HostAgentCertificateService service = new HostAgentCertificateService(certDir.toString());
        service.initialize();

        X509Certificate caCertificate = readCertificate(service.caCertPath());
        X509Certificate serverCertificate = readCertificate(service.serverCertPath());

        assertEquals(caCertificate.getSubjectX500Principal(), serverCertificate.getIssuerX500Principal());
        serverCertificate.verify(caCertificate.getPublicKey());
    }

    @Test
    void serverCertificateIncludesConfiguredHostNamesAndIps() throws Exception {
        HostAgentCertificateService service = new HostAgentCertificateService(
                certDir.toString(), 3600, "", "ubuntu-backend,localhost", "192.168.77.10,127.0.0.1");
        service.initialize();

        X509Certificate serverCertificate = readCertificate(service.serverCertPath());
        assertTrue(serverCertificate.getSubjectX500Principal().getName().contains("CN=ubuntu-backend"));
        assertTrue(serverCertificate.getSubjectAlternativeNames().stream()
                .anyMatch(entry -> Integer.valueOf(2).equals(entry.get(0)) && "ubuntu-backend".equals(entry.get(1))));
        assertTrue(serverCertificate.getSubjectAlternativeNames().stream()
                .anyMatch(entry -> Integer.valueOf(7).equals(entry.get(0)) && "192.168.77.10".equals(String.valueOf(entry.get(1)))));
    }

    @Test
    void shouldKeepPreviousCaTrustedDuringOverlapGracePeriod() throws Exception {
        HostAgentCertificateService service = new HostAgentCertificateService(certDir.toString(), 3600);
        service.initialize();
        X509Certificate previousCa = service.caCertificate();

        service.rotateCertificateAuthority();

        X509Certificate currentCa = service.caCertificate();
        List<String> trustedSerials = service.trustedCaCertificates().stream()
                .map(certificate -> certificate.getSerialNumber().toString(16))
                .toList();
        assertTrue(trustedSerials.contains(previousCa.getSerialNumber().toString(16)));
        assertTrue(trustedSerials.contains(currentCa.getSerialNumber().toString(16)));
        assertEquals(2, service.revocationLists(List.of()).size());
    }

    @Test
    void shouldSkipPreviousCaWhenOverlapGracePeriodDisabled() throws Exception {
        HostAgentCertificateService service = new HostAgentCertificateService(certDir.toString(), 0);
        service.initialize();
        X509Certificate previousCa = service.caCertificate();

        service.rotateCertificateAuthority();

        List<String> trustedSerials = service.trustedCaCertificates().stream()
                .map(certificate -> certificate.getSerialNumber().toString(16))
                .toList();
        assertFalse(trustedSerials.contains(previousCa.getSerialNumber().toString(16)));
        assertEquals(1, trustedSerials.size());
    }

    // 机器身份写进 SAN 而不是只写 CN：校验侧（HostAgentMtlsInterceptor）以此为准。
    @Test
    void shouldEmbedNodeIdentitySanInNodeCertificate() throws Exception {
        HostAgentCertificateService service = new HostAgentCertificateService(certDir.toString());
        service.initialize();

        X509Certificate nodeCertificate = signNodeCertificate(service);

        assertEquals(
                List.of(new NodeIdentitySan.NodeIdentity("org-alpha", "node-1")),
                NodeIdentitySan.parseAll(nodeCertificate)
        );
        // SAN 是新增的权威来源，CN/O 仍然保留：存量校验路径与人工排障都还在读 DN。
        assertTrue(nodeCertificate.getSubjectX500Principal().getName().contains("CN=node-1"));
    }

    // CSR 里自带的 SAN 不得被复制进签发结果，否则节点就能自选身份。
    @Test
    void shouldIgnoreSubjectAlternativeNameRequestedByTheCsr() throws Exception {
        HostAgentCertificateService service = new HostAgentCertificateService(certDir.toString());
        service.initialize();

        EnrollMessages.EnrollNodeResponse response = service.signNodeCertificate(new EnrollMessages.EnrollNodeRequest(
                "bootstrap-token", "org-alpha", "node-1", "host-1", "machine-1", "test-agent",
                csrPemWithRequestedSan("node-1", NodeIdentitySan.uri("org-admin", "node-admin"))
        ));

        X509Certificate nodeCertificate = readCertificate(response.nodeCertPem());
        assertEquals(
                List.of(new NodeIdentitySan.NodeIdentity("org-alpha", "node-1")),
                NodeIdentitySan.parseAll(nodeCertificate)
        );
    }

    @Test
    void shouldEmbedOcspResponderUrlInNodeCertificate() throws Exception {
        String ocspUrl = "https://control.example.com/internal-ocsp";
        HostAgentCertificateService service = new HostAgentCertificateService(certDir.toString(), 3600, ocspUrl);
        service.initialize();

        X509Certificate nodeCertificate = signNodeCertificate(service);

        AuthorityInformationAccess authorityInformationAccess = authorityInformationAccess(nodeCertificate);
        assertNotNull(authorityInformationAccess);
        assertEquals(AccessDescription.id_ad_ocsp, authorityInformationAccess.getAccessDescriptions()[0].getAccessMethod());
        assertEquals(ocspUrl, authorityInformationAccess.getAccessDescriptions()[0].getAccessLocation().getName().toString());
    }

    @Test
    void shouldReturnGoodOcspStatusForActiveCertificate() throws Exception {
        HostAgentCertificateService service = new HostAgentCertificateService(certDir.toString(), 3600, "https://control.example.com/ocsp");
        service.initialize();
        X509Certificate nodeCertificate = signNodeCertificate(service);

        OCSPResp response = ocspResponse(service, nodeCertificate, List.of(new HostAgentCertificateService.CertificateStatusRecord(
                nodeCertificate.getSerialNumber().toString(16),
                "ACTIVE",
                null,
                null
        )));

        assertEquals(OCSPResp.SUCCESSFUL, response.getStatus());
        BasicOCSPResp basicResponse = (BasicOCSPResp) response.getResponseObject();
        assertEquals(CertificateStatus.GOOD, basicResponse.getResponses()[0].getCertStatus());
    }

    @Test
    void shouldReturnRevokedOcspStatusForRevokedCertificate() throws Exception {
        HostAgentCertificateService service = new HostAgentCertificateService(certDir.toString(), 3600, "https://control.example.com/ocsp");
        service.initialize();
        X509Certificate nodeCertificate = signNodeCertificate(service);

        OCSPResp response = ocspResponse(service, nodeCertificate, List.of(new HostAgentCertificateService.CertificateStatusRecord(
                nodeCertificate.getSerialNumber().toString(16),
                "REVOKED",
                Instant.now().minusSeconds(60),
                "manual revocation"
        )));

        assertEquals(OCSPResp.SUCCESSFUL, response.getStatus());
        BasicOCSPResp basicResponse = (BasicOCSPResp) response.getResponseObject();
        assertTrue(basicResponse.getResponses()[0].getCertStatus() instanceof RevokedStatus);
    }


    @Test
    void shouldAnswerOcspForPreviousCaDuringOverlapGracePeriod() throws Exception {
        HostAgentCertificateService service = new HostAgentCertificateService(certDir.toString(), 3600, "https://control.example.com/ocsp");
        service.initialize();
        X509Certificate previousCa = service.caCertificate();
        X509Certificate previousNodeCertificate = signNodeCertificate(service);

        service.rotateCertificateAuthority();

        OCSPResp response = ocspResponse(service, previousCa, previousNodeCertificate, List.of(new HostAgentCertificateService.CertificateStatusRecord(
                previousNodeCertificate.getSerialNumber().toString(16),
                "REVOKED",
                Instant.now().minusSeconds(30),
                "certificate authority rotated"
        )));

        assertEquals(OCSPResp.SUCCESSFUL, response.getStatus());
        BasicOCSPResp basicResponse = (BasicOCSPResp) response.getResponseObject();
        assertTrue(basicResponse.getResponses()[0].getCertStatus() instanceof RevokedStatus);
    }

    private X509Certificate signNodeCertificate(HostAgentCertificateService service) throws Exception {
        EnrollMessages.EnrollNodeResponse response = service.signNodeCertificate(new EnrollMessages.EnrollNodeRequest(
                "bootstrap-token",
                "org-alpha",
                "node-1",
                "host-1",
                "machine-1",
                "test-agent",
                csrPem("node-1")
        ));
        return readCertificate(response.nodeCertPem());
    }

    private OCSPResp ocspResponse(HostAgentCertificateService service, X509Certificate nodeCertificate, List<HostAgentCertificateService.CertificateStatusRecord> statuses) throws Exception {
        return ocspResponse(service, service.caCertificate(), nodeCertificate, statuses);
    }

    private OCSPResp ocspResponse(HostAgentCertificateService service, X509Certificate issuerCertificate, X509Certificate nodeCertificate, List<HostAgentCertificateService.CertificateStatusRecord> statuses) throws Exception {
        CertificateID certificateID = certificateID(issuerCertificate, nodeCertificate.getSerialNumber());
        var request = new OCSPReqBuilder().addRequest(certificateID).build();
        return new OCSPResp(service.ocspResponse(request.getEncoded(), statuses));
    }

    private CertificateID certificateID(X509Certificate issuer, BigInteger serialNumber) throws Exception {
        DigestCalculatorProvider digestProvider = new JcaDigestCalculatorProviderBuilder().build();
        X509CertificateHolder issuerHolder = new JcaX509CertificateHolder(issuer);
        return new CertificateID(digestProvider.get(CertificateID.HASH_SHA1), issuerHolder, serialNumber);
    }

    private String csrPem(String commonName) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        PKCS10CertificationRequest request = new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=" + commonName), keyPair.getPublic()).build(signer);
        return toPem(request);
    }

    private String csrPemWithRequestedSan(String commonName, String sanUri) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        ExtensionsGenerator extensions = new ExtensionsGenerator();
        extensions.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(
                new GeneralName(GeneralName.uniformResourceIdentifier, sanUri)));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate());
        PKCS10CertificationRequest request = new JcaPKCS10CertificationRequestBuilder(new X500Principal("CN=" + commonName), keyPair.getPublic())
                .addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate())
                .build(signer);
        return toPem(request);
    }

    private String toPem(Object value) throws Exception {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(value);
        }
        return writer.toString();
    }

    private AuthorityInformationAccess authorityInformationAccess(X509Certificate certificate) throws Exception {
        byte[] extensionValue = certificate.getExtensionValue(Extension.authorityInfoAccess.getId());
        if (extensionValue == null) {
            return null;
        }
        ASN1OctetString octets = ASN1OctetString.getInstance(extensionValue);
        return AuthorityInformationAccess.getInstance(ASN1Primitive.fromByteArray(octets.getOctets()));
    }

    private X509Certificate readCertificate(Path path) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        try (var input = java.nio.file.Files.newInputStream(path)) {
            return (X509Certificate) factory.generateCertificate(input);
        }
    }

    private X509Certificate readCertificate(String pem) throws Exception {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(pem.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
