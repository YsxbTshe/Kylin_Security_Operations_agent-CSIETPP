package com.superskidder.kylinops.backend.auth;

import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.PBEParametersGenerator;
import org.bouncycastle.crypto.digests.SM3Digest;
import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator;
import org.bouncycastle.crypto.params.KeyParameter;
import org.springframework.stereotype.Component;

@Component
public class PasswordHasher {

    private static final String FORMAT = "pbkdf2_sm3";
    private static final int ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    private final SecureRandom secureRandom = new SecureRandom();

    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] hash = derive(rawPassword, salt, ITERATIONS);
        return FORMAT + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    public boolean matches(String rawPassword, String passwordHash) {
        String[] parts = passwordHash == null ? new String[0] : passwordHash.split("\\$");
        if (parts.length != 4 || !FORMAT.equals(parts[0])) {
            return false;
        }

        int iterations = Integer.parseInt(parts[1]);
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] actual = derive(rawPassword, salt, iterations);
        return constantTimeEquals(actual, expected);
    }

    private byte[] derive(String rawPassword, byte[] salt, int iterations) {
        PKCS5S2ParametersGenerator generator = new PKCS5S2ParametersGenerator(new SM3Digest());
        generator.init(PBEParametersGenerator.PKCS5PasswordToUTF8Bytes(rawPassword.toCharArray()), salt, iterations);
        return ((KeyParameter) generator.generateDerivedParameters(HASH_BITS)).getKey();
    }

    private boolean constantTimeEquals(byte[] first, byte[] second) {
        if (first.length != second.length) {
            return false;
        }
        int diff = 0;
        for (int index = 0; index < first.length; index++) {
            diff |= first[index] ^ second[index];
        }
        return diff == 0;
    }
}
