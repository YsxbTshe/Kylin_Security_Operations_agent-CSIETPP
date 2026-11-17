package com.superskidder.kylinops.backend.policy;

public enum RiskCode {
    R0,
    R1,
    R2,
    R3,
    R4,
    R5;

    public static RiskCode normalize(String value) {
        if (value == null || value.isBlank()) {
            return R0;
        }
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "LOW" -> R0;
            case "MEDIUM" -> R2;
            case "HIGH" -> R4;
            default -> RiskCode.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        };
    }
}
