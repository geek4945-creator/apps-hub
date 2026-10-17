import json, os, re, subprocess, sys, unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))
import refresh_apps  # noqa: E402


def load(p):
    return json.load(open(os.path.join(ROOT, p), encoding="utf-8"))


class AppsJson(unittest.TestCase):
    def test_valid_and_in_sync(self):
        doc = load("apps.json")
        refresh_apps.validate(doc)
        self.assertEqual(doc, load("docs/apps.json"))
        self.assertEqual(doc, load("android/app/src/main/assets/apps.json"))

    def test_live_first(self):
        order = [refresh_apps.STATUSES.index(a["status"]) for a in load("apps.json")["apps"]]
        self.assertEqual(order, sorted(order))

    def test_links_are_https_and_no_secrets(self):
        text = open(os.path.join(ROOT, "apps.json"), encoding="utf-8").read()
        for a in json.loads(text)["apps"]:
            for k in ("apk_url", "web_url", "repo_url"):
                if a[k]:
                    self.assertTrue(a[k].startswith("https://"), (a["id"], k))
        self.assertIsNone(re.search(r"gh[opsu]_[A-Za-z0-9]{20,}|ghs_|AKIA[0-9A-Z]{12}", text))

    def test_every_source_is_listed(self):
        ids = {a["id"] for a in load("apps.json")["apps"]}
        self.assertEqual(ids, {s["id"] for s in load("sources.json")["apps"]})

    def test_validate_rejects_bad_entries(self):
        base = {"id": "x", "name": "X", "description": "d", "status": "Live", "platform": "Web", "version": "web",
                "apk_url": None, "web_url": None, "repo_url": None, "last_updated": None}
        with self.assertRaises(ValueError):
            refresh_apps.validate({"apps": [base]})  # live without links
        with self.assertRaises(ValueError):
            refresh_apps.validate({"apps": [dict(base, status="Idea", web_url="https://x.test/")]})  # planned with link
        with self.assertRaises(ValueError):
            refresh_apps.validate({"apps": [dict(base, status="Nope")]})

    def test_offline_refresh_is_idempotent(self):
        before = open(os.path.join(ROOT, "apps.json"), "rb").read()
        subprocess.run([sys.executable, os.path.join(ROOT, "tools", "refresh_apps.py"), "--offline"], check=True, capture_output=True)
        self.assertEqual(before, open(os.path.join(ROOT, "apps.json"), "rb").read())


if __name__ == "__main__":
    unittest.main()
