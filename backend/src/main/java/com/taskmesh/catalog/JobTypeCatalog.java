package com.taskmesh.catalog;

import com.taskmesh.controller.ApiException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * The seven built-in job types (SPEC §9). The catalog is the single source of truth for
 * both the public {@code GET /api/v1/job-types} endpoint and the structural validation
 * of submitted payloads.
 */
@Component
public class JobTypeCatalog {

    private static final List<JobTypeSpec> TYPES = List.of(
            csvAnalysis(),
            jsonTransform(),
            imageResize(),
            hashSha256(),
            textStatistics(),
            archiveInspection(),
            cpuBenchmark());

    public List<JobTypeSpec> all() {
        return TYPES;
    }

    public Optional<JobTypeSpec> find(String type) {
        return TYPES.stream().filter(spec -> spec.type().equals(type)).findFirst();
    }

    /**
     * Validates the payload shape for the given type, failing with a 400 error when
     * the type is unknown or the payload does not conform to its schema.
     */
    public void validatePayload(String type, Map<String, Object> payload) {
        JobTypeSpec spec = find(type).orElseThrow(() ->
                new ApiException.Validation("Unknown job type: '" + type
                        + "' (see GET /api/v1/job-types for supported types)"));
        if (payload == null) {
            throw new ApiException.Validation("payload must be a non-empty object");
        }
        List<String> violations = spec.validatePayload(payload);
        if (!violations.isEmpty()) {
            throw new ApiException.Validation("payload validation failed: " + String.join("; ", violations));
        }
    }

    private static JobTypeSpec csvAnalysis() {
        Map<String, Object> schema = object(Map.of(
                "csv", prop("string", "maxLength", 2_000_000,
                        "description", "CSV content, at most 2 MB"),
                "delimiter", prop("string", "enum", List.of(",", ";", "\\t"),
                        "description", "Column delimiter: comma, semicolon or tab"),
                "hasHeader", prop("boolean", "description", "Treat the first row as a header (default true)")),
                "csv");
        Map<String, Object> example = Map.of(
                "csv", "name,age\nAda,36\nGrace,45\n",
                "delimiter", ",",
                "hasHeader", true);
        return new JobTypeSpec("csv_analysis",
                "Parses CSV content and computes per-column profiles (types, missing values, min/max/mean).",
                schema, example, null);
    }

    private static JobTypeSpec jsonTransform() {
        Map<String, Object> operation = object(Map.of(
                "op", prop("string", "enum", List.of("pick", "remove", "rename", "flatten"),
                        "description", "Transformation to apply"),
                "paths", prop("array", "items", prop("string"),
                        "description", "Dotted key paths for pick/remove"),
                "from", prop("string", "description", "Source key for rename"),
                "to", prop("string", "description", "Target key for rename"),
                "separator", prop("string", "description", "Separator inserted by flatten (default '.')")),
                "op");
        Map<String, Object> schema = object(Map.of(
                "input", prop("object", "description", "Arbitrary JSON object to transform"),
                "operations", prop("array", "items", operation,
                        "description", "Operations applied in order")),
                "input", "operations");
        Map<String, Object> example = Map.of(
                "input", Map.of("user", Map.of("name", "Ada", "email", "ada@example.org"), "active", true),
                "operations", List.of(
                        Map.of("op", "pick", "paths", List.of("user.name")),
                        Map.of("op", "rename", "from", "user.name", "to", "username")));
        return new JobTypeSpec("json_transform",
                "Applies a pipeline of pick/remove/rename/flatten operations to a JSON document.",
                schema, example, null);
    }

    private static JobTypeSpec imageResize() {
        Map<String, Object> schema = object(Map.of(
                "imageBase64", prop("string", "description", "Base64-encoded PNG or JPEG image"),
                "width", prop("integer", "minimum", 1, "maximum", 10_000,
                        "description", "Target width in pixels"),
                "height", prop("integer", "minimum", 1, "maximum", 10_000,
                        "description", "Target height in pixels"),
                "maintainAspect", prop("boolean", "description", "Keep aspect ratio (default true)"),
                "format", prop("string", "enum", List.of("png", "jpeg"),
                        "description", "Output format (default: input format)")),
                "imageBase64", "width", "height");
        Map<String, Object> example = Map.of(
                "imageBase64", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
                "width", 64,
                "height", 64,
                "maintainAspect", true,
                "format", "png");
        return new JobTypeSpec("image_resize",
                "Resizes an encoded image to the requested dimensions; the resized image is returned as a file result.",
                schema, example, null);
    }

    private static JobTypeSpec hashSha256() {
        Map<String, String> descriptionOnly = Map.of(
                "contentBase64", "Base64-encoded bytes to hash",
                "text", "UTF-8 text to hash");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("contentBase64", prop("string", "description", descriptionOnly.get("contentBase64")));
        properties.put("text", prop("string", "description", descriptionOnly.get("text")));
        Map<String, Object> schema = object(properties);
        schema.put("x-exactly-one-of", List.of("contentBase64", "text"));
        Map<String, Object> example = Map.of("text", "The quick brown fox jumps over the lazy dog");
        BiPredicate<Map<String, Object>, StringBuilder> exactlyOne = (payload, errors) -> {
            boolean hasB64 = payload.get("contentBase64") != null;
            boolean hasText = payload.get("text") != null;
            if (hasB64 == hasText) {
                errors.append("payload must provide exactly one of contentBase64 or text");
                return false;
            }
            return true;
        };
        return new JobTypeSpec("hash_sha256",
                "Computes the SHA-256 digest of base64-encoded bytes or plain text.",
                schema, example, exactlyOne);
    }

    private static JobTypeSpec textStatistics() {
        Map<String, Object> schema = object(Map.of(
                "text", prop("string", "maxLength", 2_000_000,
                        "description", "Text to analyse, at most 2 MB"),
                "caseSensitive", prop("boolean", "description", "Case-sensitive word counting (default false)")),
                "text");
        Map<String, Object> example = Map.of(
                "text", "TaskMesh processes jobs. Jobs are tasks.",
                "caseSensitive", false);
        return new JobTypeSpec("text_statistics",
                "Computes character, word, line and paragraph statistics plus top words and reading time.",
                schema, example, null);
    }

    private static JobTypeSpec archiveInspection() {
        Map<String, Object> schema = object(Map.of(
                "archiveBase64", prop("string", "maxLength", 20_000_000,
                        "description", "Base64-encoded ZIP archive, at most 20 MB"),
                "maxEntries", prop("integer", "minimum", 1, "maximum", 10_000,
                        "description", "Maximum number of entries to report (default 500)")),
                "archiveBase64");
        Map<String, Object> example = Map.of(
                "archiveBase64", "UEsDBAoAAAAAAA...",
                "maxEntries", 500);
        return new JobTypeSpec("archive_inspection",
                "Lists entries, sizes and compression ratio of a ZIP archive.",
                schema, example, null);
    }

    private static JobTypeSpec cpuBenchmark() {
        Map<String, Object> schema = object(Map.of(
                "workload", prop("string", "enum", List.of("primes", "matrix"),
                        "description", "Benchmark workload"),
                "durationSeconds", prop("integer", "minimum", 1, "maximum", 30,
                        "description", "Run duration in seconds (default 5)")),
                "workload");
        Map<String, Object> example = Map.of(
                "workload", "primes",
                "durationSeconds", 5);
        return new JobTypeSpec("cpu_benchmark",
                "Measures CPU throughput with a prime-sieve or matrix-multiplication workload.",
                schema, example, null);
    }

    private static Map<String, Object> object(Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>(properties));
        if (required.length > 0) {
            schema.put("required", List.of(required));
        }
        return schema;
    }

    private static Map<String, Object> prop(String type, Object... keyValues) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        for (int i = 0; i < keyValues.length; i += 2) {
            schema.put((String) keyValues[i], keyValues[i + 1]);
        }
        return schema;
    }
}
