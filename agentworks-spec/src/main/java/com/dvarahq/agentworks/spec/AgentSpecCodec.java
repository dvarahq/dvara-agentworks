package com.dvarahq.agentworks.spec;

import com.dvarahq.agentworks.spec.model.AgentSpec;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.snakeyaml.engine.v2.api.LoadSettings;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.core.exc.StreamConstraintsException;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLFactory;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Parses, validates, and canonicalizes AgentSpec documents. YAML, JSON, REST, and Java SDK values all pass through
 * the same pipeline: bounded parse, {@code apiVersion} gate, versioned JSON Schema, semantic rules, and canonical
 * serialization. Instances are immutable and thread-safe.
 */
public final class AgentSpecCodec {
    public static final int MAX_DOCUMENT_BYTES = 256 * 1024;
    static final int MAX_NESTING_DEPTH = 32;
    /** Same constraint as the schema's {@code $defs/name}; also used for AgentDefinition names. */
    public static final String AGENT_NAME_PATTERN = "^[a-z0-9]([-a-z0-9]*[a-z0-9])?$";
    private static final Pattern AGENT_NAME = Pattern.compile(AGENT_NAME_PATTERN);
    private static final BigDecimal MAX_NUMBER_MAGNITUDE = BigDecimal.TEN.pow(15);
    private static final int MAX_NUMBER_SCALE = 18;
    /** Every value with at most 15 significant digits survives a round trip through an IEEE-754 double client. */
    private static final int MAX_SIGNIFICANT_DIGITS = 15;

    private static final Map<String, String> SCHEMA_RESOURCES = Map.of(
            AgentSpec.API_VERSION_V1ALPHA1, "/com/dvarahq/agentworks/spec/schema/v1alpha1/agent.schema.json");
    private static final Set<String> LIFECYCLE_FIELDS = Set.of(
            "environment", "environments", "release", "releases", "deployment", "deployments", "promotion",
            "promotionPolicy", "placement", "exposure", "exposures", "alias", "aliases");
    private static final Set<String> TENANCY_FIELDS = Set.of(
            "organization", "organizationId", "workspace", "workspaceId", "tenant", "tenantId");

    private final ObjectMapper json;
    private final ObjectMapper yaml;
    private final Map<String, Schema> schemas;
    private final LoadSettings yamlSettings;

    private AgentSpecCodec() {
        StreamReadConstraints constraints = StreamReadConstraints.builder()
                .maxNestingDepth(MAX_NESTING_DEPTH)
                .maxStringLength(MAX_DOCUMENT_BYTES)
                .maxNumberLength(64)
                .maxNameLength(256)
                .maxDocumentLength(MAX_DOCUMENT_BYTES)
                .build();
        JsonInclude.Value nonNull = JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL);
        this.json = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(constraints).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .changeDefaultPropertyInclusion(ignored -> nonNull)
                .build();
        this.yamlSettings = LoadSettings.builder()
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setMaxAliasesForCollections(0)
                .setCodePointLimit(MAX_DOCUMENT_BYTES)
                .build();
        this.yaml = YAMLMapper.builder(YAMLFactory.builder().streamReadConstraints(constraints)
                        .loadSettings(yamlSettings).build())
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .changeDefaultPropertyInclusion(ignored -> nonNull)
                .build();
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Map<String, Schema> loaded = new TreeMap<>();
        SCHEMA_RESOURCES.forEach((apiVersion, resource) -> {
            JsonNode node = readResource(resource);
            loaded.put(apiVersion, registry.getSchema(SchemaLocation.of(node.get("$id").asString()), node));
        });
        this.schemas = Map.copyOf(loaded);
    }

    public static AgentSpecCodec create() {
        return new AgentSpecCodec();
    }

    /** API versions this installation accepts. Unknown versions are rejected, never interpreted. */
    public static Set<String> supportedApiVersions() {
        return SCHEMA_RESOURCES.keySet();
    }

    /** The published JSON Schema for {@code apiVersion}, as a UTF-8 resource stream. */
    public static InputStream schemaResource(String apiVersion) {
        String resource = SCHEMA_RESOURCES.get(apiVersion);
        if (resource == null) throw new IllegalArgumentException("unsupported apiVersion");
        return Objects.requireNonNull(AgentSpecCodec.class.getResourceAsStream(resource), resource);
    }

    public static boolean isValidAgentName(String name) {
        return name != null && name.length() <= 63 && AGENT_NAME.matcher(name).matches();
    }

    /** Parses and validates an authored YAML or JSON document. */
    public CanonicalAgentSpec read(byte[] content, SpecFormat format) {
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(format, "format");
        if (content.length > MAX_DOCUMENT_BYTES) {
            throw invalid(DiagnosticCode.DOCUMENT_TOO_LARGE, "",
                    "document exceeds " + MAX_DOCUMENT_BYTES + " bytes");
        }
        return fromTree(parseTree(content, format));
    }

    /** Validates and canonicalizes a Java SDK value through the same rules as authored documents. */
    public CanonicalAgentSpec canonicalize(AgentSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return fromTree(json.valueToTree(spec));
    }

    /** Canonical JSON for a valid specification. */
    public String toJson(AgentSpec spec) {
        return canonicalize(spec).canonicalJson();
    }

    /** YAML rendering of the canonical form; reading it back yields the same digest. */
    public String toYaml(AgentSpec spec) {
        JsonNode canonical = json.readTree(canonicalize(spec).canonicalJson());
        return yaml.writeValueAsString(canonical);
    }

    private JsonNode parseTree(byte[] content, SpecFormat format) {
        ObjectMapper mapper = format == SpecFormat.YAML ? yaml : json;
        if (format == SpecFormat.YAML) YamlStructureGuard.verify(content, yamlSettings);
        try {
            JsonNode tree = mapper.readTree(content);
            if (tree == null || tree.isMissingNode()) {
                throw invalid(DiagnosticCode.MALFORMED_DOCUMENT, "", "document is empty");
            }
            return tree;
        } catch (StreamConstraintsException exception) {
            throw invalid(DiagnosticCode.DOCUMENT_TOO_LARGE, "", "document exceeds structural bounds");
        } catch (JacksonException | IllegalArgumentException exception) {
            throw invalid(DiagnosticCode.MALFORMED_DOCUMENT, "", "document is not a single well-formed "
                    + format + " document");
        } catch (RuntimeException exception) {
            // snakeyaml-engine reports limit and composition failures with its own unchecked exception types.
            if (exception instanceof AgentSpecValidationException validation) throw validation;
            throw invalid(DiagnosticCode.MALFORMED_DOCUMENT, "", "document is not a single well-formed "
                    + format + " document");
        }
    }

    /**
     * Rejects numbers no AgentSpec field can hold and strings with unpaired UTF-16 surrogates, which cannot be
     * encoded as UTF-8 and would otherwise collide in the digest.
     */
    private static void checkValues(JsonNode node, String path) {
        if (node.isString() && !wellFormedUnicode(node.asString())) {
            throw invalid(DiagnosticCode.INVALID_VALUE, path, "must be well-formed Unicode");
        }
        if (node.isNumber()) {
            if (node.isFloatingPointNumber() && !node.isBigDecimal() && !Double.isFinite(node.doubleValue())) {
                throw invalid(DiagnosticCode.INVALID_VALUE, path, "must be a finite number");
            }
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            if (value.abs().compareTo(MAX_NUMBER_MAGNITUDE) > 0 || value.scale() > MAX_NUMBER_SCALE
                    || value.precision() > MAX_SIGNIFICANT_DIGITS) {
                throw invalid(DiagnosticCode.OUT_OF_RANGE, path, "number exceeds the supported magnitude or precision");
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!wellFormedUnicode(field.getKey())) {
                    throw invalid(DiagnosticCode.INVALID_VALUE, path, "field names must be well-formed Unicode");
                }
                checkValues(field.getValue(), SchemaDiagnostics.child(path, field.getKey()));
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                checkValues(node.get(index), path + "/" + index);
            }
        }
    }

    private static boolean wellFormedUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) return false;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private CanonicalAgentSpec fromTree(JsonNode tree) {
        if (!tree.isObject()) throw invalid(DiagnosticCode.TYPE_MISMATCH, "", "document root must be an object");
        checkValues(tree, "");
        JsonNode apiVersion = tree.get("apiVersion");
        if (apiVersion == null) {
            throw invalid(DiagnosticCode.REQUIRED_FIELD_MISSING, "/apiVersion", "required field is missing");
        }
        if (!apiVersion.isString()) throw invalid(DiagnosticCode.TYPE_MISMATCH, "/apiVersion", "expected string");
        Schema schema = schemas.get(apiVersion.asString());
        if (schema == null) {
            throw invalid(DiagnosticCode.UNSUPPORTED_API_VERSION, "/apiVersion",
                    "supported API versions are " + supportedApiVersions());
        }
        List<SpecDiagnostic> diagnostics = schema.validate(tree).stream()
                .map(error -> SchemaDiagnostics.map(error, LIFECYCLE_FIELDS, TENANCY_FIELDS))
                .toList();
        if (!diagnostics.isEmpty()) throw new AgentSpecValidationException(diagnostics);

        AgentSpec spec;
        try {
            spec = json.treeToValue(tree, AgentSpec.class);
        } catch (JacksonException exception) {
            throw invalid(DiagnosticCode.TYPE_MISMATCH, "", "document cannot be bound to AgentSpec");
        }
        diagnostics = SemanticRules.check(spec);
        if (!diagnostics.isEmpty()) throw new AgentSpecValidationException(diagnostics);

        JsonNode canonical = canonicalTree(json.valueToTree(spec));
        if (!canonical.equals(canonicalTree(tree))) {
            // Every schema-accepted field must survive binding; a difference means the model lost semantics.
            throw new IllegalStateException("AgentSpec binding lost authored content");
        }
        String canonicalJson = json.writeValueAsString(canonical);
        return new CanonicalAgentSpec(spec, canonicalJson, digest(canonicalJson));
    }

    /** {@code sha256:} digest over the UTF-8 bytes of canonical JSON. */
    public static String digest(String canonicalJson) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static JsonNode canonicalTree(JsonNode node) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        if (node.isObject()) {
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!field.getValue().isNull()) sorted.put(field.getKey(), canonicalTree(field.getValue()));
            }
            ObjectNode result = nodes.objectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.values().forEach(item -> items.add(canonicalTree(item)));
            return nodes.arrayNode().addAll(items);
        }
        if (node.isNumber()) {
            BigDecimal value = node.decimalValue().stripTrailingZeros();
            if (value.scale() <= 0) return nodes.numberNode(value.toBigIntegerExact());
            return nodes.numberNode(value);
        }
        return node;
    }

    private JsonNode readResource(String resource) {
        try (InputStream input = AgentSpecCodec.class.getResourceAsStream(resource)) {
            return json.readTree(Objects.requireNonNull(input, resource));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static AgentSpecValidationException invalid(DiagnosticCode code, String path, String message) {
        return new AgentSpecValidationException(List.of(SpecDiagnostic.at(code, path, message)));
    }
}
