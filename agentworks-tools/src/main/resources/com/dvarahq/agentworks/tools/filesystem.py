"""Fixed sandbox filesystem protocol v1. Receives data in argv, never code or a host path."""
import base64
import hashlib
import json
import os
import stat
import sys
import uuid

MAX_BYTES = 65536


def regular(fd):
    facts = os.fstat(fd)
    if not stat.S_ISREG(facts.st_mode) or facts.st_nlink != 1:
        raise ValueError("FILESYSTEM_TYPE_DENIED")
    return facts


def read(parent, name):
    fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    try:
        facts = regular(fd)
        if facts.st_size > MAX_BYTES:
            raise ValueError("TOOL_OUTPUT_LIMIT")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            result = stream.read(MAX_BYTES + 1)
        if len(result) > MAX_BYTES:
            raise ValueError("TOOL_OUTPUT_LIMIT")
        return result
    finally:
        os.close(fd)


def main(request):
    path = request["path"]
    operation = request["operation"]
    parts = path.split("/")
    root = path == "." and operation in ("list", "stat")
    if not root and (len(path) > 1024 or any(not p or p in (".", "..") or "\x00" in p or "\\" in p for p in parts)):
        raise ValueError("FILESYSTEM_PATH_DENIED")
    parent = os.open("/workspace", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        for part in parts[:-1]:
            next_fd = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            os.close(parent)
            parent = next_fd
        name = parts[-1]
        if operation == "read":
            content = read(parent, name)
            return {"encoding": "base64", "body": base64.b64encode(content).decode("ascii"), "bytes": len(content)}
        if operation in ("write", "patch"):
            content = base64.b64decode(request["content"], validate=True)
            if len(content) > MAX_BYTES:
                raise ValueError("TOOL_INPUT_INVALID")
            try:
                facts = os.stat(name, dir_fd=parent, follow_symlinks=False)
                if not stat.S_ISREG(facts.st_mode) or facts.st_nlink != 1:
                    raise ValueError("FILESYSTEM_TYPE_DENIED")
            except FileNotFoundError:
                if operation == "patch":
                    raise ValueError("FILESYSTEM_PRECONDITION_FAILED")
            if operation == "patch":
                digest = "sha256:" + hashlib.sha256(read(parent, name)).hexdigest()
                if digest != request["expectedDigest"]:
                    raise ValueError("FILESYSTEM_PRECONDITION_FAILED")
            temporary = ".agentworks-" + uuid.uuid4().hex
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
            try:
                with os.fdopen(fd, "wb", closefd=False) as stream:
                    stream.write(content)
                    stream.flush()
                    os.fsync(fd)
                os.rename(temporary, name, src_dir_fd=parent, dst_dir_fd=parent)
                os.fsync(parent)
            finally:
                os.close(fd)
                try:
                    os.unlink(temporary, dir_fd=parent)
                except FileNotFoundError:
                    pass
            return {"bytes": len(content), "digest": "sha256:" + hashlib.sha256(content).hexdigest()}
        if operation == "stat":
            facts = os.stat(name, dir_fd=parent, follow_symlinks=False)
            if not (stat.S_ISREG(facts.st_mode) and facts.st_nlink == 1 or stat.S_ISDIR(facts.st_mode)):
                raise ValueError("FILESYSTEM_TYPE_DENIED")
            return {"type": "directory" if stat.S_ISDIR(facts.st_mode) else "file", "bytes": facts.st_size}
        if operation == "list":
            directory = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            try:
                entries = []
                with os.scandir(directory) as scan:
                    for entry in scan:
                        if len(entries) == 256:
                            raise ValueError("TOOL_OUTPUT_LIMIT")
                        entries.append(entry.name)
                return {"entries": sorted(entries)}
            finally:
                os.close(directory)
        raise ValueError("TOOL_INPUT_INVALID")
    finally:
        os.close(parent)


try:
    print(json.dumps({"result": main(json.loads(sys.argv[1]))}, separators=(",", ":")))
except ValueError as failure:
    code = str(failure)
    permitted = {"FILESYSTEM_PATH_DENIED", "FILESYSTEM_TYPE_DENIED", "FILESYSTEM_PRECONDITION_FAILED",
                 "TOOL_INPUT_INVALID", "TOOL_OUTPUT_LIMIT"}
    print(json.dumps({"error": code if code in permitted else "TOOL_INPUT_INVALID"}))
except Exception:
    print('{"error":"FILESYSTEM_OPERATION_FAILED"}')
