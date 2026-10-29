package com.dvarahq.agentworks.capability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeterministicCapabilityResolverTest {
    private static final String SCHEMA = "{\"type\":\"object\"}";
    private static final String HASH = AgentTool.digest(SCHEMA);
    private static final ToolId SEARCH = new ToolId("core", "search", "1.0.0");
    private static final ToolId SHELL = new ToolId("core", "shell", "1.0.0");
    private static final ToolId ADMIN = new ToolId("core", "admin", "1.0.0");

    private final DeterministicCapabilityResolver resolver = new DeterministicCapabilityResolver();

    @Test
    void resolutionIsDeterministicRelevantAndSummaryFirst() {
        var request = request(Set.of("web"), Set.of(), Set.of("linux"), Set.of(SEARCH), 20, 20);

        CapabilityResolution first = resolver.resolve(request, packs(), List.of(tool(SHELL, "run commands", Set.of()),
                tool(SEARCH, "search the web", Set.of()), tool(ADMIN, "administer users", Set.of())));
        CapabilityResolution second = resolver.resolve(request, packs().reversed(),
                List.of(tool(ADMIN, "administer users", Set.of()), tool(SEARCH, "search the web", Set.of()),
                        tool(SHELL, "run commands", Set.of())));

        assertThat(first).isEqualTo(second);
        assertThat(first.resolverVersion()).isEqualTo("capability-resolver/v1");
        assertThat(decision(first, SEARCH).disclosure()).isEqualTo(CapabilityResolution.Disclosure.DETAIL);
        assertThat(decision(first, SHELL).disclosure()).isEqualTo(CapabilityResolution.Disclosure.SUMMARY);
        assertThat(decision(first, SHELL).reason()).isEqualTo(CapabilityResolution.Reason.NOT_TRIGGERED);
    }

    @Test
    void policyCompatibilityAndBudgetsExcludeBeforeModelContext() {
        AgentTool incompatible = tool(ADMIN, "administer users", Set.of("windows"));
        var request = request(Set.of(), Set.of(SHELL), Set.of("linux"), Set.of(SEARCH), 5, 4);

        CapabilityResolution result = resolver.resolve(request, packs(),
                List.of(tool(SEARCH, "search", Set.of()), tool(SHELL, "shell", Set.of()), incompatible));

        assertThat(decision(result, SHELL).reason()).isEqualTo(CapabilityResolution.Reason.POLICY_DENIED);
        assertThat(decision(result, ADMIN).reason()).isEqualTo(CapabilityResolution.Reason.INCOMPATIBLE);
        assertThat(decision(result, SEARCH).disclosure()).isEqualTo(CapabilityResolution.Disclosure.SUMMARY);
        assertThat(decision(result, SEARCH).reason()).isEqualTo(CapabilityResolution.Reason.DETAIL_BUDGET);
        assertThat(result.decisions()).noneMatch(decision -> decision.tool().equals(SHELL)
                && decision.disclosure() != CapabilityResolution.Disclosure.NONE);
    }

    @Test
    void summaryBudgetUsesRelevanceThenStableIdentityWithoutAToolCountThreshold() {
        ToolId other = new ToolId("core", "web-fetch", "1.0.0");
        var request = request(Set.of("web"), Set.of(), Set.of("linux"), Set.of(), 5, 0);

        CapabilityResolution result = resolver.resolve(request,
                List.of(new CapabilityPack("standard", "1", CapabilityPack.State.ACTIVE,
                        List.of(SHELL, other, SEARCH))),
                List.of(tool(SHELL, "shell", Set.of()), tool(other, "fetch web pages", Set.of()),
                        tool(SEARCH, "search web", Set.of())));

        assertThat(result.decisions().stream().filter(d -> d.disclosure() == CapabilityResolution.Disclosure.SUMMARY))
                .extracting(CapabilityResolution.Decision::tool).containsExactly(SEARCH);
        assertThat(decision(result, other).reason()).isEqualTo(CapabilityResolution.Reason.SUMMARY_BUDGET);
        assertThat(decision(result, SHELL).reason()).isEqualTo(CapabilityResolution.Reason.SUMMARY_BUDGET);
    }

    @Test
    void unavailableAndDeniedPacksAreExplainedWithoutDisclosingTheirTools() {
        CapabilityResolution result = resolver.resolve(new CapabilityResolutionRequest(
                        List.of("missing:1", "disabled:1", "denied:1"), Set.of(), Set.of(), Set.of(), Set.of(), 50, 50),
                List.of(new CapabilityPack("disabled", "1", CapabilityPack.State.DISABLED, List.of(SEARCH)),
                        new CapabilityPack("denied", "1", CapabilityPack.State.DENIED, List.of(SHELL))),
                List.of(tool(SEARCH, "search", Set.of()), tool(SHELL, "shell", Set.of())));

        assertThat(result.packs()).extracting(CapabilityResolution.PackDecision::reason)
                .containsExactly(CapabilityResolution.PackReason.DENIED, CapabilityResolution.PackReason.DISABLED,
                        CapabilityResolution.PackReason.UNAVAILABLE);
        assertThat(result.decisions()).isEmpty();
    }

    @Test
    void modelProjectionIncludesSchemasOnlyForDetailAndRejectsSchemaDrift() {
        AgentTool search = tool(SEARCH, "search", Set.of());
        AgentTool shell = tool(SHELL, "shell", Set.of());
        CapabilityResolution result = resolver.resolve(
                request(Set.of(), Set.of(), Set.of(), Set.of(SEARCH), 20, 20),
                List.of(new CapabilityPack("standard", "1", CapabilityPack.State.ACTIVE, List.of(SEARCH, SHELL))),
                List.of(search, shell));

        CapabilityDisclosure disclosure = CapabilityDisclosure.from(result,
                List.of(search.metadata(), shell.metadata()), List.of(search));

        assertThat(disclosure.summaries()).extracting(CapabilityDisclosure.Summary::id).containsExactly(SHELL);
        assertThat(disclosure.details()).extracting(CapabilityDisclosure.Detail::id).containsExactly(SEARCH);
        assertThat(disclosure.summaries().getFirst().toString()).doesNotContain(SCHEMA);
        AgentTool changed = new AgentTool(SEARCH, ToolType.JAVA, "search", "changed", "{\"type\":\"string\"}",
                AgentTool.digest("{\"type\":\"string\"}"), effect(), Set.of(), Set.of(), 5, 8,
                AgentTool.Availability.ACTIVE);
        assertThatThrownBy(() -> CapabilityDisclosure.from(result,
                List.of(search.metadata(), shell.metadata()), List.of(changed)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema changed");
    }

    @Test
    void malformedOrMismatchedSchemaDigestsFailClosed() {
        assertThatThrownBy(() -> new AgentTool(SEARCH, ToolType.JAVA, "search", "search", SCHEMA,
                "sha256:" + "0".repeat(64), effect(), Set.of(), Set.of(), 5, 8, AgentTool.Availability.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("schema hash mismatch");
    }

    private static CapabilityResolutionRequest request(Set<String> terms, Set<ToolId> denied,
            Set<String> compatibility, Set<ToolId> triggers, int summaries, int details) {
        return new CapabilityResolutionRequest(List.of("standard:1"), terms, denied, compatibility, triggers,
                summaries, details);
    }

    private static List<CapabilityPack> packs() {
        return List.of(new CapabilityPack("standard", "1", CapabilityPack.State.ACTIVE,
                List.of(SHELL, SEARCH, ADMIN)));
    }

    private static AgentTool tool(ToolId id, String summary, Set<String> compatibility) {
        return new AgentTool(id, ToolType.JAVA, summary, summary + " in detail", SCHEMA, HASH, effect(),
                Set.of(), compatibility, 5, 8, AgentTool.Availability.ACTIVE);
    }

    private static ToolEffect effect() {
        return new ToolEffect("test-resource", "read", com.dvarahq.agentworks.domain.security.PermissionRisk.LOW);
    }

    private static CapabilityResolution.Decision decision(CapabilityResolution result, ToolId id) {
        return result.decisions().stream().filter(decision -> decision.tool().equals(id)).findFirst().orElseThrow();
    }
}
