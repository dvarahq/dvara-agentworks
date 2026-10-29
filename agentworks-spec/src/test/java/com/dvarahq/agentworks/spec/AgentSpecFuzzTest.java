package com.dvarahq.agentworks.spec;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic, seeded fuzzing. Every mutated input must either produce a canonical spec whose canonical form is a
 * fixed point, or fail with typed diagnostics; no other exception type may escape the codec.
 */
class AgentSpecFuzzTest {
    private static final long SEED = 0x5EED_A6E7L;
    private static final int ITERATIONS = 3_000;
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final AgentSpecCodec codec = AgentSpecCodec.create();

    @Test
    void byteLevelMutationsNeverEscapeTypedValidation() throws IOException {
        SplittableRandom random = new SplittableRandom(SEED);
        byte[][] seeds = {
                AgentSpecGoldenVectorTest.resource("software-engineer.yaml"),
                AgentSpecGoldenVectorTest.resource("software-engineer.json"),
                AgentSpecGoldenVectorTest.resource("minimal-remote.yaml"),
                AgentSpecGoldenVectorTest.resource("minimal-remote.json")};
        int accepted = 0;
        int rejected = 0;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            int seedIndex = random.nextInt(seeds.length);
            byte[] mutated = mutateBytes(seeds[seedIndex], random);
            SpecFormat format = seedIndex % 2 == 0 ? SpecFormat.YAML : SpecFormat.JSON;
            if (exercise(mutated, format, iteration)) accepted++;
            else rejected++;
        }
        assertThat(rejected).as("mutations must exercise rejection paths").isGreaterThan(ITERATIONS / 4);
        assertThat(accepted).as("mutations must also exercise acceptance paths").isPositive();
    }

    @Test
    void structuralMutationsNeverEscapeTypedValidation() throws IOException {
        SplittableRandom random = new SplittableRandom(SEED + 1);
        JsonNode golden = MAPPER.readTree(AgentSpecGoldenVectorTest.resource("software-engineer.json"));
        List<String> pointers = new ArrayList<>();
        collectPointers(golden, "", pointers);
        int rejected = 0;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            ObjectNode document = (ObjectNode) golden.deepCopy();
            int mutations = 1 + random.nextInt(3);
            for (int count = 0; count < mutations; count++) {
                replace(document, pointers.get(random.nextInt(pointers.size())), randomValue(random, 0));
            }
            SpecFormat format = random.nextBoolean() ? SpecFormat.JSON : SpecFormat.YAML;
            byte[] bytes = format == SpecFormat.JSON ? MAPPER.writeValueAsBytes(document)
                    : toYaml(document).getBytes(StandardCharsets.UTF_8);
            if (!exercise(bytes, format, iteration)) rejected++;
        }
        assertThat(rejected).isGreaterThan(ITERATIONS / 2);
    }

    private boolean exercise(byte[] input, SpecFormat format, int iteration) {
        try {
            CanonicalAgentSpec result = codec.read(input, format);
            CanonicalAgentSpec reread = codec.read(result.canonicalBytes(), SpecFormat.JSON);
            assertThat(reread).as("iteration %d canonical fixed point", iteration).isEqualTo(result);
            assertThat(codec.canonicalize(result.spec())).isEqualTo(result);
            return true;
        } catch (AgentSpecValidationException exception) {
            assertThat(exception.diagnostics()).as("iteration %d", iteration).isNotEmpty()
                    .allSatisfy(diagnostic -> assertThat(diagnostic.path()).satisfiesAnyOf(
                            path -> assertThat(path).isEmpty(), path -> assertThat(path).startsWith("/")));
            return false;
        }
    }

    private static byte[] mutateBytes(byte[] source, SplittableRandom random) {
        byte[] result = source.clone();
        return switch (random.nextInt(5)) {
            case 0 -> { result[random.nextInt(result.length)] = (byte) random.nextInt(256); yield result; }
            case 1 -> java.util.Arrays.copyOf(result, random.nextInt(result.length));
            case 2 -> {
                int position = random.nextInt(result.length);
                byte[] tokens = "{}[]:,\"'&*!#-? \n\t0.e".getBytes(StandardCharsets.UTF_8);
                byte[] grown = new byte[result.length + 1];
                System.arraycopy(result, 0, grown, 0, position);
                grown[position] = tokens[random.nextInt(tokens.length)];
                System.arraycopy(result, position, grown, position + 1, result.length - position);
                yield grown;
            }
            case 3 -> {
                int position = random.nextInt(result.length);
                byte[] shrunk = new byte[result.length - 1];
                System.arraycopy(result, 0, shrunk, 0, position);
                System.arraycopy(result, position + 1, shrunk, position, result.length - position - 1);
                yield shrunk;
            }
            default -> result; // unmodified control input keeps the acceptance path exercised
        };
    }

    private static JsonNode randomValue(SplittableRandom random, int depth) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        return switch (random.nextInt(depth > 1 ? 7 : 9)) {
            case 0 -> nodes.nullNode();
            case 1 -> nodes.booleanNode(random.nextBoolean());
            case 2 -> nodes.numberNode(random.nextInt(-5, 200_000));
            case 3 -> nodes.numberNode(new java.math.BigDecimal(random.nextDouble(-2, 2)).setScale(random.nextInt(0, 8),
                    java.math.RoundingMode.HALF_UP));
            case 4 -> nodes.stringNode(List.of("", "a", "spring-ai", "30m", "8Gi", "ON_DEMAND", "hierarchical",
                    "Ω", "DROP TABLE", "../../etc", "1.0.0").get(random.nextInt(11)));
            case 5 -> nodes.stringNode("x".repeat(random.nextInt(0, 300)));
            case 6 -> nodes.numberNode(random.nextLong());
            case 7 -> {
                ArrayNode array = nodes.arrayNode();
                for (int index = random.nextInt(4); index > 0; index--) array.add(randomValue(random, depth + 1));
                yield array;
            }
            default -> {
                ObjectNode object = nodes.objectNode();
                for (int index = random.nextInt(4); index > 0; index--) {
                    object.set(List.of("type", "enabled", "gateway", "model", "environment", "extra")
                            .get(random.nextInt(6)), randomValue(random, depth + 1));
                }
                yield object;
            }
        };
    }

    private static void collectPointers(JsonNode node, String pointer, List<String> pointers) {
        if (!pointer.isEmpty()) pointers.add(pointer);
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                collectPointers(field.getValue(), pointer + "/" + field.getKey(), pointers);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) collectPointers(node.get(index), pointer + "/" + index, pointers);
        }
    }

    private static void replace(ObjectNode document, String pointer, JsonNode value) {
        int split = pointer.lastIndexOf('/');
        JsonNode parent = document.at(pointer.substring(0, split));
        String token = pointer.substring(split + 1);
        if (parent instanceof ObjectNode object) object.set(token, value);
        else if (parent instanceof ArrayNode array && token.chars().allMatch(Character::isDigit)
                && Integer.parseInt(token) < array.size()) {
            array.set(Integer.parseInt(token), value);
        }
        // Otherwise an earlier mutation removed this path; the pointer is skipped.
    }

    private static String toYaml(JsonNode document) {
        return tools.jackson.dataformat.yaml.YAMLMapper.builder().build().writeValueAsString(document);
    }
}
