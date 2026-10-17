#!/usr/bin/env python3
"""Rebuild apps.json from sources.json by asking the GitHub API for each app's newest release.

Usage:  python3 tools/refresh_apps.py [--offline] [--no-check]
  --offline   do not touch the network; just re-validate and re-copy the existing apps.json
  --no-check  skip the HEAD requests that verify web / APK links
Env: GITHUB_TOKEN or GH_TOKEN (optional but avoids rate limits; needed for private repos).
Falls back to the `gh` CLI token if available.

Writes apps.json (canonical), docs/apps.json and android/app/src/main/assets/apps.json.
If one app's lookup fails, its previous entry in apps.json is kept (never dropped).
"""
import json, os, re, shutil, subprocess, sys, urllib.request, urllib.error
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
API = "https://api.github.com"
REQUIRED = ["id", "name", "description", "status", "platform", "version", "apk_url", "web_url", "repo_url", "last_updated"]
STATUSES = ["Live", "In progress", "Idea", "Held"]  # display order: live first


def token():
    t = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if t:
        return t
    try:
        return subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, timeout=10).stdout.strip() or None
    except Exception:
        return None


TOKEN = None


def api(path):
    req = urllib.request.Request(API + path, headers={"Accept": "application/vnd.github+json", "User-Agent": "apps-hub-refresh"})
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def head_ok(url):
    try:
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "apps-hub-refresh"})
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status < 400
    except Exception:
        try:  # some hosts reject HEAD
            req = urllib.request.Request(url, headers={"User-Agent": "apps-hub-refresh", "Range": "bytes=0-0"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.status < 400
        except Exception:
            return False


def newest_release_with(releases, pattern):
    rx = re.compile(pattern)
    best = None
    for rel in releases:
        if rel.get("draft"):
            continue
        for asset in rel.get("assets", []):
            if rx.search(asset["name"]):
                key = rel.get("published_at") or rel.get("created_at") or ""
                if best is None or key > best[0]:
                    best = (key, rel, asset)
                break
    return best


def version_of(rel):
    tag = rel["tag_name"]
    v = tag[1:] if re.match(r"^v\d", tag) else tag
    return v


def build_planned(src):
    """Not-yet-published item: no download/open links, repo only if one exists."""
    repo = src.get("repo")
    return {
        "id": src["id"], "name": src["name"], "description": src["description"],
        "status": src["status"], "status_note": src.get("status_note", ""),
        "color": src.get("color", "#475569"), "tags": src.get("tags", []),
        "repo_url": ("https://github.com/" + repo) if repo else None,
        "web_url": None, "platform": src.get("platform_override", "TBD"), "version": src["status"],
        "apk_url": None, "apk_name": None, "apk_private": False, "extra_downloads": [], "last_updated": None,
    }


def build_entry(src, prev, check):
    if src.get("status", "Live") != "Live":
        return build_planned(src)
    repo = src["repo"]
    e = {
        "id": src["id"], "name": src["name"], "description": src["description"],
        "status": "Live", "status_note": "",
        "color": src.get("color", "#4F46E5"), "tags": src.get("tags", []),
        "repo_url": "https://github.com/" + repo,
        "web_url": src.get("web_url"),
    }
    if src.get("notes"):
        e["notes"] = src["notes"]
    info = api("/repos/" + repo)
    private = bool(info.get("private"))
    updated = info.get("pushed_at")
    apk_url = apk_name = version = None
    extras = []
    apk_private = False
    if src.get("apk_asset"):
        releases = api("/repos/%s/releases?per_page=30" % repo)
        hit = newest_release_with(releases, src["apk_asset"])
        if hit:
            published, rel, asset = hit
            version = version_of(rel)
            apk_name = asset["name"]
            apk_private = private
            apk_url = rel["html_url"] if private else asset["browser_download_url"]
            updated = published or updated
            e["release_url"] = rel["html_url"]
            for x in src.get("extra_apks", []):
                m = re.compile(x["asset"])
                for a in rel.get("assets", []):
                    if m.search(a["name"]):
                        extras.append({"label": x["label"], "url": rel["html_url"] if private else a["browser_download_url"]})
                        break
        elif src.get("fallback"):
            fb = src["fallback"]
            version, apk_url, apk_name = fb["version"], fb["apk_url"], fb.get("apk_name")
            e["release_url"] = "https://github.com/%s/releases/tag/%s" % (repo, fb.get("tag", "v" + version))
            print("  note: %s has no release yet, using fallback" % src["id"])
        else:
            print("  warn: no release asset matching %s in %s" % (src["apk_asset"], repo), file=sys.stderr)
    for x in src.get("extra_downloads", []):
        extras.append({"label": x["label"], "url": x["url"]})
    plat = src.get("platform_override")
    if not plat:
        plat = "Android APK + Web" if (apk_url and e["web_url"]) else ("Android APK" if apk_url else "Web")
    e.update({
        "platform": plat,
        "version": version or "web",
        "apk_url": apk_url, "apk_name": apk_name, "apk_private": apk_private,
        "extra_downloads": extras,
        "last_updated": updated,
    })
    if check:
        e["web_ok"] = head_ok(e["web_url"]) if e["web_url"] else None
        e["apk_ok"] = (head_ok(apk_url) if (apk_url and not apk_private) else None)
        for k in ("web_ok", "apk_ok"):
            if e[k] is False:
                print("  warn: %s %s failed link check" % (src["id"], k), file=sys.stderr)
    return e


def validate(doc):
    ids = set()
    for a in doc["apps"]:
        for k in REQUIRED:
            if k not in a:
                raise ValueError("%s missing %s" % (a.get("id"), k))
        if a["id"] in ids:
            raise ValueError("duplicate id " + a["id"])
        ids.add(a["id"])
        if a["status"] not in STATUSES:
            raise ValueError("%s has unknown status %r" % (a["id"], a["status"]))
        if a["status"] == "Live" and not a["apk_url"] and not a["web_url"]:
            raise ValueError("%s is Live but has neither APK nor web link" % a["id"])
        if a["status"] != "Live" and (a["apk_url"] or a["web_url"]):
            raise ValueError("%s is %s but has download/open links" % (a["id"], a["status"]))
    return doc


def write(doc):
    text = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
    targets = [os.path.join(ROOT, "apps.json"), os.path.join(ROOT, "docs", "apps.json"),
               os.path.join(ROOT, "android/app/src/main/assets/apps.json")]
    for t in targets:
        os.makedirs(os.path.dirname(t), exist_ok=True)
        with open(t, "w", encoding="utf-8") as f:
            f.write(text)


def main():
    global TOKEN
    offline = "--offline" in sys.argv
    check = "--no-check" not in sys.argv and not offline
    path = os.path.join(ROOT, "apps.json")
    prev_doc = json.load(open(path)) if os.path.exists(path) else {"apps": []}
    prev = {a["id"]: a for a in prev_doc.get("apps", [])}
    if offline:
        write(validate(prev_doc))
        print("offline: re-validated %d apps" % len(prev))
        return 0
    TOKEN = token()
    sources = json.load(open(os.path.join(ROOT, "sources.json")))
    apps, failed = [], []
    for src in sources["apps"]:
        print("-", src["id"])
        try:
            apps.append(build_entry(src, prev.get(src["id"]), check))
        except Exception as ex:  # keep last known good entry
            print("  ERROR %s: %s" % (src["id"], ex), file=sys.stderr)
            failed.append(src["id"])
            if src["id"] in prev:
                apps.append(prev[src["id"]])
    doc = {
        "schema": 1,
        "owner": sources["owner"],
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "apps": apps,
    }
    apps.sort(key=lambda a: STATUSES.index(a["status"]))  # stable: live first, source order kept inside a status
    validate(doc)
    # Only touch files when something other than the timestamp changed (keeps nightly commits meaningful).
    def strip(d):
        return {k: v for k, v in d.items() if k != "generated_at"}
    if prev_doc.get("apps") and strip(prev_doc) == strip(doc):
        print("no changes in %d apps" % len(apps))
        write(prev_doc)  # make sure copies are in sync, keep old timestamp
    else:
        write(doc)
        print("wrote apps.json with %d apps" % len(apps))
    if failed:
        print("lookup failed for: " + ", ".join(failed), file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
