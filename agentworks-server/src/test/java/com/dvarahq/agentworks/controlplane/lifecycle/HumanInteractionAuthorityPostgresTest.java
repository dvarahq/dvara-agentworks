package com.dvarahq.agentworks.controlplane.lifecycle;

import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.CohortCommand;
import com.dvarahq.agentworks.controlplane.lifecycle.LifecycleModel.RetirementResult;
import com.dvarahq.agentworks.domain.lifecycle.state.LifecycleRows.RunRow;
import com.dvarahq.agentworks.domain.security.CredentialRef;
import com.dvarahq.agentworks.domain.security.InvocationIdentity;
import com.dvarahq.agentworks.domain.tenant.WorkspaceId;
import com.dvarahq.agentworks.interaction.HumanInteractionAssignment;
import com.dvarahq.agentworks.interaction.HumanInteractionAuthorityException;
import com.dvarahq.agentworks.interaction.HumanInteractionDraft;
import com.dvarahq.agentworks.interaction.HumanInteractionPrompt;
import com.dvarahq.agentworks.interaction.HumanInteractionRecord;
import com.dvarahq.agentworks.interaction.HumanInteractionResponse;
import com.dvarahq.agentworks.interaction.HumanInteractionStatus;
import com.dvarahq.agentworks.runplane.admission.RunAdmissionService;
import com.dvarahq.agentworks.storage.interaction.JdbcHumanInteractionAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.dvarahq.agentworks.controlplane.lifecycle.PromotionLifecyclePostgresTest.race;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PostgreSQL authority, restart, tenant, deadline, idempotency, outcome-race, and retirement evidence for #42. */
class HumanInteractionAuthorityPostgresTest {
    private LifecycleFixture fixture;
    private JdbcHumanInteractionAuthority authority;
    private WorkspaceId workspace;
    private RunRow run;
    private UUID user;
    private InvocationIdentity identity;

    @BeforeEach
    void setUp() {
        fixture = LifecycleFixture.postgres();
        authority = new JdbcHumanInteractionAuthority(fixture.jdbc, fixture.transactions);
        workspace = fixture.workspace();
        var environment = fixture.environment(workspace, "prod");
        var promotion = fixture.promote(workspace, environment, "api",
                fixture.release(workspace, "agent", "1.0.0"));
        run = fixture.admissions.admitRoot(workspace, promotion.deploymentId(), null, "{}",
                RunAdmissionService.Options.SUBMIT).run();
        user = UUID.randomUUID();
        identity = new InvocationIdentity(UUID.randomUUID(), workspace.value(), user, UUID.randomUUID(),
                UUID.randomUUID());
    }

    @Test
    void allSixKindsSurviveRestartWithDatabaseTimePinsAndOneLedgerRowEach() {
        List<HumanInteractionPrompt> prompts = List.of(
                new HumanInteractionPrompt.Approval("Approve", "Proceed?", "Changes production"),
                new HumanInteractionPrompt.Question("Question", "Name?", "{\"type\":\"string\"}"),
                new HumanInteractionPrompt.Selection("Select", "Region?", List.of(
                        new HumanInteractionPrompt.Option("us", "US"),
                        new HumanInteractionPrompt.Option("eu", "EU")), 1, 1),
                new HumanInteractionPrompt.Form("Form", "Complete", "{\"type\":\"object\"}"),
                new HumanInteractionPrompt.Confirmation("Confirm", "Continue?", "Cannot be undone"),
                new HumanInteractionPrompt.Authorization("Authorize", "Connect",
                        new CredentialRef("env://calendar-token"), Set.of("calendar.read")));
        Instant before = fixture.clock.now();
        for (int index = 0; index < prompts.size(); index++) {
            HumanInteractionRecord created = authority.create(workspace,
                    draft(prompts.get(index), "kind-" + index, Duration.ofMinutes(15)));
            assertThat(created.request().createdAt()).isBetween(before, fixture.clock.now());
            assertThat(Duration.between(created.request().createdAt(), created.request().deadline()))
                    .isEqualTo(Duration.ofMinutes(15));
            assertThat(created.request().prompt()).isEqualTo(prompts.get(index));
            assertThat(created.status()).isEqualTo(HumanInteractionStatus.OPEN);
        }

        var restarted = new JdbcHumanInteractionAuthority(fixture.jdbc, fixture.transactions);
        assertThat(fixture.jdbc.queryForObject("select count(*) from human_interaction where workspace_id = ?",
                Integer.class, workspace.value())).isEqualTo(6);
        assertThat(fixture.jdbc.queryForObject("select count(*) from cohort_work_item where workspace_id = ? "
                + "and category = 'INTERACTION' and state = 'PENDING'", Integer.class, workspace.value())).isEqualTo(6);
        assertThat(restarted.find(workspace, authority.create(workspace,
                draft(prompts.getFirst(), "restart", Duration.ofMinutes(15))).request().id())).isPresent();
    }

    @Test
    void responderDeadlineAndOutcomeRacesHaveExactlyOneDurableWinner() throws Exception {
        HumanInteractionRecord created = authority.create(workspace,
                draft(new HumanInteractionPrompt.Approval("Approve", "Proceed?", "Production"), "race",
                        Duration.ofMinutes(5)));
        assertThatThrownBy(() -> authority.respond(workspace, created.request().id(),
                new InvocationIdentity(UUID.randomUUID(), workspace.value(), UUID.randomUUID(), identity.sessionId(),
                        identity.delegatedSubjectId()),
                new HumanInteractionResponse.Approval(true), "user:wrong"))
                .isInstanceOfSatisfying(HumanInteractionAuthorityException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(HumanInteractionAuthorityException.Code.UNAUTHORIZED_RESPONDER));
        assertThatThrownBy(() -> authority.expire(workspace, created.request().id(), "system:expiry"))
                .isInstanceOfSatisfying(HumanInteractionAuthorityException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(HumanInteractionAuthorityException.Code.DEADLINE_NOT_REACHED));

        List<Object> outcomes = race(2, index -> {
            try {
                return authority.respond(workspace, created.request().id(), identity,
                        new HumanInteractionResponse.Approval(index == 0), "user:" + user);
            } catch (RuntimeException failure) {
                return failure;
            }
        });
        assertThat(outcomes.stream().filter(HumanInteractionRecord.class::isInstance)).hasSize(1);
        assertThat(outcomes.stream().filter(HumanInteractionAuthorityException.class::isInstance)).hasSize(1);
        HumanInteractionRecord stored = authority.find(workspace, created.request().id()).orElseThrow();
        assertThat(stored.status()).isEqualTo(HumanInteractionStatus.RESPONDED);
        assertThat(fixture.jdbc.queryForObject("select count(*) from cohort_work_item where subject_id = ? "
                + "and state = 'TERMINAL'", Integer.class, created.request().id())).isOne();

        HumanInteractionRecord cancellationRace = authority.create(workspace,
                draft(new HumanInteractionPrompt.Confirmation("Confirm", "Continue?", "Production"),
                        "cancel-race", Duration.ofMinutes(5)));
        List<Object> cancelOutcomes = race(2, index -> {
            try {
                return index == 0
                        ? authority.respond(workspace, cancellationRace.request().id(), identity,
                                new HumanInteractionResponse.Confirmation(true), "user:" + user)
                        : authority.cancel(workspace, cancellationRace.request().id(), "operator:cancel");
            } catch (RuntimeException failure) {
                return failure;
            }
        });
        assertThat(cancelOutcomes.stream().filter(HumanInteractionRecord.class::isInstance)).hasSize(1);
        assertThat(cancelOutcomes.stream().filter(HumanInteractionAuthorityException.class::isInstance)).hasSize(1);
        assertThat(authority.find(workspace, cancellationRace.request().id()).orElseThrow().status())
                .isIn(HumanInteractionStatus.RESPONDED, HumanInteractionStatus.CANCELLED);
    }

    @Test
    void databaseExpiryWinsAndEveryLateResponseIsRejected() {
        HumanInteractionRecord created = authority.create(workspace,
                draft(new HumanInteractionPrompt.Question("Question", "Answer?", "{\"type\":\"string\"}"),
                        "expiry", Duration.ofMillis(1)));
        fixture.jdbc.execute("select pg_sleep(0.01)");

        HumanInteractionRecord expired = authority.expire(workspace, created.request().id(), "system:expiry");
        assertThat(expired.status()).isEqualTo(HumanInteractionStatus.EXPIRED);
        assertThat(expired.resolvedAt()).hasValueSatisfying(
                resolved -> assertThat(resolved).isAfterOrEqualTo(expired.request().deadline()));
        assertThatThrownBy(() -> authority.respond(workspace, created.request().id(), identity,
                new HumanInteractionResponse.Answer("late"), "user:" + user))
                .isInstanceOfSatisfying(HumanInteractionAuthorityException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(HumanInteractionAuthorityException.Code.ALREADY_TERMINAL));
    }

    @Test
    void idempotencyAndTenantScopeFailClosedWithoutAnotherInteraction() {
        HumanInteractionDraft original = draft(new HumanInteractionPrompt.Confirmation(
                "Confirm", "Continue?", "May charge"), "idem", Duration.ofMinutes(5));
        HumanInteractionRecord first = authority.create(workspace, original);
        assertThat(authority.create(workspace, original)).isEqualTo(first);

        HumanInteractionDraft mismatch = new HumanInteractionDraft(UUID.randomUUID(), original.workspaceId(),
                original.runId(), original.releaseId(), original.deploymentId(), original.revisionId(),
                original.cohortId(), original.correlationId(), original.idempotencyKey(),
                new HumanInteractionPrompt.Confirmation("Different", "Continue?", "May charge"),
                original.assignment(), original.timeout());
        assertThatThrownBy(() -> authority.create(workspace, mismatch))
                .isInstanceOfSatisfying(HumanInteractionAuthorityException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(HumanInteractionAuthorityException.Code.IDEMPOTENCY_CONFLICT));
        WorkspaceId other = fixture.workspace();
        assertThat(authority.find(other, first.request().id())).isEmpty();
        assertThat(fixture.jdbc.queryForObject("select count(*) from human_interaction where workspace_id = ?",
                Integer.class, workspace.value())).isOne();
    }

    @Test
    void databaseGuardsAreDedicatedAndInteractionHistoryCannotBeRewritten() {
        HumanInteractionRecord created = authority.create(workspace,
                draft(new HumanInteractionPrompt.Confirmation("Confirm", "Continue?", "Production"),
                        "guard", Duration.ofMinutes(5)));
        for (String guard : List.of("agentworks_guard_human_interaction",
                "agentworks_sync_human_interaction_work")) {
            var row = fixture.jdbc.queryForMap("select pg_get_userbyid(p.proowner) as owner, "
                    + "p.prosecdef as definer, array_to_string(p.proconfig, ',') as config, "
                    + "has_function_privilege('agentworks_run_plane', p.oid, 'EXECUTE') as run_plane, "
                    + "has_function_privilege('agentworks_control_plane', p.oid, 'EXECUTE') as control_plane "
                    + "from pg_proc p where p.proname = ?", guard);
            assertThat(row.get("owner")).as(guard).isEqualTo("agentworks_fence_owner");
            assertThat(row.get("definer")).as(guard).isEqualTo(true);
            assertThat((String) row.get("config")).as(guard).startsWith("search_path=").endsWith("pg_temp");
            assertThat(row.get("run_plane")).as(guard).isEqualTo(false);
            assertThat(row.get("control_plane")).as(guard).isEqualTo(false);
        }
        assertThatThrownBy(() -> fixture.jdbc.update("update human_interaction set deadline = deadline "
                + "+ interval '1 minute' where workspace_id = ? and id = ?", workspace.value(), created.request().id()))
                .hasStackTraceContaining("IMMUTABLE_IDENTITY");
        assertThatThrownBy(() -> fixture.jdbc.update("delete from human_interaction where workspace_id = ? and id = ?",
                workspace.value(), created.request().id())).hasStackTraceContaining("IMMUTABLE_HISTORY");
        assertThatThrownBy(() -> fixture.jdbc.execute("truncate human_interaction"))
                .hasStackTraceContaining("IMMUTABLE_HISTORY");
    }

    @Test
    void interactionFenceAndRetirementRaceNeverResurrectThePinnedCohort() throws Exception {
        HumanInteractionRecord created = authority.create(workspace,
                draft(new HumanInteractionPrompt.Approval("Approve", "Proceed?", "Production"), "retirement",
                        Duration.ofMinutes(5)));
        fixture.promote(workspace, fixture.environments.get(workspace, run.environmentId()), "api",
                fixture.release(workspace, "agent", "2.0.0"));

        long initialVersion = fixture.deployments.cohort(workspace, run.cohortId()).transitionVersion();
        RetirementResult blocked = fixture.deployments.retire(workspace,
                new CohortCommand(run.cohortId(), initialVersion, null, "tester", null)).value();
        assertThat(blocked.outcome()).isEqualTo("BLOCKED");
        assertThat(blocked.blockers()).extracting(LifecycleModel.Blocker::category)
                .contains("PENDING_INTERACTION");

        // Isolate the interaction as the only remaining resumable work before racing its outcome with retirement.
        fixture.engine.cancel(workspace, run.id(), "tester", "interaction race");
        List<Object> outcomes = race(2, index -> {
            if (index == 0) {
                try {
                    return authority.respond(workspace, created.request().id(), identity,
                            new HumanInteractionResponse.Approval(true), "user:" + user);
                } catch (RuntimeException failure) {
                    return failure;
                }
            }
            long version = fixture.deployments.cohort(workspace, run.cohortId()).transitionVersion();
            return fixture.deployments.retire(workspace,
                    new CohortCommand(run.cohortId(), version, null, "tester", null)).value();
        });
        RetirementResult retirement = (RetirementResult) outcomes.get(1);
        if ("BLOCKED".equals(retirement.outcome())) {
            assertThat(outcomes.getFirst()).isInstanceOf(HumanInteractionRecord.class);
            long version = fixture.deployments.cohort(workspace, run.cohortId()).transitionVersion();
            assertThat(fixture.deployments.retire(workspace,
                    new CohortCommand(run.cohortId(), version, null, "tester", null)).value().outcome())
                    .isEqualTo("RETIRED");
        } else {
            assertThat(retirement.outcome()).isEqualTo("RETIRED");
            assertThat(outcomes.getFirst()).isInstanceOfAny(HumanInteractionRecord.class,
                    HumanInteractionAuthorityException.class);
        }
        assertThat(authority.find(workspace, created.request().id()).orElseThrow().status().terminal()).isTrue();
        assertThatThrownBy(() -> authority.create(workspace,
                draft(new HumanInteractionPrompt.Approval("Late", "Proceed?", "Production"), "late",
                        Duration.ofMinutes(5))))
                .isInstanceOfSatisfying(HumanInteractionAuthorityException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo(HumanInteractionAuthorityException.Code.COHORT_NOT_ADMITTING));
    }

    private HumanInteractionDraft draft(HumanInteractionPrompt prompt, String key, Duration timeout) {
        return new HumanInteractionDraft(UUID.randomUUID(), workspace.value(), run.id(), run.releaseId(),
                run.deploymentId(), run.revisionId(), run.cohortId(), "corr-" + key, "idem-" + key, prompt,
                HumanInteractionAssignment.user(workspace.value(), user), timeout);
    }
}
