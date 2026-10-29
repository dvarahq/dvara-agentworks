#!/usr/bin/env python3
"""Verify that the walking-skeleton journey harness reaches the product through public extension points only (#37).

The harness module `agentworks-journey` is built only under the `journey` profile. Its extension jar is put on the
unmodified server boot jar's loader path, so what it may compile against is what decides whether "public extension
points only" holds:

- the journey profile is the only profile with modules, and it adds exactly `agentworks-journey`;
- every dependency is `provided` or `test` scoped, so nothing of the harness reaches a runtime classpath;
- `provided` dependencies are exactly the allowlisted extension points: the gateway SPI, capability identity and
  versioned callback contracts, Spring AI's tool contract, and Spring Boot auto-configuration;
- no other Core module is a dependency, except the server as a `pom`-type, fully excluded ordering dependency that
  puts nothing on any classpath;
- the extension's sources import only allowlisted packages: never the runtime, storage, release, deployment, server,
  or Enterprise code.
"""

import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(os.environ.get("AGENTWORKS_REPOSITORY_ROOT", Path(__file__).resolve().parents[2]))
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
MODULE = "agentworks-journey"
PROVIDED = {
    ("com.dvarahq.agentworks", "agentworks-gateway-api"),
    ("com.dvarahq.agentworks", "agentworks-capabilities"),
    ("com.dvarahq.agentworks", "agentworks-runtime"),
    ("org.springframework.ai", "spring-ai-model"),
    ("org.springframework.boot", "spring-boot-autoconfigure"),
}
ORDERING = ("com.dvarahq.agentworks", "agentworks-server")
IMPORTS = (
    "com.dvarahq.agentworks.gateway.",
    "com.dvarahq.agentworks.capability.",
    "com.dvarahq.agentworks.agent.springai.",
    "com.dvarahq.agentworks.journey.",
    "org.springframework.ai.tool.",
    "org.springframework.boot.autoconfigure.",
    "org.springframework.context.annotation.",
    "org.springframework.core.env.",
    "java.",
)
AUTO_CONFIGURATION_IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
EXTENSION_AUTO_CONFIGURATION = "com.dvarahq.agentworks.journey.extension.JourneyExtensionAutoConfiguration"


def problems(root: Path = ROOT) -> list[str]:
    found = []
    reactor = ET.parse(root / "pom.xml").getroot()
    profile_modules = {
        profile.findtext("m:id", namespaces=NS): [node.text for node in profile.findall("m:modules/m:module", NS)]
        for profile in reactor.findall("m:profiles/m:profile", NS) if profile.findall("m:modules/m:module", NS)
    }
    if profile_modules != {"journey": [MODULE]}:
        found.append(f"profile-only modules changed: {profile_modules}")
    pom = ET.parse(root / MODULE / "pom.xml").getroot()
    for dependency in pom.findall("m:dependencies/m:dependency", NS):
        group = dependency.findtext("m:groupId", default="", namespaces=NS)
        artifact = dependency.findtext("m:artifactId", default="", namespaces=NS)
        scope = dependency.findtext("m:scope", default="compile", namespaces=NS)
        kind = dependency.findtext("m:type", default="jar", namespaces=NS)
        coordinate = (group, artifact)
        if scope not in ("provided", "test"):
            found.append(f"the journey harness must not have a {scope} dependency: {group}:{artifact}")
        elif scope == "provided" and coordinate not in PROVIDED:
            found.append(f"the journey harness may compile only against allowlisted extension points, not "
                         f"{group}:{artifact}")
        if group == "com.dvarahq.agentworks" and coordinate not in PROVIDED:
            exclusions = [(node.findtext("m:groupId", default="", namespaces=NS),
                           node.findtext("m:artifactId", default="", namespaces=NS))
                          for node in dependency.findall("m:exclusions/m:exclusion", NS)]
            if coordinate != ORDERING or kind != "pom" or scope != "test" or exclusions != [("*", "*")]:
                found.append(f"the journey harness must not depend on Core module {artifact} (only the server as a "
                             "test-scoped, fully excluded pom-type ordering dependency)")
    for source in sorted((root / MODULE).glob("src/main/java/**/*.java")):
        relative = source.relative_to(root).as_posix()
        for imported in re.findall(r"^import\s+(?:static\s+)?([\w.*]+)\s*;", source.read_text(encoding="utf-8"),
                                   re.MULTILINE):
            if not imported.startswith(IMPORTS):
                found.append(f"{relative} imports {imported}, which is not a public extension point")
    resources = root / MODULE / "src/main/resources"
    for resource in sorted(path for path in resources.rglob("*") if path.is_file()):
        relative = resource.relative_to(resources).as_posix()
        if relative != AUTO_CONFIGURATION_IMPORTS:
            found.append(f"{MODULE}/src/main/resources/{relative} is not an allowlisted extension resource")
            continue
        entries = [line.strip() for line in resource.read_text(encoding="utf-8").splitlines()
                   if line.strip() and not line.lstrip().startswith("#")]
        if entries != [EXTENSION_AUTO_CONFIGURATION]:
            found.append(f"{AUTO_CONFIGURATION_IMPORTS} registers non-allowlisted auto-configurations: {entries}")
    return found


def main() -> None:
    if found := problems(ROOT):
        raise SystemExit("journey-isolation=FAIL: " + "; ".join(found))
    print("journey-isolation=PASS")


if __name__ == "__main__":
    main()
