package com.superskidder.kylinops.backend.agentruntime.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 解析 kylin-skill.yaml：信任等级申请、allowed_tools、required_commands 与资源额度。
 * manifest 中的 trust_level 只是申请值；授予的信任等级由审核/签名流程决定（导入默认 UNTRUSTED）。
 */
public final class SkillManifestParser {

    public static final String MANIFEST_FILE = "kylin-skill.yaml";
    private static final int MAX_MANIFEST_CHARS = 16384;
    private static final int MAX_LIST_ENTRIES = 64;

    private SkillManifestParser() {
    }

    public record SkillManifest(
            String skillId,
            SkillTrustLevel requestedTrustLevel,
            List<String> allowedTools,
            List<String> requiredCommands,
            Map<String, Long> resources) {
    }

    public static SkillManifest parse(String manifestYaml) {
        if (manifestYaml == null || manifestYaml.isBlank()) {
            throw new SkillImportException("kylin-skill.yaml is empty");
        }
        if (manifestYaml.length() > MAX_MANIFEST_CHARS) {
            throw new SkillImportException("kylin-skill.yaml is too large");
        }
        Object loaded;
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(MAX_MANIFEST_CHARS);
            loaded = new Yaml(new SafeConstructor(options)).load(manifestYaml);
        } catch (RuntimeException exception) {
            throw new SkillImportException("kylin-skill.yaml is not valid YAML", exception);
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new SkillImportException("kylin-skill.yaml must be a YAML mapping");
        }
        String skillId = optionalString(root, "skill_id");
        SkillTrustLevel requested = SkillTrustLevel.parse(optionalString(root, "trust_level"));
        List<String> allowedTools = stringList(root, "allowed_tools");
        List<String> requiredCommands = stringList(root, "required_commands");
        Map<String, Long> resources = resourceMap(root.get("resources"));
        return new SkillManifest(skillId, requested, allowedTools, requiredCommands, resources);
    }

    private static String optionalString(Map<?, ?> root, String key) {
        Object value = root.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new SkillImportException("kylin-skill.yaml field " + key + " must be a string");
        }
        return text.trim();
    }

    private static List<String> stringList(Map<?, ?> root, String key) {
        Object value = root.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> items)) {
            throw new SkillImportException("kylin-skill.yaml field " + key + " must be a list");
        }
        if (items.size() > MAX_LIST_ENTRIES) {
            throw new SkillImportException("kylin-skill.yaml field " + key + " has too many entries");
        }
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new SkillImportException("kylin-skill.yaml field " + key + " entries must be strings");
            }
            result.add(text.trim());
        }
        return List.copyOf(result);
    }

    private static Map<String, Long> resourceMap(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> entries)) {
            throw new SkillImportException("kylin-skill.yaml resources must be a mapping");
        }
        Map<String, Long> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : entries.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object raw = entry.getValue();
            if (!(raw instanceof Number number) || number.longValue() < 0) {
                throw new SkillImportException("kylin-skill.yaml resource quota must be a non-negative number");
            }
            result.put(key, number.longValue());
        }
        return Map.copyOf(result);
    }
}
