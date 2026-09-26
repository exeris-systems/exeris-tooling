#!/usr/bin/env bash
#
# Holds `.agents/hooks/hooks.yaml`'s `deny-irreversible` rule to what it is for: every command on the
# first list is refused and every command on the second is let through. Each is run through the
# rendered dispatcher exactly as Claude Code runs it, a `PreToolUse` event on stdin, so the case
# fails on the rule the session loads rather than on a copy of its patterns.
#
# The second list matters as much as the first. A pattern widened by accident denies ordinary work
# and gets the hook switched off, which is a larger loss than the one command it meant to stop.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec python3 - "$ROOT" <<'PY'
import json, os, shutil, subprocess, sys, tempfile

root = sys.argv[1]
dispatch = os.path.join(root, ".agents", "hooks", "bin", "dispatch.py")

DENY = [
    "git push origin main",
    "git push origin HEAD:main",
    "git push --force origin feat/x",
    "git push -f origin feat/x",
    "git push --mirror origin",
    "git push origin --delete feat/x",
    "git push --tags",
    "git tag v1.0.0",
    "git reset --hard HEAD~1",
    "git branch -D feat/x",
    "git branch -d feat/x",
    "git clean -fd",
    "git worktree remove ../wt",
    "gh pr merge 12",
    "gh pr close 12",
    "gh release create v1.0.0",
    "mvn -B deploy",
    "npm publish",
    "docker push ghcr.io/exeris-systems/x:1",
]
ALLOW = [
    "git push --force-with-lease origin feat/x",
    "git push origin feat/x",
    "git tag -l",
    "git branch -a",
    "git clean -n",
    "git reset --soft HEAD~1",
    "gh pr view 12",
    "gh pr create --fill",
    "mvn -B verify",
    "npm test",
]

state = tempfile.mkdtemp(prefix="hook-deny-check-")
env = dict(os.environ, CLAUDE_PROJECT_DIR=state)
shutil.copytree(os.path.join(root, ".agents"), os.path.join(state, ".agents"))

def decide(command):
    event = {"session_id": "hook-deny-check", "hook_event_name": "PreToolUse", "tool_name": "Bash",
             "tool_input": {"command": command}, "cwd": state}
    run = subprocess.run([sys.executable, dispatch, "--hook", "deny-irreversible", "--vendor", "claude",
                          "--event", "pre-tool", "--on-error", "deny"],
                         input=json.dumps(event), capture_output=True, text=True, env=env, cwd=state)
    return "deny" if run.returncode == 2 else "allow" if run.returncode == 0 else f"exit {run.returncode}"

fails = [f"'{c}' was {d}, expected deny" for c in DENY if (d := decide(c)) != "deny"]
fails += [f"'{c}' was {d}, expected allow" for c in ALLOW if (d := decide(c)) != "allow"]
shutil.rmtree(state, ignore_errors=True)

for f in fails:
    print(f"hook-deny-check: {f}", file=sys.stderr)
if fails:
    sys.exit(1)
print(f"hook-deny-check: OK — {len(DENY)} commands denied, {len(ALLOW)} let through.")
PY
