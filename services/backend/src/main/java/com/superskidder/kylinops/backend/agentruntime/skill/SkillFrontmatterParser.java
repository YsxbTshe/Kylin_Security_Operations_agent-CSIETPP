package com.superskidder.kylinops.backend.agentruntime.skill;

import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * 解析 SKILL.md 的 YAML frontmatter（{@code ---} 包裹的首部），返回 name/description 与正文。
 * 只接受安全 YAML（SafeConstructor），拒绝缺失 frontmatter、非映射结构或缺少必填字段的输入。
 */
public final class SkillFrontmatterParser {

    public static final String SKILL_FILE = "SKILL.md";
    private static final int MAX_FRONTMATTER_CHARS = 8192;

    private SkillFrontmatterParser() {
    }

    public record Frontmatter(String name, String description, Map<String, Object> attributes, String body) {
    }

    public static Frontmatter parse(String skillMarkdown) {
        if (skillMarkdown == null || skillMarkdown.isBlank()) {
            throw new SkillImportException("SKILL.md is empty");
        }
        String[] lines = skillMarkdown.split("\n", -1);
        if (!lines[0].trim().equals("---")) {
            throw new SkillImportException("SKILL.md must start with a frontmatter block");
        }
        int closing = -1;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().equals("---")) {
                closing = i;
                break;
            }
        }
        if (closing < 0) {
            throw new SkillImportException("SKILL.md frontmatter block is not closed");
        }
        String yamlBlock = String.join("\n", java.util.Arrays.copyOfRange(lines, 1, closing));
        if (yamlBlock.length() > MAX_FRONTMATTER_CHARS) {
            throw new SkillImportException("SKILL.md frontmatter is too large");
        }
        Object loaded = loadYaml(yamlBlock);
        if (!(loaded instanceof Map<?, ?> attributes)) {
            throw new SkillImportException("SKILL.md frontmatter must be a YAML mapping");
        }
        String name = requiredString(attributes, "name");
        String description = requiredString(attributes, "description");
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : attributes.entrySet()) {
            normalized.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        String body = String.join("\n", java.util.Arrays.copyOfRange(lines, closing + 1, lines.length));
        return new Frontmatter(name, description, normalized, body);
    }

    private static Object loadYaml(String yamlBlock) {
        try {
            LoaderOptions options = new LoaderOptions();
            options.setCodePointLimit(MAX_FRONTMATTER_CHARS);
            return new Yaml(new SafeConstructor(options)).load(yamlBlock);
        } catch (RuntimeException exception) {
            throw new SkillImportException("SKILL.md frontmatter is not valid YAML", exception);
        }
    }

    private static String requiredString(Map<?, ?> attributes, String key) {
        Object value = attributes.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new SkillImportException("SKILL.md frontmatter must include " + key);
        }
        return text.trim();
    }
}
