# Security policy

## Supported versions

Security fixes are made on the default branch and included in the next release. At release candidate time, only the
latest published 1.x release and the default branch receive security fixes. Operators should upgrade rather than
carry a vulnerable older minor release.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability or include credentials, exploit data, tenant content, or
personal data in a report. Use GitHub's **Report a vulnerability** form in this repository's Security tab. If private
reporting is unavailable, contact the repository owner through the organization profile and ask for a private
security channel without sending exploit details in the first message.

Include the affected version or commit, deployment profile, impact, reproduction steps, and any suggested mitigation.
Use synthetic data and revoke every credential used for reproduction. We aim to acknowledge a complete report within
three business days, provide a triage decision within seven business days, and coordinate remediation and disclosure
with the reporter. These are response targets, not a warranty.

## Scope and disclosure

In scope are authentication and authorization bypass, cross-tenant access, secret disclosure, durable replay or
idempotency bypass, sandbox escape, unsafe content handling, SSRF, and supply-chain compromise in Dvara AgentWorks.
Denial of service that needs only ordinary authenticated access is also in scope. Automated scanning must not target
systems or tenants you do not own, degrade service, persist access, or exfiltrate real data.

The project does not claim that Docker alone is a hostile-code security boundary, that a configured remote endpoint is
safe without network egress controls, or that an adapter supplied by an operator is trusted automatically. See the
[threat model](docs/security/threat-model.md) for supported boundaries and residual risks.
