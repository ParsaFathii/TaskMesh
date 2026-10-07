package com.taskmesh.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;

/**
 * Description of a supported job type: payload JSON schema (subset documented on
 * {@link PayloadValidator}), an example payload and an optional extra check that cannot
 * be expressed with standard schema keywords.
 *
 * @param type        job type identifier used in POST /api/v1/jobs
 * @param description human-readable summary
 * @param payloadSchema JSON-schema subset describing the payload shape
 * @param example     valid example payload
 * @param extraCheck  optional semantic check, appends violations to the given builder
 */
public record JobTypeSpec(
        String type,
        String description,
        Map<String, Object> payloadSchema,
        Map<String, Object> example,
        BiPredicate<Map<String, Object>, StringBuilder> extraCheck
) {

    /** Representation served by GET /api/v1/job-types (no internal lambdas). */
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("description", description);
        out.put("payloadSchema", payloadSchema);
        out.put("example", example);
        return out;
    }

    /** Validates the shape of a payload against this spec. */
    public List<String> validatePayload(Map<String, Object> payload) {
        List<String> violations = PayloadValidator.validate(payload, payloadSchema);
        if (extraCheck != null && payload != null) {
            StringBuilder extra = new StringBuilder();
            if (!extraCheck.test(payload, extra)) {
                violations.add(extra.toString());
            }
        }
        return violations;
    }
}
