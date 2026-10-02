"""Sync listing files and the canonical signed APK to the LSPosed repository."""

import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


def gh(*args, token=None, payload=None):
    env = os.environ.copy()
    if token is not None:
        env["GH_TOKEN"] = token
    command = ["gh", *args]
    if payload is not None:
        command += ["--input", "-"]
    return subprocess.check_output(
        command, input=json.dumps(payload) if payload is not None else None,
        text=True, env=env,
    ).strip()


def api(path, payload=None, method=None):
    args = ["api", path]
    if method:
        args += ["--method", method]
    return json.loads(gh(*args, payload=payload))


def sync_docs(repository, files, version):
    repo = api(f"repos/{repository}")
    branch = repo["default_branch"]
    ref = api(f"repos/{repository}/git/ref/heads/{branch}")
    head = ref["object"]["sha"]
    commit = api(f"repos/{repository}/git/commits/{head}")
    entries = api(f"repos/{repository}/git/trees/{commit['tree']['sha']}")["tree"]
    current = {entry["path"]: entry for entry in entries}
    changes = []
    for name, content in files.items():
        encoded = content.encode("utf-8")
        blob_sha = hashlib.sha1(
            f"blob {len(encoded)}\0".encode() + encoded
        ).hexdigest()
        entry = current.get(name)
        if entry and entry["sha"] == blob_sha and entry["mode"] == "100644":
            continue
        blob = api(f"repos/{repository}/git/blobs", {
            "content": base64.b64encode(encoded).decode(), "encoding": "base64",
        })
        changes.append({"path": name, "mode": "100644", "type": "blob", "sha": blob["sha"]})
    if not changes:
        print("README and SUMMARY already match.")
        return
    tree = api(f"repos/{repository}/git/trees", {
        "base_tree": commit["tree"]["sha"], "tree": changes,
    })
    new_commit = api(f"repos/{repository}/git/commits", {
        "message": f"docs: sync BioPay listing for {version}",
        "tree": tree["sha"], "parents": [head],
    })
    # A concurrent edit causes a non-fast-forward error instead of being overwritten.
    api(f"repos/{repository}/git/refs/heads/{branch}", {
        "sha": new_commit["sha"], "force": False,
    }, method="PATCH")
    print(f"Listing updated: {new_commit['sha'][:7]}")


def main():
    if not os.environ.get("GH_TOKEN"):
        raise RuntimeError(
            "Set the MODULE_REPO_TOKEN Actions secret with Contents write access "
            "to the LSPosed module repository."
        )
    source = os.environ["SOURCE_REPOSITORY"]
    target = os.environ["MODULE_REPOSITORY"]
    tag = os.environ["RELEASE_TAG"]
    code = os.environ["VERSION_CODE"]
    if source == target or not re.fullmatch(r"v\d+\.\d+\.\d+", tag) or not code.isdigit():
        raise ValueError("Invalid source repository or stable release version")
    version = tag[1:]
    store_tag = f"{code}-{version}"
    asset_name = f"BioPay-{tag}.apk"
    source_token = os.environ["SOURCE_TOKEN"]
    release = json.loads(gh(
        "release", "view", tag, "--repo", source,
        "--json", "body,isDraft,isPrerelease,assets", token=source_token,
    ))
    if release["isDraft"] or release["isPrerelease"]:
        raise ValueError("Only published stable releases can be synced")
    if not any(asset["name"] == asset_name for asset in release["assets"]):
        raise ValueError(f"Signed release asset missing: {asset_name}")

    with tempfile.TemporaryDirectory(prefix="biopay-store-") as directory:
        work = Path(directory)
        gh("release", "download", tag, "--repo", source, "--pattern", asset_name,
           "--dir", str(work), token=source_token)
        apk = work / asset_name
        notes = work / "release-notes.md"
        notes.write_text(release["body"], encoding="utf-8")
        # Use immutable release URLs so listing images match the published APK.
        readme = Path("README.md").read_text(encoding="utf-8")
        readme = readme.replace('src="images/', f'src="https://raw.githubusercontent.com/{source}/{tag}/images/')
        readme = readme.replace("](LICENSE)", f"](https://github.com/{source}/blob/{tag}/LICENSE)")
        summary = re.search(r"<p>([^<]+)</p>", readme)
        if summary is None:
            raise ValueError("README header must contain the module summary in a <p> element")
        releases = json.loads(gh("api", f"repos/{target}/releases", "--paginate", "--slurp"))
        requested_version = (int(code), tuple(map(int, version.split("."))))
        for page in releases:
            for item in page:
                match = re.fullmatch(r"(\d+)-(\d+)\.(\d+)\.(\d+)", item["tag_name"])
                if match and not item["draft"] and not item["prerelease"]:
                    published_version = (int(match[1]), tuple(map(int, match.groups()[1:])))
                    if published_version > requested_version:
                        print(f"Newer module release exists: {item['tag_name']}; skipping older sync.")
                        return
        existing = next((item for page in releases for item in page
                         if item["tag_name"] == store_tag), None)
        if existing and existing["prerelease"]:
            raise ValueError("The matching module release is marked as prerelease")
        asset_exists = existing and any(a["name"] == asset_name for a in existing["assets"])
        if asset_exists:
            check_dir = work / "existing"
            check_dir.mkdir()
            gh("release", "download", store_tag, "--repo", target,
               "--pattern", asset_name, "--dir", str(check_dir))
            if hashlib.sha256(apk.read_bytes()).digest() != hashlib.sha256((check_dir / asset_name).read_bytes()).digest():
                raise ValueError("Published module APK differs from source; refusing to replace it")
        elif existing and not existing["draft"]:
            raise ValueError("Published module release has no matching APK; inspect it before modifying")
        sync_docs(target, {"README.md": readme, "SUMMARY": summary[1].strip() + "\n"}, version)
        if existing is None:
            gh("release", "create", store_tag, "--repo", target, "--draft",
               "--title", f"BioPay {version}", "--notes-file", str(notes))
        if not asset_exists:
            gh("release", "upload", store_tag, str(apk), "--repo", target)
        # Updating the notes along with publishing also notifies the module indexer.
        if existing is None or existing["draft"] or existing["body"] != release["body"]:
            gh("release", "edit", store_tag, "--repo", target, "--draft=false",
               "--title", f"BioPay {version}", "--notes-file", str(notes), "--latest")
        url = f"https://github.com/{target}/releases/tag/{store_tag}"
        print(f"Module repository release: {url}")
        summary_file = os.environ.get("GITHUB_STEP_SUMMARY")
        if summary_file:
            with open(summary_file, "a", encoding="utf-8") as output:
                output.write(f"Synced README/SUMMARY and signed APK: [BioPay {version}]({url})\n")
                output.write("The LSPosed website updates asynchronously after publication.\n")


if __name__ == "__main__":
    main()
