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


def validate_results(response, expected_count):
    """OSV querybatch returns {} for a clean package; errors are never clean.

    Pagination is deliberately unsupported: any nonempty continuation token means
    the audit is incomplete, so fail rather than silently omitting advisories.
    """
    if not isinstance(response, dict) or set(response) != {"results"}:
        raise RuntimeError("invalid OSV response envelope")
    results = response["results"]
    if not isinstance(results, list) or len(results) != expected_count:
        raise RuntimeError("incomplete OSV response")
    for result in results:
        if not isinstance(result, dict) or not set(result).issubset({"vulns", "next_page_token"}):
            raise RuntimeError("invalid or error-bearing OSV result")
        if "next_page_token" in result:
            token = result["next_page_token"]
            if not isinstance(token, str) or token:
                raise RuntimeError("invalid or incomplete paginated OSV result")
        vulns = result.get("vulns", [])
        if not isinstance(vulns, list):
            raise RuntimeError("invalid OSV vulnerability list")
        for vulnerability in vulns:
            if not isinstance(vulnerability, dict):
                raise RuntimeError("invalid OSV vulnerability")
            advisory_id = vulnerability.get("id")
            if (not isinstance(advisory_id, str) or not advisory_id
                    or any(c.isspace() or ord(c) < 32 for c in advisory_id)):
                raise RuntimeError("missing or invalid OSV advisory ID")
            if "error" in vulnerability or "status" in vulnerability:
                raise RuntimeError("error-bearing OSV vulnerability")
            if "modified" in vulnerability and (not isinstance(vulnerability["modified"], str)
                                                or not vulnerability["modified"].strip()):
                raise RuntimeError("invalid OSV advisory modification value")
    return results


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
    results = validate_results(post({"queries": queries}), len(packages))
    findings = []
    for (name, version), result in zip(packages, results):
        if result.get("vulns"):
            findings.append({"package": name, "version": version,
                             "advisories": [v["id"] for v in result["vulns"]]})
    print(json.dumps({"ecosystem": "Maven", "packages": len(packages), "findings": findings}, indent=2))
    return 1 if findings else 0


def cli():
    try:
        return main()
    except Exception:
        print("Dependency audit unavailable or invalid; failing closed.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(cli())
