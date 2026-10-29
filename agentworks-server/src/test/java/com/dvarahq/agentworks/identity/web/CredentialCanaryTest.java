package com.dvarahq.agentworks.identity.web;

import com.dvarahq.agentworks.DvaraAgentWorksApplication;
import com.dvarahq.agentworks.identity.InstallationBootstrapService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Secret canary scan: a full identity journey with debug security logging and traced JDBC parameters, after which
 * no password, bootstrap token, session value, CSRF token, or API token appears in any log line, response it was
 * not issued in, or database column (issue #74).
 */
@ExtendWith(OutputCaptureExtension.class)
@Tag("qualification")
@Isolated("temporarily changes process-wide logging levels while scanning captured output")
class CredentialCanaryTest {

    private static final List<String> VERBOSE_LOGGERS = List.of("com.dvarahq", "org.springframework.security",
            "org.springframework.jdbc.core", "org.springframework.web");

    @Test
    void noCredentialMaterialReachesLogsOrTheDatabase(CapturedOutput output, @TempDir Path directory) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String adminPassword = "canary-admin-" + suffix;
        String wrongPassword = "canary-wrong-" + suffix;
        String changedPassword = "canary-changed-" + suffix;
        List<String> canaries = new ArrayList<>(List.of(adminPassword, wrongPassword, changedPassword));

        try (ConfigurableApplicationContext context = new SpringApplication(DvaraAgentWorksApplication.class)
                .run("--server.port=0", "--agentworks.security.bootstrap.token-file=" + directory.resolve("bootstrap"),
                        "--logging.level.com.dvarahq=DEBUG", "--logging.level.org.springframework.security=DEBUG",
                        "--logging.level.org.springframework.jdbc.core=TRACE",
                        "--logging.level.org.springframework.web=DEBUG")) {
            int port = ((WebServerApplicationContext) context).getWebServer().getPort();
            String bootstrapToken = Files.readString(context.getBean(InstallationBootstrapService.class).tokenFile()).strip();
            canaries.add(bootstrapToken);

            var browser = new IdentityHttp(port);
            assertThat(browser.send("POST", "/api/v1/auth/bootstrap", Map.of("token", bootstrapToken,
                    "username", "canary-root", "password", adminPassword)).status()).isEqualTo(201);
            assertThat(new IdentityHttp(port).login("canary-root", wrongPassword).status()).isEqualTo(401);
            assertThat(new IdentityHttp(port).login("unknown-" + suffix, wrongPassword).status()).isEqualTo(401);
            assertThat(browser.login("canary-root", adminPassword).status()).isEqualTo(200);
            canaries.add(browser.cookie());
            canaries.add(browser.csrf());
            String token = browser.send("POST", "/api/v1/tokens", Map.of("name", "canary")).json().path("token").asString();
            canaries.add(token);
            assertThat(new IdentityHttp(port).bearer(token).send("POST", "/api/v1/agents", Map.of("name", "canary"))
                    .status()).isEqualTo(201);
            assertThat(new IdentityHttp(port).bearer(token + "x").get("/api/v1/agents").status()).isEqualTo(401);
            var created = browser.send("POST", "/api/v1/users", Map.of("username", "canary-user"));
            canaries.add(created.json().path("temporaryPassword").asString());
            var reset = browser.send("POST", "/api/v1/users/" + created.json().at("/user/id").asString()
                    + "/password-reset", null);
            canaries.add(reset.json().path("temporaryPassword").asString());
            assertThat(browser.send("POST", "/api/v1/auth/password", Map.of("currentPassword", adminPassword,
                    "newPassword", changedPassword)).status()).isEqualTo(200);
            canaries.add(browser.cookie());
            assertThat(browser.send("POST", "/api/v1/auth/logout", null).status()).isEqualTo(204);

            List<String> columns = databaseText(context.getBean(JdbcTemplate.class));
            assertThat(canaries).allSatisfy(canary -> {
                assertThat(canary).isNotBlank();
                assertThat(columns).noneMatch(value -> value.contains(canary));
            });
            assertThat(columns).as("the scan read the identity tables").anyMatch(value -> value.equals("canary-root"));
        } finally {
            // Leave no debug or trace level behind for later tests in this JVM.
            var logging = LoggingSystem.get(getClass().getClassLoader());
            for (String logger : VERBOSE_LOGGERS) logging.setLogLevel(logger, null);
        }
        String logs = output.getAll();
        assertThat(logs).as("the scan covered traced JDBC parameters and request logging")
                .contains("bootstrap token was written to", "parameter value", "LoginRequest[");
        assertThat(canaries).allSatisfy(canary -> assertThat(logs).doesNotContain(canary));
        assertThat(logs).doesNotContain("Using generated security password");
    }

    /** Every non-null value of every column of every application table, as text. */
    private static List<String> databaseText(JdbcTemplate jdbc) {
        List<String> values = new ArrayList<>();
        for (String table : jdbc.queryForList("select table_name from information_schema.tables "
                + "where table_schema = 'PUBLIC' and table_type = 'BASE TABLE'", String.class)) {
            for (Map<String, Object> row : jdbc.queryForList("select * from \"" + table + "\"")) {
                row.values().stream().filter(java.util.Objects::nonNull).map(String::valueOf).forEach(values::add);
            }
        }
        return values;
    }
}
