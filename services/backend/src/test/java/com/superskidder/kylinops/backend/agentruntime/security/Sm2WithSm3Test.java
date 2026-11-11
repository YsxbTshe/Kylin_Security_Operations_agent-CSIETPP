package com.superskidder.kylinops.backend.agentruntime.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.junit.jupiter.api.Test;

class Sm2WithSm3Test {

    @Test
    void parsesSharedTestVectorAndSigns() throws Exception {
        Path vectors = Path.of("..", "..", "contracts", "test-vectors");
        PrivateKey privateKey = Sm2WithSm3.parsePrivateKey(Files.readAllBytes(vectors.resolve("TEST_ONLY_sm2_private.pem")));
        PublicKey publicKey = Sm2WithSm3.parsePublicKey(Files.readAllBytes(vectors.resolve("TEST_ONLY_sm2_public.pem")));
        byte[] payload = "probe".getBytes(StandardCharsets.UTF_8);
        byte[] signature = Sm2WithSm3.sign(privateKey, KeyPurpose.APPROVAL_SIGNING, payload);
        assertTrue(Sm2WithSm3.verify(publicKey, KeyPurpose.APPROVAL_SIGNING, payload, signature));
    }

    @Test
    void parsesPkcs8WhoseAlgorithmOidIsSm2NotIdEcPublicKey() throws Exception {
        ensureProvider();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", BouncyCastleProvider.PROVIDER_NAME);
        generator.initialize(new ECGenParameterSpec("sm2p256v1"), new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        PrivateKeyInfo standard = PrivateKeyInfo.getInstance(keyPair.getPrivate().getEncoded());
        PrivateKeyInfo ubuntuStyle = new PrivateKeyInfo(
                new AlgorithmIdentifier(GMObjectIdentifiers.sm2p256v1),
                standard.parsePrivateKey()
        );
        String pem = toPem(ubuntuStyle);

        PrivateKey parsed = Sm2WithSm3.parsePrivateKey(pem.getBytes(StandardCharsets.US_ASCII));
        assertNotNull(parsed);
        byte[] payload = "ubuntu-sm2-oid".getBytes(StandardCharsets.UTF_8);
        byte[] signature = Sm2WithSm3.sign(parsed, KeyPurpose.APPROVAL_SIGNING, payload);
        assertTrue(Sm2WithSm3.verify(keyPair.getPublic(), KeyPurpose.APPROVAL_SIGNING, payload, signature));
        assertArrayEquals(rewriteOnce(ubuntuStyle).getPrivateKeyAlgorithm().getAlgorithm().getId().getBytes(),
                org.bouncycastle.asn1.x9.X9ObjectIdentifiers.id_ecPublicKey.getId().getBytes());
    }

    private static PrivateKeyInfo rewriteOnce(PrivateKeyInfo info) throws Exception {
        return Sm2WithSm3.rewriteSm2AlgorithmOid(info);
    }

    private static void ensureProvider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static String toPem(Object value) throws Exception {
        StringWriter writer = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(writer)) {
            pemWriter.writeObject(value);
        }
        return writer.toString();
    }
}
