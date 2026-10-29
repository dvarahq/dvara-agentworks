package com.dvarahq.agentworks.testing;

import org.junit.jupiter.api.extension.ExtendWith;
import java.lang.annotation.*;

/** Declares the immutable agent identity and deterministic seed for a JUnit AgentTest class. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(AgentTestExtension.class)
public @interface AgentTest {
    String value();
    long seed() default 1L;
}
