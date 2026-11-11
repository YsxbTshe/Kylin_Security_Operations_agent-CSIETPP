package com.superskidder.kylinops.backend.agentruntime.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class ServiceDisableTool extends AbstractServiceActionTool {

    public static final String TOOL_ID = "kylin.service.disable";
    public static final String VERSION = "1.0.0";
    public static final String PACKAGE_VERSION_ID = "toolpkgver-service-disable-v1";
    public static final String INPUT_SCHEMA_DIGEST = "d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6";
    public static final String OUTPUT_SCHEMA_DIGEST = "e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7";
    public static final String CATALOG_DIGEST = "f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8";

    public ServiceDisableTool(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override public String toolId() { return TOOL_ID; }
    @Override public String version() { return VERSION; }
    @Override public String packageVersionId() { return PACKAGE_VERSION_ID; }
    @Override public String inputSchemaDigest() { return INPUT_SCHEMA_DIGEST; }
    @Override public String outputSchemaDigest() { return OUTPUT_SCHEMA_DIGEST; }
    @Override public String catalogDigest() { return CATALOG_DIGEST; }
    @Override protected String description() { return "Disable one allowlisted systemd service."; }
}
