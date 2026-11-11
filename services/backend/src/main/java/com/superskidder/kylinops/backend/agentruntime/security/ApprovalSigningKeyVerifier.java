package com.superskidder.kylinops.backend.agentruntime.security;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 审批签名密钥启动自检。路径已配置时立即加载、试签并验签；失败则阻止启动，
 * 避免健康检查 UP、第一次审批才以 {@code cannot parse SM2 private key} 炸掉且消耗审批记录。
 * 三个引用都为空时只打警告——开发/测试空库仍可启动。
 */
@Component
public class ApprovalSigningKeyVerifier {

    private static final Logger log = LoggerFactory.getLogger(ApprovalSigningKeyVerifier.class);
    private static final byte[] PROBE = "kylin-approval-startup-probe".getBytes(StandardCharsets.US_ASCII);

    private final PlatformKeyReferences keyReferences;
    private final ApprovalKeyLoader keyLoader;

    public ApprovalSigningKeyVerifier(PlatformKeyReferences keyReferences, ApprovalKeyLoader keyLoader) {
        this.keyReferences = keyReferences;
        this.keyLoader = keyLoader;
        verify();
    }

    void verify() {
        PlatformKeyReferences.KeyReference reference = keyReferences.forPurpose(KeyPurpose.APPROVAL_SIGNING);
        boolean hasPrivate = notBlank(reference.privateKeyRef());
        boolean hasPublic = notBlank(reference.publicKeyRef());
        boolean hasKeyId = notBlank(reference.keyId());
        if (!hasPrivate && !hasPublic) {
            log.warn("approval signing keys are not configured; backend will start, but the first "
                    + "approval token issue will fail. Set APPROVAL_SIGNING_PRIVATE_KEY_REF and "
                    + "APPROVAL_SIGNING_PUBLIC_KEY_REF.");
            return;
        }
        if (!hasPrivate || !hasPublic || !hasKeyId) {
            throw new IllegalStateException(
                    "approval signing key is partially configured: key-id, private-key-ref and "
                            + "public-key-ref must be set together");
        }
        try {
            PrivateKey privateKey = keyLoader.loadPrivateKey(reference.privateKeyRef());
            PublicKey publicKey = keyLoader.loadPublicKey(reference.publicKeyRef());
            byte[] signature = Sm2WithSm3.sign(privateKey, KeyPurpose.APPROVAL_SIGNING, PROBE);
            if (!Sm2WithSm3.verify(publicKey, KeyPurpose.APPROVAL_SIGNING, PROBE, signature)) {
                throw new IllegalStateException("approval signing key pair does not verify (public/private mismatch)");
            }
            log.info("approval signing key {} loaded and probe-signed successfully", reference.keyId());
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IllegalStateException(
                    "cannot load or probe-sign approval signing key '" + reference.keyId()
                            + "': " + exception.getMessage(),
                    exception);
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
