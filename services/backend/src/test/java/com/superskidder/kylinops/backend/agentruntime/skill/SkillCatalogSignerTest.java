package com.superskidder.kylinops.backend.agentruntime.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SkillCatalogSignerTest {

    private static final String TENANT = "org-alpha";
    private static final String SKILL_ID = "kylin.ops.disk-triage";
    private static final String KEY = "unit-test-catalog-key";

    private JdbcTemplate jdbcTemplate;
    private SkillService service;
    private SkillImporter importer;
    private SkillCatalogSigner signer;

    @BeforeEach
    void setUp() {
        jdbcTemplate = SkillTestDatabase.create();
        service = new SkillService(jdbcTemplate);
        importer = new SkillImporter(service);
        signer = new SkillCatalogSigner(service, KEY);
    }

    @Test
    void signThenVerifySucceedsAndPromotesTrust() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        String signatureHex = signer.signLatestVersion(TENANT, SKILL_ID);

        assertThat(signatureHex).hasSize(64);
        assertThat(signer.verify(TENANT, SKILL_ID)).isTrue();
        assertThat(service.findPackage(TENANT, SKILL_ID).orElseThrow().trustLevel())
                .isEqualTo(SkillTrustLevel.PLATFORM_SIGNED);
        SkillVersion version = service.listVersions(TENANT, SKILL_ID).get(0);
        assertThat(version.signatureAlgorithm()).isEqualTo("HMAC-SM3");
        assertThat(version.signatureHex()).isEqualTo(signatureHex);
    }

    @Test
    void tamperedContentDigestFailsVerification() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        signer.signLatestVersion(TENANT, SKILL_ID);

        jdbcTemplate.update("update skill_version set content_sm3 = repeat('0', 64)");

        assertThat(signer.verify(TENANT, SKILL_ID)).isFalse();
    }

    @Test
    void verifyWithWrongKeyFails() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        signer.signLatestVersion(TENANT, SKILL_ID);

        SkillCatalogSigner otherSigner = new SkillCatalogSigner(service, "another-key");

        assertThat(otherSigner.verify(TENANT, SKILL_ID)).isFalse();
    }

    @Test
    void unsignedVersionFailsVerification() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());

        assertThat(signer.verify(TENANT, SKILL_ID)).isFalse();
    }

    @Test
    void missingKeyFailsClosed() {
        importer.importFiles(TENANT, SkillTestDatabase.validPackage());
        SkillCatalogSigner unconfigured = new SkillCatalogSigner(service, "");

        assertThatThrownBy(() -> unconfigured.signLatestVersion(TENANT, SKILL_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing key");
        assertThatThrownBy(() -> unconfigured.verify(TENANT, SKILL_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signing key");
    }

    @Test
    void schemaDigestIsCanonicalAndDeterministic() {
        String reordered = SkillCatalogSigner.schemaDigest("{\"b\":2,\"a\":1}");
        String original = SkillCatalogSigner.schemaDigest("{\"a\":1,\"b\":2}");

        assertThat(reordered).isEqualTo(original).hasSize(64);
        assertThat(SkillCatalogSigner.schemaDigest("{\"a\":2,\"b\":2}")).isNotEqualTo(original);
        assertThatThrownBy(() -> SkillCatalogSigner.schemaDigest("not json"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
