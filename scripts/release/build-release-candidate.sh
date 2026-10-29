#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
mode="${1:-}"
tag="${2:-}"
output="${3:-$root/target/oss-release-candidate}"
if [[ "$mode" != "qualification" && "$mode" != "release" ]]; then
  echo "usage: $0 qualification|release vX.Y.Z [output]" >&2
  exit 2
fi
if [[ ! "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]]; then
  echo "release-build=FAIL: invalid release tag" >&2
  exit 1
fi
cd "$root"
project_version="$(python3 -c 'import xml.etree.ElementTree as E; r=E.parse("pom.xml").getroot(); print(r.findtext("{http://maven.apache.org/POM/4.0.0}version"))')"
if [[ "v$project_version" != "$tag" ]]; then
  echo "release-build=FAIL: Maven version $project_version does not match release tag $tag" >&2
  exit 1
fi
commit="$(git rev-parse HEAD)"
epoch="$(git show -s --format=%ct "$commit")"
if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "release-build=FAIL: release input tree is dirty" >&2
  exit 1
fi
if [[ "$mode" == "release" ]]; then
  if [[ "${GITHUB_REPOSITORY:-}" != "dvarahq/dvara-agentworks" ]]; then
    echo "release-build=FAIL: publishable artifacts can only derive from dvarahq/dvara-agentworks" >&2
    exit 1
  fi
  if [[ "${GITHUB_ACTOR:-}" != "grabdoc" ]]; then
    echo "release-build=FAIL: GitHub actor grabdoc is required" >&2
    exit 1
  fi
  if [[ "$(git rev-parse "refs/tags/$tag^{commit}")" != "$commit" ]]; then
    echo "release-build=FAIL: release tag does not identify HEAD" >&2
    exit 1
  fi
fi

work="$(mktemp -d)"
builder=""
cleanup() {
  if [[ -n "$builder" ]]; then
    docker buildx rm "$builder" >/dev/null 2>&1 || true
  fi
  rm -rf "$work"
}
trap cleanup EXIT
artifacts="$work/artifacts"
mkdir -p "$artifacts"/{maven,cli,oci,helm} "$work/cyclonedx"

./mvnw -B -ntp -DskipTests -Dproject.build.outputTimestamp="$epoch" clean package
./mvnw -B -ntp -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom \
  -DoutputFormat=json -DoutputDirectory="$work/cyclonedx" -DoutputName=dependencies \
  -DincludeBomSerialNumber=false -DincludeTestScope=false
python3 scripts/release/normalize_cyclonedx.py "$work/cyclonedx/dependencies.json" \
  "$artifacts/maven/dependencies.cdx.json" --commit "$commit"

for module in agentworks-*; do
  case "$module" in
    agentworks-agent-test-sample|agentworks-reference-embedded|agentworks-reference-remote|agentworks-journey) continue ;;
  esac
  for artifact in "$module"/target/*.jar; do
    [[ -f "$artifact" ]] || continue
    case "$artifact" in *-tests.jar|*-sources.jar|*-javadoc.jar|*/original-*) continue ;; esac
    cp "$artifact" "$artifacts/maven/${module}-$(basename "$artifact")"
  done
done
cp agentworks-bom/pom.xml "$artifacts/maven/agentworks-bom.pom.xml"

scripts/build-cli.sh "$artifacts/cli"
python3 scripts/release/package_chart.py --source deploy/helm/agentworks \
  --output "$artifacts/helm/agentworks-${tag#v}.tgz" --source-date-epoch "$epoch"

builder="$(docker buildx create --driver docker-container)"
docker buildx build --builder "$builder" --platform linux/amd64,linux/arm64 --sbom=true --provenance=mode=max \
  --output="type=oci,dest=$artifacts/oci/agentworks.oci.tar,rewrite-timestamp=true" \
  --build-arg "SOURCE_DATE_EPOCH=$epoch" --build-arg "VERSION=${tag#v}" --build-arg "REVISION=$commit" \
  --build-arg "SOURCE=https://github.com/dvarahq/dvara-agentworks" .
python3 scripts/ci/verify_oci_archive.py "$artifacts/oci/agentworks.oci.tar"
python3 scripts/ci/verify_supply_chain.py --sbom "$artifacts/maven/dependencies.cdx.json"

arguments=(--artifacts "$artifacts" --output "$output" --commit "$commit" --tag "$tag" --source-date-epoch "$epoch")
if [[ "$mode" == "qualification" ]]; then arguments+=(--qualification); fi
if [[ "$mode" == "release" ]]; then arguments+=(--github-actor "$GITHUB_ACTOR"); fi
python3 scripts/release/create_release_evidence.py "${arguments[@]}"
verify=("$output" --phase build --expected-commit "$commit")
if [[ "$mode" == "qualification" ]]; then verify+=(--allow-qualification); fi
python3 scripts/release/verify_release_candidate.py "${verify[@]}"
echo "release-build=PASS mode=$mode commit=$commit output=$output"
