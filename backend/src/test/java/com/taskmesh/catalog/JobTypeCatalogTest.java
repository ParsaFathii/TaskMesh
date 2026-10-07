package com.taskmesh.catalog;

import com.taskmesh.controller.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Payload shape validation for the seven job types of SPEC §9.
 */
class JobTypeCatalogTest {

    private final JobTypeCatalog catalog = new JobTypeCatalog();

    @Test
    void catalogContainsExactlyTheSevenSpecTypes() {
        assertThat(catalog.all())
                .extracting(JobTypeSpec::type)
                .containsExactlyInAnyOrder("csv_analysis", "json_transform", "image_resize",
                        "hash_sha256", "text_statistics", "archive_inspection", "cpu_benchmark");
    }

    @Test
    void everyExampleValidatesAgainstItsOwnSchema() {
        for (JobTypeSpec spec : catalog.all()) {
            assertThat(spec.validatePayload(spec.example()))
                    .as("example for %s must be valid", spec.type())
                    .isEmpty();
        }
    }

    @Test
    void describeExposesSchemaAndExampleForEveryType() {
        for (JobTypeSpec spec : catalog.all()) {
            Map<String, Object> described = spec.describe();
            assertThat(described).containsKeys("type", "description", "payloadSchema", "example");
        }
    }

    @Test
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> catalog.validatePayload("run_python", Map.of("code", "print(1)")))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("Unknown job type");
    }

    @Test
    void missingRequiredFieldIsRejected() {
        assertThatThrownBy(() -> catalog.validatePayload("text_statistics", Map.of("caseSensitive", true)))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("text: required field is missing");
    }

    @Test
    void wrongFieldTypeIsRejected() {
        assertThatThrownBy(() -> catalog.validatePayload("image_resize",
                Map.of("imageBase64", "AAAA", "width", "not-a-number", "height", 64)))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("width: expected integer");
    }

    @Test
    void numericRangeIsEnforced() {
        assertThatThrownBy(() -> catalog.validatePayload("image_resize",
                Map.of("imageBase64", "AAAA", "width", 0, "height", 64)))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("width: must be >= 1");
        assertThatThrownBy(() -> catalog.validatePayload("cpu_benchmark",
                Map.of("workload", "primes", "durationSeconds", 31)))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("durationSeconds: must be <= 30");
    }

    @Test
    void enumValuesAreEnforced() {
        assertThatThrownBy(() -> catalog.validatePayload("cpu_benchmark", Map.of("workload", "flops")))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("workload: must be one of");
    }

    @Test
    void hashJobRequiresExactlyOneSource() {
        assertThatThrownBy(() -> catalog.validatePayload("hash_sha256", Map.of()))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("exactly one of contentBase64 or text");
        assertThatThrownBy(() -> catalog.validatePayload("hash_sha256",
                Map.of("text", "abc", "contentBase64", "YWJj")))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("exactly one of contentBase64 or text");
        catalog.validatePayload("hash_sha256", Map.of("text", "abc"));
    }

    @Test
    void csvLengthLimitIsEnforced() {
        assertThatThrownBy(() -> catalog.validatePayload("csv_analysis",
                Map.of("csv", "a".repeat(2_000_001))))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("csv: must be at most 2000000 characters long");
    }

    @Test
    void arrayItemSchemasAreValidated() {
        assertThatThrownBy(() -> catalog.validatePayload("json_transform",
                Map.of("input", Map.of(), "operations", List.of(Map.of("op", "explode")))))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("operations[0].op: must be one of");
        // paths must be an array of strings
        assertThatThrownBy(() -> catalog.validatePayload("json_transform",
                Map.of("input", Map.of(), "operations", List.of(Map.of("op", "pick", "paths", "user.name")))))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("operations[0].paths: expected array");
    }

    @Test
    void nullAndEmptyPayloadsAreRejected() {
        assertThatThrownBy(() -> catalog.validatePayload("cpu_benchmark", null))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("non-empty");
        assertThatThrownBy(() -> catalog.validatePayload("cpu_benchmark", Map.of()))
                .isInstanceOf(ApiException.Validation.class)
                .hasMessageContaining("workload: required field is missing");
    }
}
