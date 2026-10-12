#!/usr/bin/env python3
"""Offline regression tests for fail-closed helper behavior. Never requests a token."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


def load(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


extract = load("create_query_vector")
audit = load("audit_dependencies")
verify = load("verify_results")


class ToolTests(unittest.TestCase):
    def test_extractor_checks_every_vector_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            data, output = Path(directory)/"data.json", Path(directory)/"query.json"
            documents = [{"DescriptionVector": [0.1]*1536} for _ in range(50)]
            data.write_text(json.dumps(documents))
            with patch.object(extract, "EXPECTED", hashlib.sha256(data.read_bytes()).hexdigest()), patch.object(sys, "argv", ["tool", str(data), str(output)]), contextlib.redirect_stdout(io.StringIO()):
                extract.main()
                saved = output.read_bytes()
                with self.assertRaises(FileExistsError):
                    extract.main()
                self.assertEqual(saved, output.read_bytes())
            documents[-1]["DescriptionVector"] = [1, 2]
            data.write_text(json.dumps(documents))
            with patch.object(extract, "EXPECTED", hashlib.sha256(data.read_bytes()).hexdigest()), patch.object(sys, "argv", ["tool", str(data), str(output)]):
                with self.assertRaises(ValueError):
                    extract.main()
            with patch.object(sys, "argv", ["tool", str(data), str(output)]):
                with self.assertRaises(ValueError):
                    extract.main()

    def test_audit_fails_for_empty_malformed_incomplete_and_advisory_results(self):
        with tempfile.TemporaryDirectory() as directory:
            tree = Path(directory)/"tree.json"
            with patch.object(sys, "argv", ["tool", str(tree)]), contextlib.redirect_stdout(io.StringIO()):
                tree.write_text("{}")
                with self.assertRaises(RuntimeError):
                    audit.main()
                tree.write_text(json.dumps({"children": [{"groupId": "public", "artifactId": "package", "version": "1"}]}))
                for invalid in [{"results": []}, {"results": [None]}, {"results": "bad"}]:
                    with patch.object(audit, "post", return_value=invalid), self.assertRaises(Exception):
                        audit.main()
                with patch.object(audit, "post", return_value={"results": [{"vulns": [{"id": "TEST-advisory"}]}]}):
                    self.assertEqual(1, audit.main())
                with patch.object(audit, "post", return_value={"results": [{}]}):
                    self.assertEqual(0, audit.main())

    def test_result_gate_rejects_missing_cases_unknown_hits_and_wrong_ranks(self):
        vectors = [[1.0, 0.0]+[0.0]*1534, [0.8, 0.6]+[0.0]*1534]
        docs = [{"HotelId": str(i), "DescriptionVector": vectors[0] if i == 0 else vectors[1]} for i in range(50)]
        raw = json.dumps(docs).encode()
        row = {"algorithm": "ivf", "metric": "COS", "status": "PASS", "fixtureDocuments": 50, "totalDocuments": 50,
               "hits": [{"_id": "hotel:0", "score": 1}, {"_id": "hotel:1", "score": .8}]}
        summary = {"summary": {"attempted": 1, "succeeded": 1, "failed": 0}}
        with patch.object(verify, "EXPECTED", hashlib.sha256(raw).hexdigest()):
            lines = json.dumps(row)+"\n"+json.dumps(summary)
            self.assertTrue(verify.verify(raw, vectors[0], lines, "ivf")[0]["selfMatch"])
            with self.assertRaises(ValueError):
                verify.verify(raw, vectors[0], lines, "all")
            row["hits"].reverse()
            with self.assertRaises(ValueError):
                verify.verify(raw, vectors[0], json.dumps(row)+"\n"+json.dumps(summary), "ivf")
            row["hits"][0]["_id"] = "unknown"
            with self.assertRaises(KeyError):
                verify.verify(raw, vectors[0], json.dumps(row)+"\n"+json.dumps(summary), "ivf")


if __name__ == "__main__":
    unittest.main()
