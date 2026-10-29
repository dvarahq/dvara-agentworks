package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.storage.CompatibilityCheckingCallback;
import com.dvarahq.agentworks.storage.LockTimeoutRestoringCallback;
import com.dvarahq.agentworks.storage.RuntimeCompatibility;
import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Flyway callbacks of the server's migrations. Spring Boot's Flyway auto-configuration registers every
 * {@link org.flywaydb.core.api.callback.Callback} bean, so each migration's remaining work, including the
 * schema-history insert and, with {@code spring.flyway.group}, the later migrations of the group, keeps the session's
 * {@code lock_timeout} (#206).
 */
@AutoConfiguration(beforeName = "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration")
@ConditionalOnClass(Flyway.class)
public final class AgentWorksFlywayAutoConfiguration {

    @Bean
    CompatibilityCheckingCallback agentWorksCompatibilityCheckingCallback(Environment environment) {
        RuntimeCompatibility.requireJava(Runtime.version().feature(), System.getProperty("java.runtime.name"));
        boolean allowEmbedded = environment.getProperty("agentworks.compatibility.allow-embedded-database",
                Boolean.class, false) || AgentWorksLocalDevelopment.enabled(environment);
        return new CompatibilityCheckingCallback(allowEmbedded);
    }

    @Bean
    LockTimeoutRestoringCallback agentWorksLockTimeoutRestoringCallback() {
        return new LockTimeoutRestoringCallback();
    }
}
