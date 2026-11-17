package com.superskidder.kylinops.backend.agentruntime.security;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.Signature;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.gm.GMObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.jcajce.spec.SM2ParameterSpec;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

public final class Sm2WithSm3 {
    private Sm2WithSm3() {}

    public static byte[] sign(PrivateKey key, KeyPurpose purpose, byte[] payload) {
        try {
            Signature signature = signature(purpose);
            signature.initSign(key);
            signature.update(payload);
            return signature.sign();
        } catch (Exception exception) {
            throw new IllegalStateException("cannot sign SM2-with-SM3 payload", exception);
        }
    }

    public static boolean verify(PublicKey key, KeyPurpose purpose, byte[] payload, byte[] derSignature) {
        try {
            Signature signature = signature(purpose);
            signature.initVerify(key);
            signature.update(payload);
            return signature.verify(derSignature);
        } catch (Exception exception) {
            return false;
        }
    }

    public static PrivateKey parsePrivateKey(byte[] pem) {
        Object value = parsePem(pem);
        try {
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(provider());
            if (value instanceof PrivateKeyInfo privateKeyInfo) {
                return converter.getPrivateKey(rewriteSm2AlgorithmOid(privateKeyInfo));
            }
            if (value instanceof PEMKeyPair keyPair) return converter.getKeyPair(keyPair).getPrivate();
            throw new IllegalArgumentException("PEM does not contain an SM2 private key");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("cannot parse SM2 private key", exception);
        }
    }

    /**
     * Ubuntu OpenSSL 3.0.2 的 {@code ecparam -genkey -name SM2} + {@code pkcs8 -topk8}
     * 会把 PKCS#8 算法 OID 写成 {@code sm2}（1.2.156.10197.1.301），而不是
     * {@code id-ecPublicKey} + 曲线 {@code sm2}。BouncyCastle 的
     * {@code JcaPEMKeyConverter} 只认后者。这里在解析前把前者改写成后者，
     * 两个发行版产出的密钥都能用。
     */
    static PrivateKeyInfo rewriteSm2AlgorithmOid(PrivateKeyInfo info) throws IOException {
        ASN1ObjectIdentifier oid = info.getPrivateKeyAlgorithm().getAlgorithm();
        // 算法 OID 本身就是 sm2（1.2.156.10197.1.301），而不是 id-ecPublicKey。
        // 曲线参数无论是否也写成 sm2，都改写成 BC 能认的 id-ecPublicKey + sm2 曲线。
        if (!GMObjectIdentifiers.sm2p256v1.equals(oid)) {
            return info;
        }
        AlgorithmIdentifier rewritten = new AlgorithmIdentifier(
                X9ObjectIdentifiers.id_ecPublicKey,
                GMObjectIdentifiers.sm2p256v1
        );
        return new PrivateKeyInfo(rewritten, info.parsePrivateKey());
    }

    public static PublicKey parsePublicKey(byte[] pem) {
        Object value = parsePem(pem);
        try {
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider(provider());
            if (value instanceof SubjectPublicKeyInfo publicKeyInfo) return converter.getPublicKey(publicKeyInfo);
            if (value instanceof PEMKeyPair keyPair) {
                KeyPair converted = converter.getKeyPair(keyPair);
                return converted.getPublic();
            }
            throw new IllegalArgumentException("PEM does not contain an SM2 public key");
        } catch (Exception exception) {
            throw new IllegalArgumentException("cannot parse SM2 public key", exception);
        }
    }

    private static Signature signature(KeyPurpose purpose) throws Exception {
        Signature signature = Signature.getInstance("SM3withSM2", provider());
        signature.setParameter(new SM2ParameterSpec(purpose.sm2UserId()));
        return signature;
    }

    private static String provider() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        return BouncyCastleProvider.PROVIDER_NAME;
    }

    private static Object parsePem(byte[] pem) {
        try (PEMParser parser = new PEMParser(new InputStreamReader(
                new ByteArrayInputStream(pem), StandardCharsets.US_ASCII))) {
            return parser.readObject();
        } catch (Exception exception) {
            throw new IllegalArgumentException("cannot read PEM", exception);
        }
    }
}
