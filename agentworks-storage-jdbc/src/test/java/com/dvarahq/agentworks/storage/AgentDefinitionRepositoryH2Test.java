package com.dvarahq.agentworks.storage;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

class AgentDefinitionRepositoryH2Test extends AgentDefinitionRepositoryContract {
    @Override
    protected DataSource dataSource() {
        var source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).locations("classpath:db/migration/common").load().migrate();
        return source;
    }
}
