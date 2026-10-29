package com.dvarahq.agentworks.autoconfigure;

import com.dvarahq.agentworks.identity.ApiTokenService;
import com.dvarahq.agentworks.identity.AdministrativeAuditService;
import com.dvarahq.agentworks.identity.AuthAuditLog;
import com.dvarahq.agentworks.identity.CredentialCodec;
import com.dvarahq.agentworks.identity.IdentityService;
import com.dvarahq.agentworks.identity.InstallationBootstrapService;
import com.dvarahq.agentworks.identity.MembershipService;
import com.dvarahq.agentworks.identity.PasswordPolicy;
import com.dvarahq.agentworks.identity.SecurityPolicy;
import com.dvarahq.agentworks.identity.SessionService;
import com.dvarahq.agentworks.identity.web.ApiAuthorizationRules;
import com.dvarahq.agentworks.identity.web.AdministrativeAuditController;
import com.dvarahq.agentworks.identity.web.ApiTokenController;
import com.dvarahq.agentworks.identity.web.AuthController;
import com.dvarahq.agentworks.identity.web.CredentialAuthenticationFilter;
import com.dvarahq.agentworks.identity.web.ConsoleAccess;
import com.dvarahq.agentworks.identity.web.CredentialAuthenticationProvider;
import com.dvarahq.agentworks.identity.web.IdentityApiExceptionHandler;
import com.dvarahq.agentworks.identity.web.LoginThrottle;
import com.dvarahq.agentworks.identity.web.RequestBodyLimitFilter;
import com.dvarahq.agentworks.identity.web.MembershipController;
import com.dvarahq.agentworks.identity.web.PasswordAuthenticationProvider;
import com.dvarahq.agentworks.identity.web.SecurityProblems;
import com.dvarahq.agentworks.identity.web.SessionCookies;
import com.dvarahq.agentworks.identity.web.UserAdministrationController;
import com.dvarahq.agentworks.storage.identity.JdbcIdentityRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.DelegatingRequestMatcherHeaderWriter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Spring Security for every AgentWorks servlet application (issue #74). It cannot be disabled: without identity
 * storage, every protected request is refused.
 *
 * <ul>
 *   <li>Requests authenticate statelessly from a {@code Bearer} API token or the session cookie (with a CSRF token
 *       for state-changing cookie requests); see {@link CredentialAuthenticationFilter}.</li>
 *   <li>{@link ApiAuthorizationRules} authorizes every endpoint by workspace permission and denies anything else.</li>
 *   <li>Passwords use Argon2id (OWASP parameters: 19 MiB, two iterations, one lane) behind a
 *       {@link DelegatingPasswordEncoder}, so parameters can be raised and old hashes upgrade at sign-in.</li>
 *   <li>Authentication is pluggable: every {@link AuthenticationProvider} bean joins the
 *       {@link AuthenticationManager}. A provider for an external identity provider maps its subject to a local
 *       user and returns an {@code AgentWorksAuthentication}, leaving tenant and resource authority unchanged.</li>
 * </ul>
 * Policy values bind from {@code agentworks.security.*}; see {@link SecurityPolicy}.
 */
@AutoConfiguration(after = AgentWorksJdbcAutoConfiguration.class, beforeName = {
        "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration",
        "org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration"})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(SecurityFilterChain.class)
@EnableWebSecurity
public final class AgentWorksSecurityAutoConfiguration {
    private static final String PREFIX = "agentworks.security.";
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(AgentWorksSecurityAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    SecurityPolicy agentWorksSecurityPolicy(Environment environment) {
        // Refuses startup when the local-development flag is set on a server that is not bound to loopback.
        AgentWorksLocalDevelopment.enabled(environment);
        return bind(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    Clock agentWorksClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    PasswordEncoder agentWorksPasswordEncoder() {
        return new DelegatingPasswordEncoder("argon2", Map.of("argon2", new Argon2PasswordEncoder(16, 32, 1, 19_456, 2)));
    }

    @Bean
    @ConditionalOnMissingBean
    CredentialCodec agentWorksCredentialCodec() {
        return new CredentialCodec();
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityProblems agentWorksSecurityProblems() {
        return new SecurityProblems();
    }

    @Bean
    @ConditionalOnMissingBean
    SessionCookies agentWorksSessionCookies(SecurityPolicy policy) {
        return new SessionCookies(policy.session());
    }

    @Bean
    @ConditionalOnMissingBean
    LoginThrottle agentWorksLoginThrottle(SecurityPolicy policy, Clock clock, Environment environment) {
        proxyWarning(environment).ifPresent(LOG::warn);
        return new LoginThrottle(policy.login(), clock);
    }

    /**
     * Sign-in limits key on the servlet remote address (#177). A server reachable beyond loopback without a
     * forwarded-headers strategy ({@code none} included) sees a fronting proxy's address for every client, so they
     * would all share one limit. The {@code framework} strategy honors {@code X-Forwarded-For} from any sender, so a
     * client could choose its own limit; {@code native} honors it only from Tomcat's internal proxies, which by default
     * are every private and loopback address, so {@code internal-proxies} should name the actual proxy. An unset
     * strategy on a detected cloud platform is {@code native}, as Spring Boot applies it there (#203). Whenever the
     * effective strategy is {@code native}, set or implied, and {@code internal-proxies} is not set, every other client
     * on a private network (another pod, a host on the same network, or one behind source NAT) is within Tomcat's
     * default internal proxies and could set its own address; that is warned (#207), on a detected platform or not.
     */
    public static java.util.Optional<String> proxyWarning(Environment environment) {
        String strategy = environment.getProperty("server.forward-headers-strategy", "").strip();
        org.springframework.boot.cloud.CloudPlatform platform =
                org.springframework.boot.cloud.CloudPlatform.getActive(environment);
        boolean platformNative = strategy.isEmpty() && platform != null && platform.isUsingForwardHeaders();
        // Spring Boot also installs Tomcat's RemoteIpValve whenever either header is configured, whatever the strategy
        // (PR #208 review), so that is the native strategy in effect too, except under framework, warned below.
        String header = java.util.stream.Stream.of("server.tomcat.remoteip.remote-ip-header",
                        "server.tomcat.remoteip.protocol-header")
                .filter(name -> org.springframework.util.StringUtils.hasText(environment.getProperty(name)))
                .findFirst().orElse(null);
        String remoteAddressHeader = environment.getProperty("server.tomcat.remoteip.remote-ip-header",
                "X-Forwarded-For").strip();
        boolean headerNative = header != null && !strategy.equalsIgnoreCase("framework");
        if (strategy.equalsIgnoreCase("native") || platformNative || headerNative) {
            if (environment.containsProperty("server.tomcat.remoteip.internal-proxies")) {
                return java.util.Optional.empty();
            }
            String cause = strategy.equalsIgnoreCase("native") ? "server.forward-headers-strategy=native"
                    : platformNative ? "On " + platform.name() + ", Spring Boot applies the native forwarded-headers "
                            + "strategy, which"
                    : header + " installs Tomcat's RemoteIpValve, which";
            return java.util.Optional.of(cause
                    + " honors " + remoteAddressHeader + " from Tomcat's default internal proxies, every private and loopback "
                    + "address, so a client on a private network (another pod or host, or one behind source NAT) can "
                    + "choose its own sign-in rate limit. Set server.tomcat.remoteip.internal-proxies to match only the "
                    + "reverse proxy, ingress, or load balancer.");
        }
        if (strategy.equalsIgnoreCase("framework")) {
            return java.util.Optional.of("server.forward-headers-strategy=framework honors X-Forwarded-For from any "
                    + "sender, so a client can choose its own sign-in rate limit. Use native, with "
                    + "server.tomcat.remoteip.internal-proxies naming only the reverse proxy (Tomcat's default trusts "
                    + "every private and loopback address).");
        }
        if (!strategy.isEmpty() && !strategy.equalsIgnoreCase("none")) return java.util.Optional.empty();
        String address = environment.getProperty("server.address");
        if (address != null && (address.equals("127.0.0.1") || address.equals("::1")
                || address.equalsIgnoreCase("localhost"))) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of("server.forward-headers-strategy is " + (strategy.isEmpty() ? "not set" : "none")
                + ". If AgentWorks runs behind a reverse proxy, set it to native, with "
                + "server.tomcat.remoteip.internal-proxies naming only the proxy (Tomcat's default trusts every private "
                + "and loopback address); otherwise every client shares the proxy's sign-in rate limit.");
    }

    /** Every provider bean, in order. With none, nothing authenticates and protected requests fail closed. */
    @Bean
    @ConditionalOnMissingBean
    AuthenticationManager agentWorksAuthenticationManager(ObjectProvider<AuthenticationProvider> providers) {
        List<AuthenticationProvider> available = providers.orderedStream().toList();
        if (available.isEmpty()) available = List.of(new RefuseAll());
        return new ProviderManager(available);
    }

    @Bean
    SecurityFilterChain agentWorksSecurityFilterChain(HttpSecurity http, AuthenticationManager authentication,
            SessionCookies cookies, SecurityProblems problems, SecurityPolicy policy) throws Exception {
        var console = new ConsoleAccess();
        var cookieless = new OrRequestMatcher(
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/login"),
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/v1/auth/bootstrap"),
                // Console sign-in (#36) is a plain form without a CSRF header; like the API's sign-in it ignores (and
                // revokes) any session the browser holds, and it refuses a foreign Origin itself.
                PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/console/login"));
        http.securityMatcher("/**")
                // Replaced by session-bound CSRF tokens that CredentialAuthenticationFilter enforces for cookies.
                .csrf(csrf -> csrf.disable())
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The Web Console (#36) is a browser page: sign-in and HTML refusals instead of API problems.
                .exceptionHandling(errors -> errors
                        .defaultAuthenticationEntryPointFor(console, ConsoleAccess.CONSOLE)
                        .defaultAuthenticationEntryPointFor(problems, AnyRequestMatcher.INSTANCE)
                        .defaultAccessDeniedHandlerFor(console, ConsoleAccess.CONSOLE)
                        .defaultAccessDeniedHandlerFor(problems, AnyRequestMatcher.INSTANCE))
                .headers(headers -> headers
                        // The API loads nothing; the Console loads only this server's own scripts and styles.
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(ConsoleAccess.CONSOLE,
                                new StaticHeadersWriter("Content-Security-Policy", ConsoleAccess.CSP)))
                        .addHeaderWriter(new DelegatingRequestMatcherHeaderWriter(
                                new NegatedRequestMatcher(ConsoleAccess.CONSOLE),
                                new StaticHeadersWriter("Content-Security-Policy",
                                        "default-src 'none'; frame-ancestors 'none'")))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .addFilterBefore(new RequestBodyLimitFilter(policy.requests().maxBodySize()),
                        AnonymousAuthenticationFilter.class)
                .addFilterBefore(new CredentialAuthenticationFilter(authentication, cookies, problems, cookieless),
                        AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(ApiAuthorizationRules::apply);
        return http.build();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean({JdbcIdentityRepository.class, TransactionTemplate.class})
    static class Identity {

        @Bean
        @ConditionalOnMissingBean
        PasswordPolicy agentWorksPasswordPolicy(SecurityPolicy policy) {
            return new PasswordPolicy(policy.passwords());
        }

        @Bean
        @ConditionalOnMissingBean
        AuthAuditLog agentWorksAuthAuditLog(JdbcIdentityRepository repository, Clock clock) {
            return new AuthAuditLog(repository, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        AdministrativeAuditService agentWorksAdministrativeAuditService(AgentWorksPlanes planes,
                JdbcIdentityRepository repository) {
            return new AdministrativeAuditService(planes.control().tenants(), repository);
        }

        @Bean
        @ConditionalOnMissingBean
        com.dvarahq.agentworks.identity.HashingBulkhead agentWorksHashingBulkhead(SecurityPolicy policy) {
            return new com.dvarahq.agentworks.identity.HashingBulkhead(policy.login().maxConcurrentVerifications());
        }

        @Bean
        @ConditionalOnMissingBean
        IdentityService agentWorksIdentityService(TransactionTemplate transactions, JdbcIdentityRepository repository,
                PasswordEncoder encoder, PasswordPolicy passwords, SecurityPolicy policy, CredentialCodec codec,
                AuthAuditLog audit, Clock clock, com.dvarahq.agentworks.identity.HashingBulkhead hashing) {
            return new IdentityService(transactions, repository, encoder, passwords, policy.login(), codec, audit, clock,
                    hashing);
        }

        @Bean
        @ConditionalOnMissingBean
        SessionService agentWorksSessionService(TransactionTemplate transactions, JdbcIdentityRepository repository,
                CredentialCodec codec, SecurityPolicy policy, AuthAuditLog audit, Clock clock) {
            return new SessionService(transactions, repository, codec, policy.session(), audit, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        ApiTokenService agentWorksApiTokenService(TransactionTemplate transactions, JdbcIdentityRepository repository,
                CredentialCodec codec, SecurityPolicy policy, AuthAuditLog audit, Clock clock) {
            return new ApiTokenService(transactions, repository, codec, policy.tokens(), audit, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        MembershipService agentWorksMembershipService(TransactionTemplate transactions,
                JdbcIdentityRepository repository, AuthAuditLog audit, Clock clock) {
            return new MembershipService(transactions, repository, audit, clock);
        }

        @Bean
        @ConditionalOnMissingBean
        InstallationBootstrapService agentWorksInstallationBootstrapService(TransactionTemplate transactions,
                JdbcIdentityRepository repository, PasswordEncoder encoder, PasswordPolicy passwords,
                CredentialCodec codec, SecurityPolicy policy, AuthAuditLog audit, Clock clock,
                com.dvarahq.agentworks.identity.HashingBulkhead hashing, Environment environment) {
            // agentworks.node-id names this node stably across restarts (set it in containers, whose host names
            // change); it is read only when a token is issued or a reissue value consumed, and defaults to the host name.
            return new InstallationBootstrapService(transactions, repository, encoder, passwords, codec,
                    policy.bootstrap(), audit, clock, hashing, () -> environment.getProperty("agentworks.node-id"));
        }


        @Bean
        ApplicationRunner agentWorksInstallationBootstrapRunner(InstallationBootstrapService bootstrap) {
            return arguments -> bootstrap.prepare();
        }

        @Bean
        CredentialAuthenticationProvider agentWorksCredentialAuthenticationProvider(ApiTokenService tokens,
                SessionService sessions) {
            return new CredentialAuthenticationProvider(tokens, sessions);
        }

        @Bean
        PasswordAuthenticationProvider agentWorksPasswordAuthenticationProvider(IdentityService identities) {
            return new PasswordAuthenticationProvider(identities);
        }

        @Bean
        @ConditionalOnMissingBean
        AuthController agentWorksAuthController(AuthenticationManager authentication, SessionService sessions,
                IdentityService identities, MembershipService memberships, InstallationBootstrapService bootstrap,
                SessionCookies cookies, LoginThrottle throttle, Clock clock) {
            return new AuthController(authentication, sessions, identities, memberships, bootstrap, cookies, throttle,
                    clock);
        }

        @Bean
        @ConditionalOnMissingBean
        ApiTokenController agentWorksApiTokenController(ApiTokenService tokens) {
            return new ApiTokenController(tokens);
        }

        @Bean
        @ConditionalOnMissingBean
        MembershipController agentWorksMembershipController(MembershipService memberships) {
            return new MembershipController(memberships);
        }

        @Bean
        @ConditionalOnMissingBean
        UserAdministrationController agentWorksUserAdministrationController(IdentityService identities) {
            return new UserAdministrationController(identities);
        }

        @Bean
        @ConditionalOnMissingBean
        AdministrativeAuditController agentWorksAdministrativeAuditController(AdministrativeAuditService audit,
                com.dvarahq.agentworks.controlplane.agent.web.WorkspaceScopeResolver workspaces) {
            return new AdministrativeAuditController(audit, workspaces);
        }

        /** Re-checks the principal of long-lived requests such as run event streams (issue #35). */
        @Bean
        @ConditionalOnMissingBean
        com.dvarahq.agentworks.identity.PrincipalRevalidation agentWorksPrincipalRevalidation(SessionService sessions,
                ApiTokenService tokens, IdentityService identities) {
            return com.dvarahq.agentworks.identity.PrincipalRevalidation.of(sessions, tokens, identities);
        }

        @Bean
        IdentityApiExceptionHandler agentWorksIdentityApiExceptionHandler() {
            return new IdentityApiExceptionHandler();
        }
    }

    /**
     * An audit that records control actions in the same transaction as the service writing through
     * {@code dataSource}; {@code transactions} must be a template on that data source.
     */
    static com.dvarahq.agentworks.identity.ControlActionAudit controlActionAudit(DataSource dataSource,
            TransactionTemplate transactions, Clock clock, com.dvarahq.agentworks.storage.lifecycle.LifecycleRetry retry) {
        return com.dvarahq.agentworks.identity.ControlActionAudit.transactional(transactions,
                new AuthAuditLog(new JdbcIdentityRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource)),
                        clock), com.dvarahq.agentworks.identity.web.CurrentPrincipal::require, retry);
    }

    static SecurityPolicy bind(Environment environment) {
        Binder binder = Binder.get(environment);
        SecurityPolicy defaults = SecurityPolicy.defaults();
        var session = defaults.session();
        var tokens = defaults.tokens();
        var login = defaults.login();
        var bootstrap = defaults.bootstrap();
        var passwords = defaults.passwords();
        var requests = defaults.requests();
        return new SecurityPolicy(
                new SecurityPolicy.Session(duration(binder, "session.idle-timeout", session.idleTimeout()),
                        duration(binder, "session.absolute-timeout", session.absoluteTimeout()),
                        value(binder, "session.max-concurrent", Integer.class, session.maxConcurrent()),
                        duration(binder, "session.touch-interval", session.touchInterval()),
                        value(binder, "session.cookie-secure", Boolean.class, session.cookieSecure()),
                        value(binder, "session.same-site", String.class, session.sameSite())),
                new SecurityPolicy.Tokens(duration(binder, "tokens.default-lifetime", tokens.defaultLifetime()),
                        duration(binder, "tokens.max-lifetime", tokens.maxLifetime()),
                        duration(binder, "tokens.touch-interval", tokens.touchInterval())),
                new SecurityPolicy.Login(value(binder, "login.max-failures", Integer.class, login.maxFailures()),
                        duration(binder, "login.lockout", login.lockout()),
                        value(binder, "login.attempts-per-window", Integer.class, login.attemptsPerWindow()),
                        duration(binder, "login.window", login.window()),
                        value(binder, "login.max-concurrent-verifications", Integer.class,
                                login.maxConcurrentVerifications()),
                        value(binder, "login.attempts-per-account-window", Integer.class,
                                login.attemptsPerAccountWindow())),
                new SecurityPolicy.Bootstrap(value(binder, "bootstrap.enabled", Boolean.class, bootstrap.enabled()),
                        duration(binder, "bootstrap.lifetime", bootstrap.lifetime()),
                        Path.of(value(binder, "bootstrap.token-file", String.class, bootstrap.tokenFile().toString())),
                        value(binder, "bootstrap.reissue", String.class, bootstrap.reissue()),
                        value(binder, "bootstrap.reissue-confirm", String.class, bootstrap.reissueConfirm())),
                new SecurityPolicy.Passwords(value(binder, "passwords.min-length", Integer.class, passwords.minLength()),
                        value(binder, "passwords.max-length", Integer.class, passwords.maxLength())),
                new SecurityPolicy.Requests(value(binder, "requests.max-body-size", Long.class,
                        requests.maxBodySize())));
    }

    private static Duration duration(Binder binder, String name, Duration fallback) {
        return binder.bind(PREFIX + name, Bindable.of(Duration.class)).orElse(fallback);
    }

    private static <T> T value(Binder binder, String name, Class<T> type, T fallback) {
        return binder.bind(PREFIX + name, Bindable.of(type)).orElse(fallback);
    }

    /** Used only when no provider exists, so that nothing can authenticate. */
    private static final class RefuseAll implements AuthenticationProvider {
        @Override
        public Authentication authenticate(Authentication authentication) {
            throw new BadCredentialsException("The credentials are not valid.");
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return true;
        }
    }
}
