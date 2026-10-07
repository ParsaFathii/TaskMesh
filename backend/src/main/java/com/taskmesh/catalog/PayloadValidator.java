package com.taskmesh.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON-schema subset validator used to check the <em>shape</em> of submitted
 * job payloads (required fields present, types correct). Deep value validation — e.g.
 * whether a base64 blob actually decodes — is the worker's responsibility per SPEC §9.
 *
 * <p>Supported keywords: {@code type}, {@code properties}, {@code required}, {@code enum},
 * {@code minimum}, {@code maximum}, {@code minLength}, {@code maxLength}, {@code items},
 * {@code minItems}, {@code maxItems}.</p>
 */
public final class PayloadValidator {

    private PayloadValidator() {
    }

    /**
     * Validates a decoded payload against a schema.
     *
     * @return violation descriptions (empty list when the payload conforms)
     */
    public static List<String> validate(Object payload, Map<String, Object> schema) {
        List<String> violations = new ArrayList<>();
        validate("$", payload, schema, violations);
        return violations;
    }

    @SuppressWarnings("unchecked")
    private static void validate(String path, Object value, Map<String, Object> schema,
                                 List<String> violations) {
        if (value == null) {
            if (Boolean.TRUE.equals(schema.get("required"))) {
                violations.add(path + ": must not be null");
            }
            return;
        }
        String type = String.valueOf(schema.getOrDefault("type", "any"));
        if (!typeMatches(value, type)) {
            violations.add(path + ": expected " + type + " but got " + jsonTypeName(value));
            return;
        }
        switch (type) {
            case "object" -> validateObject(path, (Map<String, Object>) value, schema, violations);
            case "array" -> validateArray(path, (List<Object>) value, schema, violations);
            case "string" -> validateString(path, (String) value, schema, violations);
            case "number", "integer" -> validateNumber(path, (Number) value, schema, violations);
            default -> {
                // "any", "boolean", "null" — nothing more to check.
            }
        }
        if (schema.containsKey("enum") && !enumContains(schema.get("enum"), value)) {
            violations.add(path + ": must be one of " + schema.get("enum"));
        }
    }

    private static void validateObject(String path, Map<String, Object> value, Map<String, Object> schema,
                                       List<String> violations) {
        Map<String, Object> properties = cast(schema.get("properties"));
        for (Object requiredEntry : listOf(schema.get("required"))) {
            String required = String.valueOf(requiredEntry);
            Object present = value.get(required);
            if (present == null) {
                violations.add(path + "." + required + ": required field is missing");
            }
        }
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            Map<String, Object> propertySchema = properties == null
                    ? null : cast(properties.get(entry.getKey()));
            if (propertySchema != null) {
                validate(path + "." + entry.getKey(), entry.getValue(), propertySchema, violations);
            }
        }
    }

    private static void validateArray(String path, List<Object> value, Map<String, Object> schema,
                                      List<String> violations) {
        int min = intOrNull(schema.get("minItems"), Integer.MIN_VALUE);
        int max = intOrNull(schema.get("maxItems"), Integer.MAX_VALUE);
        if (value.size() < min) {
            violations.add(path + ": needs at least " + min + " items");
        }
        if (value.size() > max) {
            violations.add(path + ": allows at most " + max + " items");
        }
        Map<String, Object> itemSchema = cast(schema.get("items"));
        if (itemSchema != null) {
            for (int i = 0; i < value.size(); i++) {
                validate(path + "[" + i + "]", value.get(i), itemSchema, violations);
            }
        }
    }

    private static void validateString(String path, String value, Map<String, Object> schema,
                                       List<String> violations) {
        int min = intOrNull(schema.get("minLength"), 0);
        int max = intOrNull(schema.get("maxLength"), Integer.MAX_VALUE);
        if (value.length() < min) {
            violations.add(path + ": must be at least " + min + " characters long");
        }
        if (value.length() > max) {
            violations.add(path + ": must be at most " + max + " characters long");
        }
    }

    private static void validateNumber(String path, Number value, Map<String, Object> schema,
                                       List<String> violations) {
        double v = value.doubleValue();
        Double min = doubleOrNull(schema.get("minimum"));
        Double max = doubleOrNull(schema.get("maximum"));
        if (min != null && v < min) {
            violations.add(path + ": must be >= " + min);
        }
        if (max != null && v > max) {
            violations.add(path + ": must be <= " + max);
        }
    }

    private static boolean typeMatches(Object value, String type) {
        return switch (type) {
            case "any" -> true;
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "string" -> value instanceof String;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Byte || value instanceof Short || value instanceof Integer
                    || value instanceof Long || (value instanceof Number n && isIntegral(n));
            case "number" -> value instanceof Number;
            case "null" -> value == null;
            default -> true;
        };
    }

    private static boolean isIntegral(Number n) {
        double d = n.doubleValue();
        return d == Math.rint(d) && !Double.isInfinite(d);
    }

    private static boolean enumContains(Object allowedValues, Object value) {
        if (allowedValues instanceof List<?> list) {
            return list.stream().anyMatch(candidate ->
                    candidate == value || (candidate != null && candidate.equals(value)));
        }
        return false;
    }

    private static String jsonTypeName(Object value) {
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Boolean) {
            return "boolean";
        }
        if (value instanceof Map) {
            return "object";
        }
        if (value instanceof List) {
            return "array";
        }
        if (value instanceof Number) {
            return "number";
        }
        return value.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    private static int intOrNull(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }

    private static Double doubleOrNull(Object o) {
        return o instanceof Number n ? n.doubleValue() : null;
    }
}
