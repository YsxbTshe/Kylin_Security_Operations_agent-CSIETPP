package com.superskidder.kylinops.backend.crypto;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.macs.HMac;
import org.bouncycastle.crypto.params.KeyParameter;

public final class Sm3 {

    public static final String ALGORITHM = "SM3";

    private Sm3() {
    }

    public static String hex(String value) {
        return hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String hex(byte[] value) {
        return HexFormat.of().formatHex(rawHash(value));
    }

    public static byte[] rawHash(byte[] value) {
        SM3Digest digest = new SM3Digest();
        digest.update(value, 0, value.length);
        byte[] output = new byte[digest.getDigestSize()];
        digest.doFinal(output, 0);
        return output;
    }

    /**
     * 计算 HMAC-SM3(key, message) 的十六进制摘要。key 为空时退化为零长密钥（与 Go 端
     * {@code hmac.New(sm3.New, []byte(""))} 的空密钥行为一致），用于无密钥的本地演示。
     */
    public static String hmacHex(byte[] key, String message) {
        HMac mac = new HMac(new SM3Digest());
        mac.init(new KeyParameter(key == null ? new byte[0] : key));
        byte[] input = message.getBytes(StandardCharsets.UTF_8);
        mac.update(input, 0, input.length);
        byte[] output = new byte[mac.getMacSize()];
        mac.doFinal(output, 0);
        return HexFormat.of().formatHex(output);
    }
}
