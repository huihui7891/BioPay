"""Aggregate published release downloads and publish a Shields endpoint on badges."""

import argparse
import base64
import json
import os
from pathlib import Path
import re
import subprocess

BRANCH = "badges"
FILE = "downloads.json"


def api(path, payload=None, paginate=False, missing_ok=False, method=None):
    command = ["gh", "api", path]
    if method:
        command += ["--method", method]
    if paginate:
        command += ["--paginate", "--slurp"]
    if payload is not None:
        command += ["--input", "-"]
    result = subprocess.run(
        command, input=json.dumps(payload) if payload is not None else None,
        text=True, capture_output=True,
    )
    if result.returncode:
        if missing_ok and "HTTP 404" in result.stderr:
            return None
        raise RuntimeError(f"GitHub API request failed for {path}: {result.stderr.strip()}")
    return json.loads(result.stdout)


def downloads(repository):
    pages = api(f"repos/{repository}/releases?per_page=100", paginate=True)
    return sum(
        asset["download_count"]
        for page in pages for release in page if not release["draft"]
        for asset in release["assets"]
    )


def publish(repository, content):
    reference = api(f"repos/{repository}/git/ref/heads/{BRANCH}", missing_ok=True)
    if reference is None:
        # An independent history keeps generated data out of source commits and release notes.
        tree = api(f"repos/{repository}/git/trees", {
            "tree": [{"path": FILE, "mode": "100644", "type": "blob", "content": content}],
        })
        commit = api(f"repos/{repository}/git/commits", {
            "message": "chore: initialize download badge", "tree": tree["sha"], "parents": [],
        })
        api(f"repos/{repository}/git/refs", {
            "ref": f"refs/heads/{BRANCH}", "sha": commit["sha"],
        })
        return

    current = api(f"repos/{repository}/contents/{FILE}?ref={BRANCH}", missing_ok=True)
    if current is not None and base64.b64decode(current["content"]).decode("utf-8") == content:
        print("Download badge is already current.")
        return
    payload = {
        "message": "chore: update download badge", "branch": BRANCH,
        "content": base64.b64encode(content.encode("utf-8")).decode("ascii"),
    }
    if current is not None:
        payload["sha"] = current["sha"]
    api(f"repos/{repository}/contents/{FILE}", payload, method="PUT")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--publish", action="store_true")
    args = parser.parse_args()
    source = os.environ.get("SOURCE_REPOSITORY", "kiriashi/BioPay")
    module = os.environ.get("MODULE_REPOSITORY", "Xposed-Modules-Repo/io.github.kiriashi.biopay")
    for repository in (source, module):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("Invalid repository name")
    if source == module:
        raise ValueError("Source and module repositories must differ")

    # Both requests must succeed before writing or publishing a replacement count.
    counts = {repository: downloads(repository) for repository in (source, module)}
    total = sum(counts.values())
    content = json.dumps({
        "schemaVersion": 1, "label": "downloads", "message": f"{total:,}", "color": "blue",
    }, indent=2) + "\n"
    args.output.write_text(content, encoding="utf-8")
    for repository, count in counts.items():
        print(f"{repository}: {count:,}")
    print(f"Total release asset downloads: {total:,}")
    if args.publish:
        publish(source, content)


if __name__ == "__main__":
    main()
