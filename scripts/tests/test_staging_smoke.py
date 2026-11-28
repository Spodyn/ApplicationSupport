"""Release smoke must check actual UI assets and anonymous API denial without credentials."""
import io
from pathlib import Path
import sys
import unittest
from unittest.mock import patch
import urllib.error

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "infra/staging"))
from smoke import check


class Response:
    status = 200

    def __init__(self, body):
        self.body = body

    def __enter__(self):
        return self

    def __exit__(self, *_):
        pass

    def read(self, _limit):
        return self.body


class StagingSmokeTests(unittest.TestCase):
    def public(self, *, leaked=None, asset=b"fixture", html=None):
        requests = []

        class Client:
            def open(self, request, timeout):
                path = request.full_url.removeprefix("https://staging.example.test")
                requests.append((path, request.data, timeout))
                if path == "/login":
                    return Response(html if html is not None else b'>STAGING</div><script src="/_next/static/fixture.js"></script><link href="/_next/static/fixture.css">')
                if path.startswith("/_next/static/"):
                    return Response(asset)
                if path == leaked:
                    return Response(b'{"items":[]}')
                raise urllib.error.HTTPError(request.full_url, 404 if path == "/actuator/health" else 401, "fixture", {}, None)

        with patch("smoke.urllib.request.build_opener", return_value=Client()), patch("smoke.ssl.create_default_context"), patch("sys.stdout", new_callable=io.StringIO), patch.object(Path, "read_text", side_effect=AssertionError("No credential access in public smoke")):
            check("https://staging.example.test")
        return requests

    def test_public_release_smoke_checks_assets_and_denies_each_protected_api_without_a_secret(self):
        requests = self.public()
        self.assertEqual([r[0] for r in requests], ["/login", "/_next/static/fixture.css", "/_next/static/fixture.js", "/actuator/health", "/api/v1/auth/me", "/api/v1/cases", "/api/v1/statistics/records"])
        self.assertTrue(all(body is None and timeout == 20 for _, body, timeout in requests))

    def test_every_release_checks_public_assets_and_running_identity_before_recording_success(self):
        deploy = (Path(__file__).resolve().parents[2] / "infra/staging/deploy.sh").read_text(encoding="utf-8")
        public_check = deploy.index('--public-only')
        later_branch = deploy.index('if [ "$consumed" = f ]; then', deploy.index('for service in api web'))
        recorded = deploy.index('> "$root/current-release"')
        self.assertLess(public_check, later_branch)
        self.assertLess(deploy.index('"$revision" = "$USI_EXPECTED_REVISION"'), public_check)
        self.assertLess(deploy.index('Authenticated release smoke NOT RUN'), recorded)
        self.assertIn('USI_EXPECTED_REVISION=${USI_EXPECTED_REVISION:-$(basename -- "$release")}', deploy)
        self.assertIn('"$state/smoke/password"', deploy)
        self.assertIn('"NOT RUN"', deploy)

    def test_any_exposed_protected_api_fails_closed(self):
        for path in ["/api/v1/auth/me", "/api/v1/cases", "/api/v1/statistics/records"]:
            with self.subTest(path=path), self.assertRaises(RuntimeError):
                self.public(leaked=path)

    def test_missing_assets_or_staging_marker_fail(self):
        for arguments in [{"asset": b""}, {"html": b"production"}]:
            with self.subTest(arguments=arguments), self.assertRaises(AssertionError):
                self.public(**arguments)

    def test_invalid_origins_fail_before_network_or_credential_access(self):
        for origin in ["http://staging.example.test", "https://user:pass@staging.example.test", "https://staging.example.test/?token=fixture", "https://staging.example.test/#fragment", "https://staging.example.test/path"]:
            with self.subTest(origin=origin), patch("smoke.urllib.request.build_opener") as opener, self.assertRaises(ValueError):
                check(origin)
            opener.assert_not_called()

    def test_half_configured_authentication_cannot_be_treated_as_public_success(self):
        for args in [("fixture@example.test", None), (None, Path("never-read"))]:
            with self.subTest(args=args), self.assertRaises(ValueError):
                check("https://staging.example.test", *args)


if __name__ == "__main__":
    unittest.main()
