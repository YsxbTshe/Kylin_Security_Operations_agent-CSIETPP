package com.superskidder.kylinops.backend.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 记忆可信度是「召回结果能否被采信」的关键判据（ADR-001 §2.7），单独钉住其顺序关系。
 */
class MemoryTrustTest {

    @Test
    void verifiedSuccessOutranksUnverified() {
        double verified = MemoryTrust.multiplier(true, "SUCCESS", false, false);
        double plain = MemoryTrust.multiplier(false, null, false, false);
        assertThat(verified).isGreaterThan(plain);
    }

    @Test
    void failedAndRolledBackTaskMemoriesAreDowngraded() {
        double success = MemoryTrust.multiplier(false, "SUCCESS", false, false);
        double failed = MemoryTrust.multiplier(false, "FAILED", false, false);
        double rolledBack = MemoryTrust.multiplier(false, "ROLLED_BACK", false, false);
        assertThat(failed).isLessThan(success);
        assertThat(rolledBack).isLessThan(failed);
    }

    @Test
    void supersededAndExpiredAreStronglyDowngraded() {
        double current = MemoryTrust.multiplier(true, "SUCCESS", false, false);
        double superseded = MemoryTrust.multiplier(true, "SUCCESS", true, false);
        double expired = MemoryTrust.multiplier(true, "SUCCESS", false, true);
        assertThat(superseded).isLessThan(current * 0.5D);
        assertThat(expired).isLessThan(current * 0.5D);
    }

    @Test
    void unknownOutcomeIsNeutralNotBoosted() {
        // 收紧不放宽：认不出的结局取中性 1.0，不得当作可信加分。
        double unknown = MemoryTrust.multiplier(false, "WHATEVER", false, false);
        double none = MemoryTrust.multiplier(false, null, false, false);
        assertThat(unknown).isEqualTo(none);
        assertThat(unknown).isEqualTo(1.0D);
    }
}
