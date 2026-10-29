package com.dvarahq.agentworks.spec;

import com.networknt.schema.Error;
import com.networknt.schema.path.NodePath;
import tools.jackson.databind.JsonNode;

import java.util.Set;

/** Maps JSON Schema errors to typed diagnostics without echoing authored values. */
final class SchemaDiagnostics {
    private SchemaDiagnostics() {}

    static SpecDiagnostic map(Error error, Set<String> lifecycleFields, Set<String> tenancyFields) {
        String location = pointer(error.getInstanceLocation());
        JsonNode schemaValue = error.getSchemaNode();
        return switch (error.getKeyword()) {
            case "required" -> SpecDiagnostic.at(DiagnosticCode.REQUIRED_FIELD_MISSING,
                    child(location, error.getProperty()), "required field is missing");
            case "additionalProperties" -> unknownField(child(location, error.getProperty()), error.getProperty(),
                    lifecycleFields, tenancyFields);
            case "type" -> SpecDiagnostic.at(DiagnosticCode.TYPE_MISMATCH, location, "expected " + text(schemaValue));
            case "const" -> location.equals("/kind")
                    ? SpecDiagnostic.at(DiagnosticCode.UNSUPPORTED_KIND, location, "kind must be Agent")
                    : SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE, location, "must equal " + text(schemaValue));
            case "enum" -> SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE, location, "must be one of " + text(schemaValue));
            case "pattern" -> SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE, location,
                    "must match pattern " + text(schemaValue));
            case "uniqueItems" -> SpecDiagnostic.at(DiagnosticCode.DUPLICATE_ENTRY, location, "entries must be unique");
            case "minimum" -> range(location, "must be >= ", schemaValue);
            case "exclusiveMinimum" -> range(location, "must be > ", schemaValue);
            case "maximum" -> range(location, "must be <= ", schemaValue);
            case "exclusiveMaximum" -> range(location, "must be < ", schemaValue);
            case "minLength" -> range(location, "length must be >= ", schemaValue);
            case "maxLength" -> range(location, "length must be <= ", schemaValue);
            case "minItems" -> range(location, "item count must be >= ", schemaValue);
            case "maxItems" -> range(location, "item count must be <= ", schemaValue);
            case "minProperties" -> range(location, "entry count must be >= ", schemaValue);
            case "maxProperties" -> range(location, "entry count must be <= ", schemaValue);
            default -> SpecDiagnostic.at(DiagnosticCode.INVALID_VALUE, location,
                    "violates schema keyword " + error.getKeyword());
        };
    }

    private static SpecDiagnostic unknownField(String path, String name, Set<String> lifecycleFields,
            Set<String> tenancyFields) {
        if (lifecycleFields.contains(name)) {
            return SpecDiagnostic.at(DiagnosticCode.FORBIDDEN_FIELD, path,
                    "environment, release, deployment, promotion, placement, and exposure are separate artifacts");
        }
        if (tenancyFields.contains(name)) {
            return SpecDiagnostic.at(DiagnosticCode.FORBIDDEN_FIELD, path,
                    "tenancy is bound by the authenticated workspace, not by document content");
        }
        return SpecDiagnostic.at(DiagnosticCode.UNKNOWN_FIELD, path, "field is not defined by the declared apiVersion");
    }

    private static SpecDiagnostic range(String location, String prefix, JsonNode bound) {
        return SpecDiagnostic.at(DiagnosticCode.OUT_OF_RANGE, location, prefix + text(bound));
    }

    private static String text(JsonNode node) {
        if (node == null) return "the schema constraint";
        return node.isString() ? node.asString() : node.toString();
    }

    static String pointer(NodePath path) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < path.getNameCount(); index++) {
            result.append('/').append(escape(String.valueOf(path.getElement(index))));
        }
        return result.toString();
    }

    static String child(String location, String name) {
        return location + "/" + escape(name == null ? "" : name);
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }
}
