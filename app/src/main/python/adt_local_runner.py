import contextlib
from collections import deque
import io
import json
import os
import runpy
import sys


MAX_CAPTURED_OUTPUT_CHARS = 32_000
MAX_RETURNED_OUTPUT_CHARS = 32_000


class _BoundedCapture(io.TextIOBase):
    """Tail buffer with a hard memory bound for redirected Python output."""

    def __init__(self, limit=MAX_CAPTURED_OUTPUT_CHARS):
        super().__init__()
        self._limit = max(1, int(limit))
        self._chunks = deque()
        self._length = 0
        self.truncated = False

    def write(self, value):
        value = str(value)
        if not value:
            return 0
        if len(value) >= self._limit:
            self._chunks.clear()
            self._chunks.append(value[-self._limit:])
            self._length = self._limit
            self.truncated = True
            return len(value)
        self._chunks.append(value)
        self._length += len(value)
        while self._length > self._limit:
            overflow = self._length - self._limit
            head = self._chunks[0]
            if len(head) <= overflow:
                self._chunks.popleft()
                self._length -= len(head)
            else:
                self._chunks[0] = head[overflow:]
                self._length -= overflow
            self.truncated = True
        return len(value)

    def flush(self):
        return None

    @property
    def encoding(self):
        return "utf-8"

    def getvalue(self):
        return "".join(self._chunks)


def _render_capture(name, capture):
    value = capture.getvalue()
    if capture.truncated:
        value = (
            f"[{name} truncated; showing the last {len(capture.getvalue())} characters]\n"
            + value
        )
    return value


def _tail_text(value, limit=MAX_RETURNED_OUTPUT_CHARS):
    if len(value) <= limit:
        return value
    marker = "[combined output truncated; showing the tail]\n"
    tail_length = max(0, limit - len(marker))
    tail = value[-tail_length:] if tail_length else ""
    return (marker + tail)[:limit]


def run_script(project_root, entrypoint, args_json="[]", site_packages="", installed_packages_json="[]"):
    project_root = os.path.realpath(project_root)
    entrypoint = os.path.realpath(entrypoint)
    if entrypoint != project_root and not entrypoint.startswith(project_root + os.sep):
        raise ValueError("Entrypoint must stay inside the project workspace")

    args = json.loads(args_json or "[]")
    installed_packages = json.loads(installed_packages_json or "[]")
    old_sys_path = sys.path[:]
    if site_packages:
        site_packages = os.path.realpath(site_packages)
        if site_packages.startswith(project_root + os.sep) and site_packages not in sys.path:
            sys.path.insert(0, site_packages)

    old_argv = sys.argv[:]
    old_cwd = os.getcwd()
    stdout = _BoundedCapture()
    stderr = _BoundedCapture()
    try:
        os.chdir(project_root)
        sys.argv = [entrypoint] + [str(arg) for arg in args]
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            runpy.run_path(entrypoint, run_name="__main__")
    finally:
        sys.argv = old_argv
        sys.path[:] = old_sys_path
        os.chdir(old_cwd)

    out = _render_capture("stdout", stdout)
    err = _render_capture("stderr", stderr)
    sections = []
    if out.strip():
        sections.append(out.rstrip())
    if err.strip():
        sections.append("[stderr]\n" + err.rstrip())
    if installed_packages:
        sections.append("[dependencies]\n" + ", ".join(installed_packages))
    return _tail_text("\n".join(sections))
