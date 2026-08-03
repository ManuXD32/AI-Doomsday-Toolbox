import contextlib
import io
import json
import os
import runpy
import sys


def run_script(project_root, entrypoint, args_json="[]", site_packages="", installed_packages_json="[]"):
    project_root = os.path.realpath(project_root)
    entrypoint = os.path.realpath(entrypoint)
    if entrypoint != project_root and not entrypoint.startswith(project_root + os.sep):
        raise ValueError("Entrypoint must stay inside the project workspace")

    args = json.loads(args_json or "[]")
    installed_packages = json.loads(installed_packages_json or "[]")
    if site_packages:
        site_packages = os.path.realpath(site_packages)
        if site_packages.startswith(project_root + os.sep) and site_packages not in sys.path:
            sys.path.insert(0, site_packages)

    old_argv = sys.argv[:]
    old_cwd = os.getcwd()
    stdout = io.StringIO()
    stderr = io.StringIO()
    try:
        os.chdir(project_root)
        sys.argv = [entrypoint] + [str(arg) for arg in args]
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            runpy.run_path(entrypoint, run_name="__main__")
    finally:
        sys.argv = old_argv
        os.chdir(old_cwd)

    out = stdout.getvalue()
    err = stderr.getvalue()
    sections = []
    if out.strip():
        sections.append(out.rstrip())
    if err.strip():
        sections.append("[stderr]\n" + err.rstrip())
    if installed_packages:
        sections.append("[dependencies]\n" + ", ".join(installed_packages))
    return "\n".join(sections)
