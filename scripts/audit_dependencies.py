#!/usr/bin/env python3
"""Fail-closed public OSV audit of Maven's *resolved* dependency tree (including tests).
No credentials, NVD database, or package upload; sends only public Maven coordinates.
Run: mvn dependency:tree -DoutputType=json -DoutputFile=target/dependencies.json
     python3 scripts/audit_dependencies.py target/dependencies.json
"""
import json
import sys
import time
import urllib.error
import urllib.request


def post(payload):
    request = urllib.request.Request("https://api.osv.dev/v1/querybatch",
                                     json.dumps(payload).encode(), {"Content-Type": "application/json"})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 503) or attempt == 2:
                raise
            delay = min(120, int(error.headers.get("Retry-After", "20")))
            time.sleep(max(1, delay))
    raise RuntimeError("OSV unavailable")


def main():
    with open(sys.argv[1], encoding="utf-8") as source:
        tree = json.load(source)
    coordinates = set()

    def walk(node):
        for child in node.get("children", []):
            coordinates.add((child["groupId"] + ":" + child["artifactId"], child["version"]))
            walk(child)

    walk(tree)
    if not coordinates:
        raise RuntimeError("empty resolved dependency tree")
    packages = sorted(coordinates)
    queries = [{"package": {"ecosystem": "Maven", "name": name}, "version": version}
               for name, version in packages]
    results = post({"queries": queries})["results"]
    if not isinstance(results, list) or len(results) != len(packages) or any(not isinstance(r, dict) for r in results):
        raise RuntimeError("incomplete OSV response")
    findings = []
    for (name, version), result in zip(packages, results):
        if result.get("vulns"):
            findings.append({"package": name, "version": version,
                             "advisories": [v["id"] for v in result["vulns"]]})
    print(json.dumps({"ecosystem": "Maven", "packages": len(packages), "findings": findings}, indent=2))
    return 1 if findings else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception:
        print("Dependency audit unavailable or invalid; failing closed.", file=sys.stderr)
        sys.exit(2)
