package com.dvarahq.agentworks.autoconfigure;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.Objects;

/** The transaction boundary supported by the JDBC-backed AgentWorks server. */
final class AgentWorksJdbcTransactionContract {
    private AgentWorksJdbcTransactionContract() {}

    static void require(DataSource dataSource, TransactionTemplate transactions) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(transactions, "transactions");
        var manager = Objects.requireNonNull(transactions.getTransactionManager(), "transactionManager");
        if (!(manager instanceof DataSourceTransactionManager jdbc)) {
            throw new IllegalStateException("AgentWorks JDBC requires DataSourceTransactionManager or "
                    + "JdbcTransactionManager; JPA and JTA transaction managers are not supported");
        }
        if (jdbc.getDataSource() != dataSource) {
            throw new IllegalStateException("AgentWorks JDBC requires its TransactionTemplate and DataSource to "
                    + "refer to the same DataSource instance");
        }
    }
}
