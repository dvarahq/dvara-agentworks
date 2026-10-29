"""Dependency-free, CI-oriented client for the AgentWorks v1 API."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import stat
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO

VERSION = "1.0"
SCHEMA = "agentworks.cli/v1"
TOKEN = re.compile(r"^awt_[A-Za-z0-9_-]+$")
SECRET_KEY = re.compile(r"token|password|secret|credential|authorization|cookie", re.I)

OK = 0
USAGE = 2
AUTH = 3
API = 4
TEST_FAILED = 10
SIMULATION_FAILED = 11
EVALUATION_FAILED = 12
RELEASE_FAILED = 13
PROMOTION_FAILED = 14
RUN_FAILED = 15
INTERACTION_FAILED = 16
OPERATION_FAILED = 17
ARTIFACT_FAILED = 18
NETWORK = 19
STREAM_RESYNC = 20
LIFECYCLE_CONFLICT = 21

DOMAIN_EXIT = {
    "test": TEST_FAILED, "simulate": SIMULATION_FAILED, "eval": EVALUATION_FAILED,
    "release": RELEASE_FAILED, "promote": PROMOTION_FAILED, "run": RUN_FAILED,
    "audit": API,
    "events": RUN_FAILED, "replay": RUN_FAILED, "interaction": INTERACTION_FAILED,
    "operation": OPERATION_FAILED, "artifact": ARTIFACT_FAILED,
}


class CliError(Exception):
    def __init__(self, code: int, kind: str, message: str, *, status: int | None = None, detail: Any = None):
        super().__init__(message)
        self.code, self.kind, self.status, self.detail = code, kind, status, detail


def config_path() -> Path:
    root = os.environ.get("XDG_CONFIG_HOME")
    return Path(root) / "agentworks" / "config.json" if root else Path.home() / ".config" / "agentworks" / "config.json"


def read_config() -> dict[str, Any]:
    path = config_path()
    if not path.exists():
        return {"version": 1, "currentProfile": "default", "profiles": {}}
    try:
        if path.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO):
            raise CliError(AUTH, "UNSAFE_CONFIG_PERMISSIONS", f"Configuration permissions must be 0600: {path}")
        value = json.loads(path.read_text(encoding="utf-8"))
        if value.get("version") != 1 or not isinstance(value.get("profiles"), dict):
            raise ValueError("unsupported schema")
        return value
    except CliError:
        raise
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        raise CliError(USAGE, "INVALID_CONFIG", f"Cannot read configuration: {exc}") from None


def write_config(value: dict[str, Any]) -> None:
    path = config_path()
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    path.parent.chmod(0o700)
    data = json.dumps(value, sort_keys=True, indent=2) + "\n"
    descriptor, temporary_name = tempfile.mkstemp(prefix=".config-", dir=path.parent)
    temporary = Path(temporary_name)
    os.chmod(temporary, 0o600)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        path.chmod(0o600)
    finally:
        if temporary.exists():
            temporary.unlink()


def redact(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: "[REDACTED]" if SECRET_KEY.search(str(key)) else redact(item) for key, item in value.items()}
    if isinstance(value, list):
        return [redact(item) for item in value]
    if isinstance(value, str):
        value = re.sub(r"awt_[A-Za-z0-9_-]+", "[REDACTED]", value)
        value = re.sub(r"(?i)(bearer\s+)[^\s]+", r"\1[REDACTED]", value)
    return value


def envelope(command: str, data: Any = None, *, error: dict[str, Any] | None = None,
             status: int | None = None, exit_code: int = 0,
             redact_data: bool = True) -> dict[str, Any]:
    result: dict[str, Any] = {"schemaVersion": SCHEMA, "ok": error is None, "command": command,
                              "exitCode": exit_code}
    if status is not None:
        result["httpStatus"] = status
    payload = data if error is None else error
    result["data" if error is None else "error"] = redact(payload) if error is not None or redact_data else payload
    return result


def emit(value: dict[str, Any], output: str, stream: Any = None) -> None:
    target = stream or sys.stdout
    if output == "json":
        print(json.dumps(value, sort_keys=True, separators=(",", ":")), file=target)
        return
    if value["ok"]:
        data = value.get("data")
        if isinstance(data, (dict, list)):
            print(json.dumps(data, sort_keys=True, indent=2), file=target)
        elif data is not None:
            print(data, file=target)
    else:
        error = value["error"]
        print(f"Error [{error['code']}]: {error['message']}", file=target)


@dataclass(frozen=True)
class Context:
    url: str
    token: str
    workspace: str | None
    profile: str


def context(args: argparse.Namespace, *, token_required: bool = True) -> Context:
    config = read_config()
    name = args.profile or os.environ.get("AGENTWORKS_PROFILE") or config.get("currentProfile", "default")
    profile = config["profiles"].get(name, {})
    url = args.url or os.environ.get("AGENTWORKS_URL") or profile.get("url") or "http://localhost:8080"
    parsed = urllib.parse.urlsplit(url)
    if (parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password
            or parsed.query or parsed.fragment):
        raise CliError(USAGE, "INVALID_URL", "AgentWorks URL must be an http or https URL")
    if parsed.scheme == "http" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise CliError(USAGE, "INSECURE_URL", "Plain HTTP is permitted only for a loopback AgentWorks server")
    token = os.environ.get("AGENTWORKS_TOKEN", "")
    if not token and profile.get("tokenEnv"):
        token = os.environ.get(profile["tokenEnv"], "")
    if not token and profile.get("tokenFile"):
        token_path = Path(profile["tokenFile"]).expanduser()
        try:
            if token_path.stat().st_mode & (stat.S_IRWXG | stat.S_IRWXO):
                raise CliError(AUTH, "UNSAFE_TOKEN_PERMISSIONS", f"Token file permissions must be 0600: {token_path}")
            token = token_path.read_text(encoding="utf-8").strip()
        except CliError:
            raise
        except OSError:
            raise CliError(AUTH, "TOKEN_UNAVAILABLE", "The configured token reference is unavailable") from None
    if token_required and not TOKEN.fullmatch(token):
        raise CliError(AUTH, "TOKEN_UNAVAILABLE", "Set AGENTWORKS_TOKEN or configure a safe token reference")
    return Context(url.rstrip("/"), token, profile.get("workspace"), name)


def read_json_file(path: str | None) -> Any:
    if not path:
        return None
    try:
        raw = sys.stdin.buffer.read() if path == "-" else Path(path).read_bytes()
        return json.loads(raw)
    except (OSError, json.JSONDecodeError) as exc:
        raise CliError(USAGE, "INVALID_INPUT", f"Cannot read JSON input: {exc}") from None


def positive_timeout(value: str) -> float:
    try:
        timeout = float(value)
    except ValueError:
        raise argparse.ArgumentTypeError("must be a positive number") from None
    if timeout <= 0:
        raise argparse.ArgumentTypeError("must be a positive number")
    return timeout


class ApiClient:
    def __init__(self, ctx: Context, *, timeout: float, retries: int):
        self.ctx, self.timeout, self.retries = ctx, timeout, retries

    def request(self, method: str, path: str, *, body: Any = None, headers: dict[str, str] | None = None,
                binary: bytes | None = None, accept: str = "application/json",
                retryable: bool = False) -> tuple[int, Any, dict[str, str]]:
        target = self.ctx.url + path
        request_headers = {"Accept": accept, "User-Agent": f"agentworks-cli/{VERSION}"}
        if self.ctx.token:
            request_headers["Authorization"] = f"Bearer {self.ctx.token}"
        request_headers.update(headers or {})
        data = binary
        if body is not None:
            data = json.dumps(body, sort_keys=True, separators=(",", ":")).encode()
            request_headers["Content-Type"] = "application/json"
        safe_retry = method in {"GET", "HEAD"} or retryable
        attempts = self.retries + 1 if safe_retry else 1
        for attempt in range(attempts):
            try:
                request = urllib.request.Request(target, data=data, headers=request_headers, method=method)
                with urllib.request.urlopen(request, timeout=self.timeout) as response:
                    raw = response.read()
                    try:
                        result = json.loads(raw) if raw and "json" in response.headers.get("Content-Type", "") else raw
                    except (json.JSONDecodeError, UnicodeDecodeError):
                        raise CliError(API, "INVALID_RESPONSE", "AgentWorks returned invalid JSON",
                                       status=response.status) from None
                    return response.status, result, dict(response.headers)
            except urllib.error.HTTPError as exc:
                raw = exc.read()
                try:
                    problem = json.loads(raw)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    problem = {"code": "HTTP_ERROR", "detail": "The server rejected the request."}
                if exc.code in {502, 503, 504} and attempt + 1 < attempts:
                    time.sleep(min(0.2 * (2 ** attempt), 2.0))
                    continue
                code = problem.get("code", "HTTP_ERROR") if isinstance(problem, dict) else "HTTP_ERROR"
                if exc.code in {401, 403}:
                    exit_code = AUTH
                elif exc.code in {409, 410, 412, 423}:
                    exit_code = LIFECYCLE_CONFLICT
                else:
                    exit_code = API
                detail = problem.get("detail", "The request failed.") if isinstance(problem, dict) else "The request failed."
                raise CliError(exit_code, code, detail,
                               status=exc.code, detail=problem) from None
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                if attempt + 1 < attempts:
                    time.sleep(min(0.2 * (2 ** attempt), 2.0))
                    continue
                raise CliError(NETWORK, "NETWORK_UNAVAILABLE", f"Cannot reach AgentWorks: {exc.reason if hasattr(exc, 'reason') else exc}") from None
        raise AssertionError("unreachable")


@dataclass(frozen=True)
class Endpoint:
    method: str
    path: str
    ids: tuple[str, ...] = ()
    body: str = "absent"
    idempotency: str = "none"
    query: tuple[str, ...] = ()
    etag: str = "none"
    public: bool = False
    accept: str = "application/json"


ENDPOINTS: dict[tuple[str, str], Endpoint] = {
    ("agent", "list"): Endpoint("GET", "/api/v1/agents", query=("cursor", "limit")),
    ("agent", "show"): Endpoint("GET", "/api/v1/agents/{definition_id}", ("definition_id",)),
    ("agent", "create"): Endpoint("POST", "/api/v1/agents", body="required", idempotency="natural"),
    ("agent", "versions"): Endpoint("GET", "/api/v1/agents/{definition_id}/versions", ("definition_id",),
                                    query=("cursor", "limit")),
    ("agent", "publish"): Endpoint("POST", "/api/v1/agents/{definition_id}/versions", ("definition_id",),
                                   body="required", idempotency="natural"),
    ("eval", "datasets"): Endpoint("GET", "/api/v1/evaluation-datasets"),
    ("eval", "create-dataset"): Endpoint("POST", "/api/v1/evaluation-datasets", body="required", idempotency="natural"),
    ("eval", "purge-expired"): Endpoint("POST", "/api/v1/evaluation-datasets/purge-expired", idempotency="natural"),
    ("eval", "evaluators"): Endpoint("GET", "/api/v1/evaluators"),
    ("eval", "create-evaluator"): Endpoint("POST", "/api/v1/evaluators", body="required", idempotency="natural"),
    ("eval", "run"): Endpoint("POST", "/api/v1/evaluation-runs", body="required", idempotency="natural"),
    ("eval", "status"): Endpoint("GET", "/api/v1/evaluation-runs/{run_id}", ("run_id",)),
    ("eval", "report"): Endpoint("GET", "/api/v1/evaluation-runs/{run_id}/report", ("run_id",)),
    ("eval", "cancel"): Endpoint("POST", "/api/v1/evaluation-runs/{run_id}/cancel", ("run_id",), idempotency="natural"),
    ("eval", "resume"): Endpoint("POST", "/api/v1/evaluation-runs/{run_id}/resume", ("run_id",), idempotency="natural"),
    ("eval", "baselines"): Endpoint("GET", "/api/v1/evaluation-baselines"),
    ("eval", "create-baseline"): Endpoint("POST", "/api/v1/evaluation-baselines", body="required", idempotency="natural"),
    ("eval", "compare"): Endpoint("GET", "/api/v1/evaluation-baselines/{baseline_id}/comparison", ("baseline_id",), query=("candidate_run_id",)),
    ("release", "build"): Endpoint("POST", "/api/v1/agent-versions/{version_id}/releases", ("version_id",),
                                    body="required", idempotency="natural"),
    ("release", "show"): Endpoint("GET", "/api/v1/releases/{release_id}", ("release_id",)),
    ("release", "manifest"): Endpoint("GET", "/api/v1/releases/{release_id}/manifest", ("release_id",)),
    ("release", "verify"): Endpoint("POST", "/api/v1/release-bundles/verify", body="required"),
    ("release", "lifecycle"): Endpoint("GET", "/api/v1/releases/{release_id}/lifecycle", ("release_id",)),
    ("release", "suspend"): Endpoint("POST", "/api/v1/releases/{release_id}/suspend", ("release_id",),
                                         body="required", idempotency="key", etag="required"),
    ("release", "resume"): Endpoint("POST", "/api/v1/releases/{release_id}/resume", ("release_id",),
                                        body="required", idempotency="key", etag="required"),
    ("release", "revoke"): Endpoint("POST", "/api/v1/releases/{release_id}/revoke", ("release_id",),
                                        body="required", idempotency="key", etag="required"),
    ("release", "retire"): Endpoint("POST", "/api/v1/releases/{release_id}/retire", ("release_id",),
                                        body="required", idempotency="key", etag="required"),
    ("release", "revocations"): Endpoint("GET", "/api/v1/release-revocations",
                                             query=("release_id", "subject_kind", "cursor", "limit")),
    ("audit", "list"): Endpoint("GET", "/api/v1/administrative-audit",
                                    query=("event_type", "outcome", "target_type", "target_id", "cursor", "limit")),
    ("promote", "apply"): Endpoint("POST", "/api/v1/environments/{environment_id}/promotions", ("environment_id",),
                                     body="required", idempotency="key", etag="optional"),
    ("promote", "approval"): Endpoint("POST", "/api/v1/environments/{environment_id}/promotion-approvals", ("environment_id",),
                                        body="required"),
    ("promote", "approval-show"): Endpoint("GET", "/api/v1/environments/{environment_id}/promotion-approvals/{approval_id}", ("environment_id", "approval_id")),
    ("promote", "approve"): Endpoint("POST", "/api/v1/environments/{environment_id}/promotion-approvals/{approval_id}/approve", ("environment_id", "approval_id"),
                                       body="required", idempotency="natural"),
    ("deploy", "list"): Endpoint("GET", "/api/v1/deployments", query=("environment_id", "cursor", "limit")),
    ("deploy", "show"): Endpoint("GET", "/api/v1/deployments/{deployment_id}", ("deployment_id",)),
    ("deploy", "rollback"): Endpoint("POST", "/api/v1/deployments/{deployment_id}/rollback", ("deployment_id",),
                                      body="required", idempotency="key", etag="required"),
    ("deploy", "cohort"): Endpoint("GET", "/api/v1/cohorts/{cohort_id}", ("cohort_id",)),
    ("deploy", "quiescence"): Endpoint("POST", "/api/v1/cohorts/{cohort_id}/quiescence", ("cohort_id",), idempotency="natural"),
    ("deploy", "suspend"): Endpoint("POST", "/api/v1/cohorts/{cohort_id}/suspend", ("cohort_id",), body="required", idempotency="key", etag="required"),
    ("deploy", "resume"): Endpoint("POST", "/api/v1/cohorts/{cohort_id}/resume", ("cohort_id",), body="required", idempotency="key", etag="required"),
    ("deploy", "revoke"): Endpoint("POST", "/api/v1/cohorts/{cohort_id}/revoke", ("cohort_id",), body="required", idempotency="key", etag="required"),
    ("deploy", "retire"): Endpoint("POST", "/api/v1/cohorts/{cohort_id}/retire", ("cohort_id",), body="required", idempotency="key", etag="required"),
    ("deploy", "routing"): Endpoint("GET", "/api/v1/routing-generations/{routing_id}/targets", ("routing_id",)),
    ("run", "start"): Endpoint("POST", "/api/v1/deployments/{deployment_id}/runs", ("deployment_id",), body="required", idempotency="key"),
    ("run", "list"): Endpoint("GET", "/api/v1/runs",
                                query=("deployment_id", "release_id", "lifecycle", "cursor", "limit")),
    ("run", "show"): Endpoint("GET", "/api/v1/runs/{run_id}", ("run_id",)),
    ("run", "cancel"): Endpoint("POST", "/api/v1/runs/{run_id}/cancel", ("run_id",), body="optional", idempotency="natural"),
    ("run", "pause"): Endpoint("POST", "/api/v1/runs/{run_id}/pause", ("run_id",), body="optional", idempotency="natural"),
    ("run", "resume"): Endpoint("POST", "/api/v1/runs/{run_id}/resume", ("run_id",), idempotency="natural"),
    ("events", "list"): Endpoint("GET", "/api/v1/runs/{run_id}/events", ("run_id",), query=("cursor", "limit")),
    ("replay", "plan"): Endpoint("GET", "/api/v1/runs/{run_id}/replay/plan", ("run_id",), query=("mode",)),
    ("replay", "start"): Endpoint("POST", "/api/v1/runs/{run_id}/replay", ("run_id",), body="required"),
    ("interaction", "list"): Endpoint("GET", "/api/v1/interactions",
                                        query=("status", "run_id", "cursor", "limit")),
    ("interaction", "show"): Endpoint("GET", "/api/v1/interactions/{interaction_id}", ("interaction_id",)),
    ("interaction", "respond"): Endpoint("POST", "/api/v1/interactions/{interaction_id}/response", ("interaction_id",), body="required", idempotency="natural"),
    ("interaction", "expire"): Endpoint("POST", "/api/v1/interactions/{interaction_id}/expire", ("interaction_id",), idempotency="natural"),
    ("interaction", "cancel"): Endpoint("POST", "/api/v1/interactions/{interaction_id}/cancel", ("interaction_id",), idempotency="natural"),
    ("interaction", "revoke"): Endpoint("POST", "/api/v1/interactions/{interaction_id}/revoke", ("interaction_id",), idempotency="natural"),
    ("operation", "list"): Endpoint("GET", "/api/v1/operations",
                                      query=("session_id", "status", "cursor", "limit")),
    ("operation", "show"): Endpoint("GET", "/api/v1/operations/{operation_id}", ("operation_id",)),
    ("operation", "cancel"): Endpoint("POST", "/api/v1/operations/{operation_id}/cancel", ("operation_id",), idempotency="natural"),
    ("artifact", "list"): Endpoint("GET", "/api/v1/artifacts",
                                     query=("media_type", "classification", "cursor", "limit")),
    ("artifact", "show"): Endpoint("GET", "/api/v1/artifacts/{artifact_id}", ("artifact_id",)),
    ("artifact", "expire"): Endpoint("POST", "/api/v1/artifacts/{artifact_id}/expire", ("artifact_id",), idempotency="natural"),
    ("artifact", "validate"): Endpoint("POST", "/api/v1/artifacts/{artifact_id}/validation", ("artifact_id",), body="required", idempotency="natural"),
    ("artifact", "pins"): Endpoint("GET", "/api/v1/artifacts/{artifact_id}/pins", ("artifact_id",)),
    ("artifact", "pin"): Endpoint("POST", "/api/v1/artifacts/{artifact_id}/pins", ("artifact_id",), body="required", idempotency="natural"),
    ("artifact", "unpin"): Endpoint("DELETE", "/api/v1/artifacts/{artifact_id}/pins/{pin_id}", ("artifact_id", "pin_id"), idempotency="natural"),
    ("diagnostic", "openapi"): Endpoint("GET", "/api/v1/openapi.yaml", public=True,
                                         accept="application/yaml"),
}

CUSTOM_ENDPOINTS: dict[tuple[str, str], Endpoint] = {
    ("auth", "status"): Endpoint("GET", "/api/v1/auth/session"),
    ("context", "show"): Endpoint("GET", "/api/v1/auth/session"),
    ("deploy", "continue-waiting"): Endpoint("POST", "/api/v1/cohorts/{cohort_id}/quiescence",
                                                ("cohort_id",), idempotency="natural"),
    ("stream", "events"): Endpoint("GET", "/api/v1/runs/{run_id}/stream", ("run_id",),
                                     query=("cursor", "client")),
    ("artifact", "download"): Endpoint("GET", "/api/v1/artifacts/{artifact_id}/content", ("artifact_id",)),
    ("artifact", "upload"): Endpoint("POST", "/api/v1/artifacts", body="required",
                                       query=("classification", "redacted", "expires_at")),
}


def endpoint_parser(subparsers: Any, group: str, actions: list[str]) -> None:
    parser = subparsers.add_parser(group)
    children = parser.add_subparsers(dest="action", required=True)
    for action in actions:
        endpoint = ENDPOINTS[(group, action)]
        child = children.add_parser(action)
        for name in endpoint.ids:
            choices = ["suspend", "resume", "revoke"] if name == "cohort_action" else None
            child.add_argument(name, choices=choices)
        if endpoint.body != "absent":
            child.add_argument("--file", required=endpoint.body == "required",
                               help="JSON request file, or - for stdin")
        if endpoint.idempotency == "key":
            child.add_argument("--idempotency-key", required=True)
        if endpoint.etag != "none":
            child.add_argument("--if-match", required=endpoint.etag == "required", help="Current resource ETag")
        for name in endpoint.query:
            child.add_argument("--" + name.replace("_", "-"), required=(group, action, name) == ("eval", "compare", "candidate_run_id"))


class CliArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise CliError(USAGE, "INVALID_ARGUMENT", message)


def parser() -> argparse.ArgumentParser:
    root = CliArgumentParser(prog="agentworks", description="Dvara AgentWorks CI-friendly CLI")
    root.add_argument("--version", action="version", version=f"agentworks {VERSION}")
    root.add_argument("--output", choices=("human", "json"), default="human")
    root.add_argument("--profile")
    root.add_argument("--url")
    root.add_argument("--timeout", type=positive_timeout, default=30.0)
    root.add_argument("--retries", type=int, choices=range(0, 6), default=2)
    groups = root.add_subparsers(dest="group", required=True)

    profile = groups.add_parser("profile")
    profile_actions = profile.add_subparsers(dest="action", required=True)
    set_profile = profile_actions.add_parser("set")
    set_profile.add_argument("name"); set_profile.add_argument("--server", required=True)
    refs = set_profile.add_mutually_exclusive_group(required=True)
    refs.add_argument("--token-env"); refs.add_argument("--token-file")
    set_profile.add_argument("--workspace")
    profile_actions.add_parser("list")
    use = profile_actions.add_parser("use"); use.add_argument("name")
    remove = profile_actions.add_parser("remove"); remove.add_argument("name")
    auth = groups.add_parser("auth"); auth.add_subparsers(dest="action", required=True).add_parser("status")
    ctx = groups.add_parser("context"); ctx.add_subparsers(dest="action", required=True).add_parser("show")

    endpoint_parser(groups, "agent", ["list", "show", "create", "versions", "publish"])
    endpoint_parser(groups, "eval", ["datasets", "create-dataset", "purge-expired", "evaluators", "create-evaluator", "run", "status", "report", "cancel", "resume", "baselines", "create-baseline", "compare"])
    endpoint_parser(groups, "release", ["build", "show", "manifest", "verify", "lifecycle", "suspend",
                                          "resume", "revoke", "retire", "revocations"])
    endpoint_parser(groups, "audit", ["list"])
    endpoint_parser(groups, "promote", ["apply", "approval", "approval-show", "approve"])
    endpoint_parser(groups, "deploy", ["list", "show", "rollback", "cohort", "quiescence", "suspend", "resume", "revoke", "retire", "routing"])
    for action in ("continue-waiting", "cancel", "migrate", "retained-cost", "retention", "purge"):
        unavailable = groups.choices["deploy"]._subparsers._group_actions[0].choices
        item = unavailable.setdefault(action, CliArgumentParser(prog=f"agentworks deploy {action}"))
        item.add_argument("cohort_id")
    endpoint_parser(groups, "run", ["start", "list", "show", "cancel", "pause", "resume"])
    endpoint_parser(groups, "events", ["list"])
    stream = groups.add_parser("stream"); stream.add_argument("run_id"); stream.add_argument("--after")
    stream.add_argument("--cursor")
    stream.add_argument("--client", default=None); stream.add_argument("--max-reconnects", type=int,
                                                                        choices=range(0, 21), default=5)
    endpoint_parser(groups, "replay", ["plan", "start"])
    endpoint_parser(groups, "interaction", ["list", "show", "respond", "expire", "cancel", "revoke"])
    endpoint_parser(groups, "operation", ["list", "show", "cancel"])
    endpoint_parser(groups, "artifact", ["list", "show", "expire", "validate", "pins", "pin", "unpin"])
    artifact = groups.choices["artifact"]._subparsers._group_actions[0].choices
    download = CliArgumentParser(prog="agentworks artifact download")
    download.add_argument("artifact_id"); download.add_argument("destination"); download.add_argument("--force", action="store_true")
    artifact["download"] = download
    upload = CliArgumentParser(prog="agentworks artifact upload")
    upload.add_argument("source"); upload.add_argument("--media-type", default="application/octet-stream")
    upload.add_argument("--classification", choices=("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED"), default="INTERNAL")
    upload.add_argument("--redacted", action="store_true"); upload.add_argument("--expires-at")
    artifact["upload"] = upload
    diagnostic = groups.add_parser("diagnostic")
    diagnostics = diagnostic.add_subparsers(dest="action", required=True)
    diagnostics.add_parser("openapi"); diagnostics.add_parser("context")
    for group in ("test", "simulate"):
        local = groups.add_parser(group)
        local_sub = local.add_subparsers(dest="action", required=True)
        run = local_sub.add_parser("run")
        run.add_argument("--project", default="."); run.add_argument("command", nargs=argparse.REMAINDER)
    return root


def profile_command(args: argparse.Namespace) -> Any:
    config = read_config()
    if args.action == "set":
        if args.token_env and not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", args.token_env):
            raise CliError(USAGE, "INVALID_TOKEN_REFERENCE", "Token environment variable name is invalid")
        config["profiles"][args.name] = {key: value for key, value in {
            "url": args.server.rstrip("/"), "tokenEnv": args.token_env,
            "tokenFile": args.token_file, "workspace": args.workspace}.items() if value}
        write_config(config)
        return {"profile": args.name, "authSource": "environment" if args.token_env else "file"}
    if args.action == "use":
        if args.name not in config["profiles"]:
            raise CliError(USAGE, "PROFILE_NOT_FOUND", f"Profile does not exist: {args.name}")
        config["currentProfile"] = args.name; write_config(config)
        return {"currentProfile": args.name}
    if args.action == "remove":
        if args.name not in config["profiles"]:
            raise CliError(USAGE, "PROFILE_NOT_FOUND", f"Profile does not exist: {args.name}")
        del config["profiles"][args.name]
        if config.get("currentProfile") == args.name: config["currentProfile"] = "default"
        write_config(config); return {"removed": args.name}
    return {"currentProfile": config.get("currentProfile"), "profiles": [
        {"name": name, "url": value.get("url"), "workspace": value.get("workspace"),
         "authSource": "environment" if value.get("tokenEnv") else "file" if value.get("tokenFile") else "none"}
        for name, value in sorted(config["profiles"].items())]}


def local_command(args: argparse.Namespace) -> Any:
    project = Path(args.project).resolve()
    command = args.command
    if command and command[0] == "--": command = command[1:]
    if not command:
        wrapper = project / ("mvnw.cmd" if os.name == "nt" else "mvnw")
        command = [str(wrapper), "-B", "-ntp", "test"]
        if args.group == "simulate": command.append("-Dagentworks.simulation=true")
    child_environment = os.environ.copy()
    child_environment.pop("AGENTWORKS_TOKEN", None)
    config = read_config()
    profile_name = args.profile or os.environ.get("AGENTWORKS_PROFILE") or config.get("currentProfile", "default")
    token_env = config["profiles"].get(profile_name, {}).get("tokenEnv")
    if token_env:
        child_environment.pop(token_env, None)
    for name in tuple(child_environment):
        if name.startswith("AGENTWORKS_") and SECRET_KEY.search(name):
            child_environment.pop(name, None)
    try:
        with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
            result = subprocess.run(command, cwd=project, env=child_environment, check=False,
                                    stdout=stdout, stderr=stderr)
            def tail(stream: BinaryIO) -> str:
                size = os.fstat(stream.fileno()).st_size
                stream.seek(max(0, size - 65536))
                return stream.read().decode("utf-8", "replace")
            log_tail = tail(stdout) + tail(stderr)
    except OSError as exc:
        raise CliError(DOMAIN_EXIT[args.group], "LOCAL_COMMAND_UNAVAILABLE", str(exc)) from None
    if result.returncode:
        raise CliError(DOMAIN_EXIT[args.group], args.group.upper() + "_FAILED",
                       f"Command failed with status {result.returncode}", detail={"logTail": log_tail})
    return {"status": "PASSED", "executable": Path(command[0]).name, "project": str(project)}


def stream_events(args: argparse.Namespace, client: ApiClient) -> None:
    last = args.after
    stream_client = args.client or secrets.token_hex(16)
    reconnects = 0
    while reconnects <= args.max_reconnects:
        query_values = {"client": stream_client}
        if args.cursor is not None and last is None:
            query_values["cursor"] = args.cursor
        query = urllib.parse.urlencode(query_values)
        headers = {"Accept": "text/event-stream"}
        if last: headers["Last-Event-ID"] = last
        run_id = urllib.parse.quote(args.run_id, safe="")
        request = urllib.request.Request(f"{client.ctx.url}/api/v1/runs/{run_id}/stream?{query}",
                                         headers={**headers, "Authorization": f"Bearer {client.ctx.token}",
                                                  "User-Agent": f"agentworks-cli/{VERSION}"})
        try:
            with urllib.request.urlopen(request, timeout=client.timeout) as response:
                event: dict[str, str] = {}
                for raw in response:
                    line = raw.decode("utf-8").rstrip("\r\n")
                    if not line:
                        if event:
                            try: data = json.loads(event.get("data", "null"))
                            except json.JSONDecodeError:
                                raise CliError(STREAM_RESYNC, "INVALID_STREAM_EVENT",
                                               "The server sent an invalid SSE JSON event") from None
                            item = {"id": event.get("id"), "event": event.get("event", "message"), "data": data}
                            emit(envelope("stream", item), args.output)
                            if event.get("id"): last = event["id"]
                            if event.get("event") == "stream-end": return
                            if event.get("event") in {"stream-error", "reconnect"}: break
                            event = {}
                        continue
                    if line.startswith(":"): continue
                    key, _, value = line.partition(":")
                    event[key] = value.lstrip()
        except urllib.error.HTTPError as exc:
            if exc.code == 204: return
            if exc.code in {401, 403}: raise CliError(AUTH, "STREAM_UNAUTHORIZED", "Stream authorization failed", status=exc.code) from None
            if exc.code in {409, 410}: raise CliError(STREAM_RESYNC, "STREAM_RESYNC_REQUIRED", "The saved event position cannot be resumed", status=exc.code) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            pass
        reconnects += 1
        if reconnects > args.max_reconnects: break
        time.sleep(min(0.2 * (2 ** (reconnects - 1)), 2.0))
    raise CliError(NETWORK, "STREAM_UNAVAILABLE", "Stream reconnect budget exhausted")


def semantic_failure(group: str, action: str, data: Any) -> tuple[int, str, str] | None:
    """Interpret only typed terminal outcomes whose OpenAPI schema makes success/failure unambiguous."""
    if not isinstance(data, dict):
        return None
    if (group, action) == ("release", "verify") and data.get("trusted") is False:
        kind = data.get("code") if isinstance(data.get("code"), str) and data["code"] else "RELEASE_UNTRUSTED"
        return RELEASE_FAILED, kind, "Release bundle verification was refused"
    if (group, action) in {("promote", "apply"), ("deploy", "rollback")} and data.get("outcome") == "DENIED":
        return PROMOTION_FAILED, "PROMOTION_DENIED", "Promotion policy denied the requested change"
    if group != "eval":
        return None
    if action == "compare":
        if data.get("comparable") is False:
            return (EVALUATION_FAILED, "EVALUATION_NOT_COMPARABLE",
                    "Evaluation comparison is not comparable")
        if data.get("regression") is True:
            return EVALUATION_FAILED, "EVALUATION_REGRESSION", "Evaluation comparison reports a regression"
    if action not in {"run", "status", "report", "cancel", "resume"}:
        return None
    report = data if isinstance(data.get("run"), dict) and isinstance(data.get("results"), list) else None
    run = report["run"] if report is not None else data
    status = run.get("status") if isinstance(run, dict) else None
    if status in {"FAILED", "CANCELLED", "BUDGET_EXCEEDED"}:
        return EVALUATION_FAILED, f"EVALUATION_{status}", f"Evaluation ended with status {status}"
    if report is not None and any(isinstance(item, dict) and item.get("passed") is False
                                  for item in report["results"]):
        return EVALUATION_FAILED, "EVALUATION_RESULT_FAILED", "One or more evaluation results failed"
    if (action in {"status", "cancel"} and isinstance(run.get("completedResults"), int)
            and isinstance(run.get("passedResults"), int)
            and run["passedResults"] < run["completedResults"]):
        return EVALUATION_FAILED, "EVALUATION_RESULT_FAILED", "One or more evaluation results failed"
    return None


def execute(args: argparse.Namespace) -> Any:
    if args.group == "profile": return profile_command(args)
    if args.group in {"test", "simulate"}: return local_command(args)
    if args.group == "diagnostic" and args.action == "context":
        ctx = context(args, token_required=False)
        return {"profile": ctx.profile, "url": ctx.url, "workspace": ctx.workspace,
                "authenticated": bool(ctx.token), "token": "[REDACTED]" if ctx.token else None}
    endpoint = ENDPOINTS.get((args.group, getattr(args, "action", None)))
    ctx = context(args, token_required=not (endpoint and endpoint.public))
    client = ApiClient(ctx, timeout=args.timeout, retries=args.retries)
    if args.group == "auth" or args.group == "context":
        status, principal, _ = client.request("GET", "/api/v1/auth/session")
        return {"status": status, "profile": ctx.profile, "url": ctx.url, "configuredWorkspace": ctx.workspace,
                "principal": principal}
    if args.group == "stream":
        stream_events(args, client); return None
    if args.group == "deploy" and args.action in {
            "continue-waiting", "cancel", "migrate", "retained-cost", "retention", "purge"}:
        if args.action == "continue-waiting":
            cohort_id = urllib.parse.quote(args.cohort_id, safe="")
            _, data, _ = client.request("POST", f"/api/v1/cohorts/{cohort_id}/quiescence",
                                        retryable=True)
            return {"operation": "CONTINUE_WAITING", "result": data}
        if args.action == "retained-cost":
            raise CliError(LIFECYCLE_CONFLICT, "CAPABILITY_UNAVAILABLE",
                           "No canonical retained-runtime cost projection is available; no value was estimated")
        raise CliError(LIFECYCLE_CONFLICT, "CAPABILITY_UNAVAILABLE",
                       f"The {args.action} cohort operation is not part of the OSS 1.0 server authority; no state changed")
    if args.group == "artifact" and args.action == "download":
        artifact_id = urllib.parse.quote(args.artifact_id, safe="")
        _, data, _ = client.request("GET", f"/api/v1/artifacts/{artifact_id}/content",
                                    accept="application/octet-stream")
        target = Path(args.destination)
        if target.exists() and not args.force:
            raise CliError(USAGE, "OUTPUT_EXISTS", f"Refusing to overwrite artifact destination: {target}")
        try:
            mode = "wb" if args.force else "xb"
            with target.open(mode) as output: output.write(data)
        except OSError as exc:
            raise CliError(USAGE, "OUTPUT_UNAVAILABLE", f"Cannot write artifact destination: {exc}") from None
        return {"artifactId": args.artifact_id, "destination": str(target), "bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest()}
    if args.group == "artifact" and args.action == "upload":
        try: data = Path(args.source).read_bytes()
        except OSError as exc: raise CliError(USAGE, "INVALID_INPUT", f"Cannot read artifact: {exc}") from None
        digest = hashlib.sha256(data).hexdigest()
        query_values = {"classification": args.classification, "redacted": str(args.redacted).lower()}
        if args.expires_at is not None:
            query_values["expiresAt"] = args.expires_at
        query = urllib.parse.urlencode(query_values)
        _, result, _ = client.request("POST", "/api/v1/artifacts?" + query, binary=data,
                                      headers={"Content-Type": args.media_type, "X-Artifact-SHA256": digest})
        return {"sha256": digest, "result": result}
    endpoint = ENDPOINTS[(args.group, args.action)]
    values = {name: urllib.parse.quote(str(getattr(args, name)), safe="") for name in endpoint.ids}
    path = endpoint.path.format(**values)
    def query_wire_name(name: str) -> str:
        first, *rest = name.split("_")
        return first + "".join(item.capitalize() for item in rest)
    query = {query_wire_name(name): getattr(args, name)
             for name in endpoint.query if getattr(args, name, None) is not None}
    if query: path += "?" + urllib.parse.urlencode(query)
    headers = {}
    if endpoint.idempotency == "key":
        if not args.idempotency_key or "\r" in args.idempotency_key or "\n" in args.idempotency_key:
            raise CliError(USAGE, "INVALID_IDEMPOTENCY_KEY", "Idempotency key must be a non-empty header value")
        headers["Idempotency-Key"] = args.idempotency_key
    if endpoint.etag != "none" and args.if_match is not None:
        if not args.if_match or "\r" in args.if_match or "\n" in args.if_match:
            raise CliError(USAGE, "INVALID_ETAG", "ETag must be a non-empty header value")
        headers["If-Match"] = args.if_match
    status, data, response_headers = client.request(
        endpoint.method, path, body=read_json_file(getattr(args, "file", None)), headers=headers,
        accept=endpoint.accept, retryable=endpoint.idempotency in {"key", "natural"})
    if isinstance(data, bytes): data = data.decode("utf-8", "replace")
    selected_headers = {}
    for public_name, wire_name in (("idempotentReplayed", "Idempotent-Replayed"), ("etag", "ETag"),
                                   ("location", "Location"), ("retryAfter", "Retry-After")):
        value = next((v for k, v in response_headers.items() if k.lower() == wire_name.lower()), None)
        if value is not None: selected_headers[public_name] = value
    semantic = semantic_failure(args.group, args.action, data)
    if semantic is not None:
        exit_code, kind, message = semantic
        raise CliError(exit_code, kind, message, status=status,
                       detail={"result": data, "response": selected_headers})
    return {"status": status, "result": data, "idempotencyKey": getattr(args, "idempotency_key", None),
            "response": selected_headers}


def main(argv: list[str] | None = None) -> int:
    command = "unknown"
    raw_args = list(sys.argv[1:] if argv is None else argv)
    output = "json" if any(raw_args[index:index + 2] == ["--output", "json"]
                           for index in range(len(raw_args))) else "human"
    try:
        args = parser().parse_args(raw_args)
        command = args.group + (" " + args.action if getattr(args, "action", None) else "")
        output = args.output
        result = execute(args)
        if result is not None:
            emit(envelope(command, result, redact_data=command != "diagnostic openapi"), output)
        return OK
    except CliError as exc:
        code = exc.code
        if code == API and command.split()[0] in DOMAIN_EXIT: code = DOMAIN_EXIT[command.split()[0]]
        emit(envelope(command, error={"code": exc.kind, "message": str(exc), "detail": exc.detail},
                      status=exc.status, exit_code=code), output, sys.stderr)
        return code
    except KeyboardInterrupt:
        emit(envelope(command, error={"code": "INTERRUPTED", "message": "Interrupted", "detail": None},
                      exit_code=130), output, sys.stderr)
        return 130
