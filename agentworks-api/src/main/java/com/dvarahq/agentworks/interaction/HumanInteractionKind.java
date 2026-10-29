package com.dvarahq.agentworks.interaction;

/** Closed Core interaction kinds. A run blocks for every kind without holding a worker lease. */
public enum HumanInteractionKind {
    APPROVAL, QUESTION, SELECTION, FORM, CONFIRMATION, AUTHORIZATION
}
