# Corresponding source

Every Dvara AgentWorks Core release is built from the canonical public repository:

<https://github.com/dvarahq/dvara-agentworks>

The release bundle contains `SOURCE.json`, which names the exact immutable tag and
40-character commit used for the build. It also contains checksums, per-artifact
SBOM and provenance records, and a signed checksum manifest. Those records let a
recipient retrieve and independently rebuild the corresponding Apache-2.0 source.

No proprietary source or dependency is part of an open-source release.
