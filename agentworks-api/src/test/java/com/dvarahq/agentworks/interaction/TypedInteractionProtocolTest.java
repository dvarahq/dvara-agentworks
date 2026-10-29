package com.dvarahq.agentworks.interaction;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TypedInteractionProtocolTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final ContentMetadata META = new ContentMetadata("INTERNAL", "AUTHENTICATED", false, 10);

    @Test
    void closedVocabularyCoversEveryRequiredContentType() {
        DvaraUi ui = new DvaraUi("dvaraui/v1", new DvaraUi.Component(DvaraUi.Type.CONFIRMATION, "confirm",
                Map.of("title", "Proceed?"), List.of(new DvaraUi.Action("yes", "Proceed",
                        URI.create("https://example.test/actions/yes"), "POST"))));
        List<InteractionContent> content = List.of(
                new TextContent("hello", META),
                new ImageContent(URI.create("https://example.test/i.png"), "image/png", "image", DIGEST, META),
                new AudioContent(URI.create("https://example.test/a.ogg"), "audio/ogg", null, DIGEST, META),
                new FileContent(URI.create("artifact://files/1"), "report.txt", "text/plain", DIGEST, META),
                new StructuredContent("schema/v1", Map.of("answer", 42), META),
                new ToolRequestContent("call-1", "tools/search@1", Map.of("q", "safe"), META),
                new ToolResultContent("call-1", true, Map.of("count", 1), META),
                new ArtifactContent(UUID.randomUUID(), "application/pdf", DIGEST, META),
                new UiContent(ui, META),
                new ReferenceContent(URI.create("https://example.test/source"), "source", "citation", META));

        assertThat(content).extracting(InteractionContent::type).containsExactly(ContentType.values());
        assertThat(new TypedInteractionValidator(100, 1000).validate(content,
                Set.of("INTERNAL"), true)).containsExactlyElementsOf(content);
    }

    @Test
    void commonPolicyRejectsOversizeClassificationAndToolConfusion() {
        TypedInteractionValidator validator = new TypedInteractionValidator(10, 20);
        assertThatThrownBy(() -> validator.validate(List.of(new TextContent("x",
                new ContentMetadata("RESTRICTED", "UNTRUSTED", false, 11))), Set.of("RESTRICTED"), true))
                .hasMessageContaining("size policy");
        assertThatThrownBy(() -> validator.validate(List.of(new TextContent("x", META)), Set.of("PUBLIC"), true))
                .hasMessageContaining("classification");
        assertThatThrownBy(() -> validator.validate(List.of(new ToolRequestContent("c", "t", Map.of(), META)),
                Set.of("INTERNAL"), false)).hasMessageContaining("tool content");
    }

    @Test
    void uiForbidsExecutablePropertiesAndUnknownContentDegradesToInertText() {
        assertThatThrownBy(() -> new DvaraUi.Component(DvaraUi.Type.FORM, "form", Map.of("onClick", "steal()"),
                List.of())).hasMessageContaining("executable");
        assertThatThrownBy(() -> new DvaraUi.Component(DvaraUi.Type.FORM, "form",
                Map.of("layout", Map.of("script", "steal()")), List.of())).hasMessageContaining("executable");
        assertThatThrownBy(() -> new DvaraUi.Action("x", "x", URI.create("javascript:alert(1)"), "GET"))
                .hasMessageContaining("unsafe");
        TextContent degraded = TypedInteractionValidator.degradeUnknown("future-widget");
        assertThat(degraded.type()).isEqualTo(ContentType.TEXT);
        assertThat(degraded.metadata().redacted()).isTrue();
        assertThat(degraded.text()).contains("future-widget");
    }

    @Test
    void structuredValuesAreDeeplyImmutableAndJsonOnly() {
        java.util.ArrayList<Object> source = new java.util.ArrayList<>(List.of("before"));
        StructuredContent content = new StructuredContent("schema/v1", Map.of("nested", source), META);
        source.set(0, "after");

        assertThat(content.value().get("nested")).isEqualTo(List.of("before"));
        assertThatThrownBy(() -> new StructuredContent("schema/v1", Map.of("unsafe", new Object()), META))
                .hasMessageContaining("JSON");
    }
}
