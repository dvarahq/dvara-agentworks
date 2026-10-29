package com.dvarahq.agentworks.evaluation;

import tools.jackson.databind.JsonNode;
import static com.dvarahq.agentworks.evaluation.EvaluationContracts.*;

/** Explicitly registered Java evaluator. Implementations are never loaded from user-supplied class names. */
public interface CustomEvaluator {
    String identity();
    Outcome evaluate(Case evaluationCase, Observation observation, JsonNode configuration);
}
