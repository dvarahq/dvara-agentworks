# Contributing to Dvara AgentWorks Core

DVARA Labs Inc welcomes contributions to the public Dvara AgentWorks Core project.

## License and provenance

Dvara AgentWorks Core is licensed under the Apache License 2.0. By intentionally
submitting a contribution for inclusion, you agree that it is licensed under
Apache License 2.0 as described by section 5 of that license, unless DVARA Labs
Inc has agreed to separate written terms.

Every commit must include a Developer Certificate of Origin 1.1 sign-off:

```text
Signed-off-by: Full Name <email@example.com>
```

Add it with `git commit -s`. The sign-off certifies the statements in the
included `DCO` file. Contributions without a valid sign-off must not be merged.

If a contribution incorporates third-party work, identify its source and
license in the pull request. Do not submit code, documentation, generated
content, or other material that you do not have the right to contribute under
Apache License 2.0.

## Contribution workflow

1. Open an issue for significant behavioral, API, or architectural changes.
2. Keep changes focused and include appropriate automated tests.
3. Reproduce the release-blocking checks before opening a pull request:

   ```text
   ./mvnw -B -ntp clean verify
   python3 -m unittest discover -s src/test/python -v
   python3 scripts/ci/verify_traceability.py
   python3 scripts/ci/verify_repository_policy.py
   python3 scripts/ci/test_policy_gates.py
   scripts/ci/verify_negative_dependency_fixture.sh
   ```

   Docker must be available for the PostgreSQL Testcontainers suites. No model
   provider credentials, Kafka service, or Redis service are required.
4. Map every new executable test in `config/traceability/requirements.json`.
   An issue that completes a delivery adds `config/traceability/issues/<n>.json`
   and lists `<n>` in `config/traceability/index.json`. Cite only requirement
   and detailed IDs that a repository catalog defines.
5. Explain the change, validation performed, and any compatibility or security
   implications in the pull request.
6. Preserve copyright, attribution, and license notices.

Pull requests must pass `Java and Python verification`, `Policy and
traceability gates`, and `DCO sign-off`. These checks are release-blocking on
the canonical branch.

Acceptance of a contribution does not grant rights to DVARA Labs Inc, Dvara AgentWorks,
or DvaraHQ names, logos, or trademarks beyond customary attribution.
