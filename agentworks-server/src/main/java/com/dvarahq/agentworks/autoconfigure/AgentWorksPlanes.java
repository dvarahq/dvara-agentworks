package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.storage.JdbcAgentDefinitionRepository;
import com.dvarahq.agentworks.storage.TenantTransactionTemplate;
import com.dvarahq.agentworks.storage.lifecycle.DatabaseClock;
import com.dvarahq.agentworks.storage.lifecycle.JdbcIdempotencyRepository;
import com.dvarahq.agentworks.storage.lifecycle.JdbcLifecycleRepository;
import com.dvarahq.agentworks.storage.lifecycle.JdbcRunAdmissionRepository;
import com.dvarahq.agentworks.storage.run.JdbcDurableRunStore;
import com.dvarahq.agentworks.storage.sandbox.JdbcSandboxLifecycleAuthority;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * Control Plane and Run Plane connections (ADR-002, ADR-012). When {@code agentworks.planes.control-plane.url}
 * and {@code agentworks.planes.run-plane.url} (with {@code username} and {@code password}) are set, each plane gets
 * its own pool and transaction manager under its own login role, typically members of
 * {@code agentworks_control_plane} and {@code agentworks_run_plane}; Flyway keeps the primary (owner) connection.
 * Without them both planes share the primary data source. Configuring only one plane, or a URL without a username,
 * fails at startup instead of silently falling back to the owner connection. Row-level security and provisioning
 * are #59.
 */
public final class AgentWorksPlanes implements AutoCloseable {
    public record Plane(String name, DataSource dataSource, boolean dedicated, TransactionTemplate transactions,
            TenantTransactionTemplate tenants, JdbcTemplate jdbc) {
        public JdbcLifecycleRepository lifecycle() {
            return new JdbcLifecycleRepository(jdbc);
        }

        public JdbcIdempotencyRepository idempotency() {
            return new JdbcIdempotencyRepository(jdbc);
        }

        public JdbcRunAdmissionRepository runs() {
            return new JdbcRunAdmissionRepository(jdbc);
        }

        public JdbcAgentDefinitionRepository agents() {
            return new JdbcAgentDefinitionRepository(jdbc);
        }

        public DatabaseClock clock() {
            return new DatabaseClock(jdbc);
        }

        /** The claim quarantine's listing and release (#181), which belong on the Control Plane's connection. */
        public com.dvarahq.agentworks.storage.run.JdbcClaimQuarantineRepository claimQuarantines() {
            return new com.dvarahq.agentworks.storage.run.JdbcClaimQuarantineRepository(jdbc, transactions);
        }

        /** Durable run history on this plane's connection; it joins the plane's admission transactions. */
        public JdbcDurableRunStore runStore() {
            return new JdbcDurableRunStore(jdbc, transactions);
        }

        /** Durable sandbox lifecycle authority; callers must obtain it from the Run Plane. */
        public JdbcSandboxLifecycleAuthority sandboxes() {
            return new JdbcSandboxLifecycleAuthority(jdbc, transactions);
        }
    }

    private final Plane control;
    private final Plane run;

    public AgentWorksPlanes(Plane control, Plane run) {
        this.control = Objects.requireNonNull(control, "control");
        this.run = Objects.requireNonNull(run, "run");
    }

    static AgentWorksPlanes from(Environment environment, DataSource primary, TransactionTemplate primaryTransactions) {
        boolean control = environment.containsProperty("agentworks.planes.control-plane.url");
        boolean run = environment.containsProperty("agentworks.planes.run-plane.url");
        if (control != run) {
            throw new IllegalStateException("configure both agentworks.planes.control-plane and agentworks.planes.run-plane "
                    + "or neither; one plane must not fall back to the owner connection");
        }
        return new AgentWorksPlanes(plane("control-plane", environment, primary, primaryTransactions),
                plane("run-plane", environment, primary, primaryTransactions));
    }

    public static Plane shared(String name, DataSource dataSource, TransactionTemplate transactions) {
        AgentWorksJdbcTransactionContract.require(dataSource, transactions);
        return new Plane(name, dataSource, false, transactions, new TenantTransactionTemplate(transactions),
                new JdbcTemplate(dataSource));
    }

    public static Plane dedicated(String name, DataSource dataSource) {
        TransactionTemplate transactions = new TransactionTemplate(new JdbcTransactionManager(dataSource));
        return new Plane(name, dataSource, true, transactions, new TenantTransactionTemplate(transactions),
                new JdbcTemplate(dataSource));
    }

    public Plane control() {
        return control;
    }

    public Plane run() {
        return run;
    }

    private static Plane plane(String name, Environment environment, DataSource primary, TransactionTemplate transactions) {
        String prefix = "agentworks.planes." + name + ".";
        String url = environment.getProperty(prefix + "url");
        if (url == null) return shared(name, primary, transactions);
        String username = environment.getProperty(prefix + "username");
        if (username == null || username.isBlank()) {
            throw new IllegalStateException(prefix + "username is required when " + prefix + "url is set");
        }
        DataSource dataSource = DataSourceBuilder.create().url(url).username(username)
                .password(environment.getProperty(prefix + "password")).build();
        return dedicated(name, dataSource);
    }

    @Override
    public void close() throws Exception {
        for (Plane plane : new Plane[]{control, run}) {
            if (plane.dedicated() && plane.dataSource() instanceof AutoCloseable closeable) closeable.close();
        }
    }
}
