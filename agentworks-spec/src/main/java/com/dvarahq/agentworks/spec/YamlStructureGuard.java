package com.dvarahq.agentworks.spec;

import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Restricts authored YAML to one document of plain mappings, sequences, and core-schema scalars. Anchors, aliases,
 * and explicit tags are rejected because the tree parser would otherwise reinterpret them instead of failing.
 */
final class YamlStructureGuard {
    /** YAML 1.2 core-schema null spellings; omit a field instead of nulling it. */
    private static final Set<String> NULL_SCALARS = Set.of("", "~", "null", "Null", "NULL");

    private YamlStructureGuard() {}

    static void verify(byte[] content, LoadSettings settings) {
        int documents = 0;
        try {
            for (Event event : new Parse(settings).parseInputStream(new ByteArrayInputStream(content))) {
                switch (event.getEventId()) {
                    case Alias -> throw rejected("YAML aliases are not supported");
                    case DocumentStart -> {
                        if (++documents > 1) throw rejected("exactly one YAML document is required");
                    }
                    case Scalar -> {
                        ScalarEvent scalar = (ScalarEvent) event;
                        verifyNode(scalar, scalar.getTag());
                        if (scalar.isPlain() && NULL_SCALARS.contains(scalar.getValue())) {
                            throw rejected("YAML null values are not supported; omit the field instead");
                        }
                    }
                    case MappingStart, SequenceStart ->
                            verifyNode((NodeEvent) event, ((CollectionStartEvent) event).getTag());
                    default -> { }
                }
            }
        } catch (AgentSpecValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw rejected("document is not a single well-formed YAML document");
        }
    }

    private static void verifyNode(NodeEvent event, Optional<String> tag) {
        if (event.getAnchor().isPresent()) throw rejected("YAML anchors are not supported");
        if (tag.isPresent()) throw rejected("explicit YAML tags are not supported");
    }

    private static AgentSpecValidationException rejected(String message) {
        return new AgentSpecValidationException(List.of(
                SpecDiagnostic.at(DiagnosticCode.MALFORMED_DOCUMENT, "", message)));
    }
}
