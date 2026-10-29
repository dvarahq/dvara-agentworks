#!/usr/bin/env bash
set -euo pipefail

# Dependency-Check's own daily mirror avoids anonymous NVD API rate limits while retaining fail-closed updates.
./mvnw -B -ntp org.owasp:dependency-check-maven:12.2.2:aggregate \
  '-DnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz' \
  -DsuppressionFiles=config/dependency-check-suppressions.xml \
  -DfailBuildOnCVSS=7 -DfailOnError=true -Dformats=HTML,JSON
