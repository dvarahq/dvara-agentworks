package com.dvarahq.agentworks.runplane.execution;

import com.dvarahq.agentworks.domain.run.RunBlockReason;
import com.dvarahq.agentworks.interaction.HumanInteractionDraft;

import java.util.Objects;

/** How a claimed continuation ended; blocked and paused runs release their worker. */
public sealed interface RunOutcome {
    record Completed(Object output) implements RunOutcome {}

    record Blocked(RunBlockReason reason, Object detail) implements RunOutcome {
        public Blocked {
            Objects.requireNonNull(reason, "reason");
        }
    }

    record Interaction(HumanInteractionDraft draft) implements RunOutcome {
        public Interaction {
            Objects.requireNonNull(draft, "draft");
        }
    }

    record Paused(String reason) implements RunOutcome {}

    record Failed(String code, String message) implements RunOutcome {
        public Failed {
            Objects.requireNonNull(code, "code");
        }
    }

    /** The handler already atomically blocked the run through a specialized durable coordinator. */
    record Delegated(Object detail) implements RunOutcome {}

    static RunOutcome completed(Object output) {
        return new Completed(output);
    }

    static RunOutcome blocked(RunBlockReason reason, Object detail) {
        return new Blocked(reason, detail);
    }

    static RunOutcome interaction(HumanInteractionDraft draft) {
        return new Interaction(draft);
    }

    static RunOutcome paused(String reason) {
        return new Paused(reason);
    }

    static RunOutcome failed(String code, String message) {
        return new Failed(code, message);
    }

    static RunOutcome delegated(Object detail) { return new Delegated(detail); }
}
