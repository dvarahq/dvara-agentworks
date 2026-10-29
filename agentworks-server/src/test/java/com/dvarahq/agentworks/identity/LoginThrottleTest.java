package com.dvarahq.agentworks.identity;

import com.dvarahq.agentworks.identity.web.LoginThrottle;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The per-address sign-in limit refuses bursts, recovers after its window, and keeps addresses independent. */
class LoginThrottleTest {

    @Test
    void refusesAttemptsBeyondTheWindowAndRecoversAfterIt() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(new SecurityPolicy.Login(5, Duration.ofMinutes(15), 3, Duration.ofMinutes(1), 16, 10),
                clock);

        assertThat(throttle.tryAcquire("198.51.100.7")).isTrue();
        assertThat(throttle.tryAcquire("198.51.100.7")).isTrue();
        assertThat(throttle.tryAcquire("198.51.100.7")).isTrue();
        assertThat(throttle.tryAcquire("198.51.100.7")).isFalse();
        assertThat(throttle.retryAfterSeconds("198.51.100.7")).isBetween(1L, 61L);
        assertThat(throttle.tryAcquire("203.0.113.9")).as("other clients are unaffected").isTrue();

        clock.advance(Duration.ofSeconds(61));
        assertThat(throttle.tryAcquire("198.51.100.7")).isTrue();
    }

    @Test
    void ipv6ClientsAreBucketedByTheirSlash64AndAccountsAreLimitedAcrossClients() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(new SecurityPolicy.Login(5, Duration.ofMinutes(15), 2, Duration.ofMinutes(1), 16, 3),
                clock);

        assertThat(LoginThrottle.clientKey("2001:db8:1:2::1")).isEqualTo(LoginThrottle.clientKey("2001:db8:1:2:ffff::9"))
                .isNotEqualTo(LoginThrottle.clientKey("2001:db8:1:3::1"));
        assertThat(LoginThrottle.clientKey("198.51.100.7")).isEqualTo("198.51.100.7");
        assertThat(throttle.tryAcquire("2001:db8:1:2::1")).isTrue();
        assertThat(throttle.tryAcquire("2001:db8:1:2::2")).isTrue();
        assertThat(throttle.tryAcquire("2001:db8:1:2::3")).as("rotating within one /64").isFalse();

        for (int index = 1; index <= 3; index++) assertThat(throttle.tryAcquireAccount("Alice")).isTrue();
        assertThat(throttle.tryAcquireAccount(" alice ")).as("the same account from any client").isFalse();
        assertThat(throttle.tryAcquireAccount("bob")).isTrue();
    }

    @Test
    void aServerReachableBeyondLoopbackWithoutAForwardedHeadersStrategyIsWarned() {
        var warnings = new java.util.ArrayList<Boolean>();
        for (String[] properties : java.util.List.of(new String[0], new String[]{"server.address", "0.0.0.0"},
                new String[]{"server.address", "127.0.0.1"},
                new String[]{"server.forward-headers-strategy", "native"},
                new String[]{"server.forward-headers-strategy", "none"},
                new String[]{"server.forward-headers-strategy", "framework"})) {
            var environment = new org.springframework.mock.env.MockEnvironment();
            if (properties.length > 0) environment.setProperty(properties[0], properties[1]);
            warnings.add(com.dvarahq.agentworks.autoconfigure.AgentWorksSecurityAutoConfiguration
                    .proxyWarning(environment).isPresent());
        }
        // #177: none is warned like an unset strategy, and framework (spoofable) is warned with native recommended.
        // #207: native without internal-proxies is warned on any server, not only on a detected cloud platform.
        assertThat(warnings).containsExactly(true, true, false, true, true, true);
        var framework = new org.springframework.mock.env.MockEnvironment()
                .withProperty("server.forward-headers-strategy", "framework");
        assertThat(com.dvarahq.agentworks.autoconfigure.AgentWorksSecurityAutoConfiguration.proxyWarning(framework))
                .get().asString().contains("native").contains("internal-proxies");
    }

    /**
     * #203 (refuted finding): framework is warned on a loopback-bound server too. A forwarded-headers strategy is set
     * only behind a proxy, which for a loopback-bound server runs on the same host, and Spring's ForwardedHeaderFilter
     * takes the leftmost X-Forwarded-For value, which a proxy that appends to the header (nginx's
     * $proxy_add_x_forwarded_for) leaves under the client's control.
     */
    @Test
    void frameworkIsWarnedOnALoopbackBoundServerToo() {
        var environment = new org.springframework.mock.env.MockEnvironment()
                .withProperty("server.address", "127.0.0.1").withProperty("server.forward-headers-strategy", "framework");
        assertThat(com.dvarahq.agentworks.autoconfigure.AgentWorksSecurityAutoConfiguration.proxyWarning(environment))
                .isPresent();
    }

    /**
     * #203: on a detected cloud platform Spring Boot applies the native strategy when none is set. It trusts every
     * private address as a proxy, so another client on the platform's network could choose its own limit: that is
     * warned, naming internal-proxies, until internal-proxies is set. An explicit none is warned as before, and a
     * platform that does not use forwarded headers is treated like no platform.
     */
    @Test
    void aCloudPlatformsImplicitNativeStrategyIsWarnedUntilInternalProxiesAreSet() {
        var warnings = new java.util.ArrayList<java.util.Optional<String>>();
        for (String[] properties : java.util.List.of(
                new String[]{"spring.main.cloud-platform", "kubernetes"},
                new String[]{"spring.main.cloud-platform", "cloud_foundry", "server.forward-headers-strategy", "native"},
                new String[]{"spring.main.cloud-platform", "kubernetes",
                        "server.tomcat.remoteip.internal-proxies", "10\\.0\\.0\\.7"},
                new String[]{"spring.main.cloud-platform", "kubernetes", "server.forward-headers-strategy", "native",
                        "server.tomcat.remoteip.internal-proxies", "10\\.0\\.0\\.7"},
                new String[]{"spring.main.cloud-platform", "none", "server.address", "0.0.0.0"},
                new String[]{"spring.main.cloud-platform", "kubernetes", "server.forward-headers-strategy", "none"})) {
            var environment = new org.springframework.mock.env.MockEnvironment();
            for (int index = 0; index < properties.length; index += 2) {
                environment.setProperty(properties[index], properties[index + 1]);
            }
            warnings.add(com.dvarahq.agentworks.autoconfigure.AgentWorksSecurityAutoConfiguration
                    .proxyWarning(environment));
        }
        assertThat(warnings.stream().map(java.util.Optional::isPresent))
                .containsExactly(true, true, false, false, true, true);
        assertThat(warnings.getFirst()).get().asString().contains("server.tomcat.remoteip.internal-proxies");
        assertThat(warnings.get(1)).get().asString().contains("server.tomcat.remoteip.internal-proxies");
    }

    /**
     * #207: the effective strategy is native, whether set or applied by a detected platform, so the warning holds
     * whenever internal-proxies is unset, on a detected platform or not, and a loopback-bound server included (a
     * forwarded-headers strategy is set only behind a proxy, whose other clients may share a private network).
     * #207 low: the fixture's internal-proxies is a regular expression that matches the proxy's own address.
     */
    @Test
    void anExplicitNativeStrategyIsWarnedUntilInternalProxiesAreSetOnAnyServer() {
        String proxy = "10\\.0\\.0\\.7";
        assertThat("10.0.0.7").matches(proxy);
        assertThat("10.0.0.70").doesNotMatch(proxy);
        var warnings = new java.util.ArrayList<java.util.Optional<String>>();
        for (String[] properties : java.util.List.of(
                new String[]{"server.forward-headers-strategy", "native"},
                new String[]{"server.forward-headers-strategy", "NATIVE", "server.address", "127.0.0.1"},
                new String[]{"server.forward-headers-strategy", "native", "spring.main.cloud-platform", "none"},
                new String[]{"server.forward-headers-strategy", "native", "server.tomcat.remoteip.internal-proxies",
                        proxy})) {
            var environment = new org.springframework.mock.env.MockEnvironment();
            for (int index = 0; index < properties.length; index += 2) {
                environment.setProperty(properties[index], properties[index + 1]);
            }
            warnings.add(com.dvarahq.agentworks.autoconfigure.AgentWorksSecurityAutoConfiguration
                    .proxyWarning(environment));
        }
        assertThat(warnings.stream().map(java.util.Optional::isPresent)).containsExactly(true, true, true, false);
        assertThat(warnings.getFirst()).get().asString().contains("native")
                .contains("server.tomcat.remoteip.internal-proxies");
    }

    /**
     * PR #208 review: Spring Boot installs Tomcat's RemoteIpValve whenever remote-ip-header or protocol-header is set,
     * whatever the strategy, so either header without internal-proxies is warned like the native strategy.
     */
    @Test
    void aRemoteIpHeaderWithoutInternalProxiesIsWarnedWhateverTheStrategy() {
        var warnings = new java.util.ArrayList<java.util.Optional<String>>();
        for (String[] properties : java.util.List.of(
                new String[]{"server.tomcat.remoteip.remote-ip-header", "x-forwarded-for", "server.address",
                        "127.0.0.1"},
                new String[]{"server.tomcat.remoteip.remote-ip-header", "x-client-address", "server.address",
                        "127.0.0.1"},
                new String[]{"server.tomcat.remoteip.protocol-header", "x-forwarded-proto", "server.address",
                        "127.0.0.1"},
                new String[]{"server.tomcat.remoteip.remote-ip-header", "x-forwarded-for",
                        "server.forward-headers-strategy", "none"},
                new String[]{"server.tomcat.remoteip.remote-ip-header", "x-forwarded-for",
                        "server.tomcat.remoteip.internal-proxies", "10\\.0\\.0\\.7"},
                new String[]{"server.tomcat.remoteip.remote-ip-header", " ", "server.address", "127.0.0.1"})) {
            var environment = new org.springframework.mock.env.MockEnvironment();
            for (int index = 0; index < properties.length; index += 2) {
                environment.setProperty(properties[index], properties[index + 1]);
            }
            warnings.add(com.dvarahq.agentworks.autoconfigure.AgentWorksSecurityAutoConfiguration
                    .proxyWarning(environment));
        }
        assertThat(warnings.stream().map(java.util.Optional::isPresent)).containsExactly(true, true, true, true, false,
                false);
        assertThat(warnings.getFirst()).get().asString().contains("server.tomcat.remoteip.remote-ip-header")
                .contains("server.tomcat.remoteip.internal-proxies");
        assertThat(warnings.get(1)).get().asString().contains("x-client-address").doesNotContain("X-Forwarded-For");
        assertThat(warnings.get(3)).get().asString().contains("RemoteIpValve");
    }

    /** #177: malformed usernames share one bucket that no real username, such as "invalid", can collide with. */
    @Test
    void malformedUsernamesNeverShareABucketWithARealAccount() {
        var throttle = new LoginThrottle(new SecurityPolicy.Login(5, Duration.ofMinutes(15), 5, Duration.ofMinutes(1), 16, 2),
                new MutableClock());

        assertThat(throttle.tryAcquireAccount("!")).isTrue();
        assertThat(throttle.tryAcquireAccount("?")).isTrue();
        assertThat(throttle.tryAcquireAccount("!?")).as("malformed usernames share one bucket").isFalse();
        assertThat(throttle.tryAcquireAccount("invalid")).as("a real user named invalid is unaffected").isTrue();
    }

    @Test
    void manyDistinctAddressesStayBounded() {
        var clock = new MutableClock();
        var throttle = new LoginThrottle(new SecurityPolicy.Login(5, Duration.ofMinutes(15), 1, Duration.ofMinutes(1), 16, 10),
                clock);
        for (int index = 0; index < 9_998; index++) {
            assertThat(throttle.tryAcquire("10.0." + (index / 250) + "." + (index % 250))).isTrue();
        }
        clock.advance(Duration.ofSeconds(1));
        assertThat(throttle.tryAcquire("203.0.113.1")).isTrue();
        assertThat(throttle.tryAcquire("203.0.113.1")).isFalse();
        // Spraying past the bound forgets the oldest windows, never the recent client's.
        for (int index = 0; index < 15_000; index++) throttle.tryAcquire("10.1." + (index / 250) + "." + (index % 250));
        assertThat(throttle.tryAcquire("203.0.113.1")).isFalse();
    }
}
