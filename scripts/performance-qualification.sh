#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mode="${1:-quick}"

case "$mode" in
  quick) duration="${PERFORMANCE_DURATION:-PT30S}" ;;
  soak) duration="${PERFORMANCE_DURATION:-PT24H}" ;;
  *) echo "usage: $0 [quick|soak]" >&2; exit 2 ;;
esac

commit="$(git -C "$root" rev-parse HEAD)"
dirty=false
if [[ -n "$(git -C "$root" status --porcelain)" ]]; then dirty=true; fi
evidence="${PERFORMANCE_EVIDENCE_DIR:-$root/agentworks-server/target/performance-evidence}"

echo "Issue #63 performance qualification: mode=$mode duration=$duration commit=$commit dirty=$dirty"

preflight_tests="SpringAiRunExecutionTest,KubernetesSandboxProviderTest,KubernetesSandboxProviderConformanceTest,"\
"RunAdmissionControlPostgresTest,RunAdmissionFencePostgresTest,PromotionLifecyclePostgresTest,"\
"ReleaseLifecyclePostgresTest,LifecycleIdempotencyPostgresTest,RunLeaseFencingPostgresTest,"\
"RunRecoveryPostgresTest,CohortWorkItemFencePostgresTest,ChildRunScheduledWakeupPostgresTest,"\
"SessionInboxDurableOperationPostgresTest,JdbcSandboxLifecycleAuthorityPostgresTest,"\
"SandboxCoordinatorPostgresTest,OutboxReconciliationPostgresTest,ControlPlaneOutboxDispatcherLockTest,"\
"OutboxReleaseInvalidationTest,RegistryDependencyContinuityProviderTest"

"$root/mvnw" -B -ntp -f "$root/pom.xml" \
  -pl agentworks-runtime,agentworks-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest="$preflight_tests" \
  test

"$root/mvnw" -B -ntp -f "$root/pom.xml" \
  -pl agentworks-server -am \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtest=PerformanceQualificationPostgresTest \
  -Dperformance.mode="$mode" \
  -Dperformance.duration="$duration" \
  -Dperformance.workers="${PERFORMANCE_WORKERS:-8}" \
  -Dperformance.batch="${PERFORMANCE_BATCH:-48}" \
  -Dperformance.inject-stale-leases="${PERFORMANCE_INJECT_STALE_LEASES:-0}" \
  -Dperformance.inject-admission-connection-failures="${PERFORMANCE_INJECT_ADMISSION_CONNECTION_FAILURES:-1}" \
  -Dperformance.inject-worker-connection-failures="${PERFORMANCE_INJECT_WORKER_CONNECTION_FAILURES:-1}" \
  -Dperformance.lease="${PERFORMANCE_LEASE:-PT1M}" \
  -Dperformance.connection-recovery-delay="${PERFORMANCE_CONNECTION_RECOVERY_DELAY:-PT1S}" \
  -Dperformance.database-connect-timeout="${PERFORMANCE_DATABASE_CONNECT_TIMEOUT:-PT5S}" \
  -Dperformance.database-socket-timeout="${PERFORMANCE_DATABASE_SOCKET_TIMEOUT:-PT30S}" \
  -Dperformance.commit="$commit" \
  -Dperformance.dirty="$dirty" \
  -Dperformance.report.dir="$evidence" \
  test

echo "Performance evidence: $evidence/report.json"
