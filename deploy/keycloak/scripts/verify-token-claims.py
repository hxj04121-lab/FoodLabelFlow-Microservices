#!/usr/bin/env python3
"""End-to-end token claim verification for the local demo realm (G1-M4.1).

The script performs a real OpenID Connect login against a running Keycloak
using the public ``spectrace-web`` client and the *authorisation code flow with
PKCE* - exactly the flow the browser client is configured for. Direct access
grants and implicit flow are deliberately not used, so this verifies the
configuration that is actually committed instead of a test-only shortcut.

For every demo user declared in ../realm/spectrace-realm.json it checks that the
decoded Access Token carries:

  * ``org_id``   - the user's single organisation, from the trusted user
                   attribute (never from a request parameter);
  * ``org_type`` - SUPPLIER or MANUFACTURER only;
  * ``roles``    - the effective realm roles, including the assigned business
                   role codes.

It also asserts the cross-organisation isolation properties: the two supplier
organisations and the two manufacturer organisations do not share claims, and a
same-organisation maker/checker pair differs in roles. Signature verification is
out of scope here: services verify the JWT against the realm JWKS (G1-M4.2
starter, G1-M3 gateway).

Requires: a reachable Keycloak (see ../docker-compose.keycloak.yml) and the
Python standard library only. This is a local/test verification tool - it is not
executed as part of `mvn verify`, see ../README.md for the run commands.

Exit code 0 means every assertion passed.
"""

import argparse
import base64
import hashlib
import html
import http.cookiejar
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
KEYCLOAK_DIR = os.path.dirname(SCRIPT_DIR)
REALM_FILE = os.path.join(KEYCLOAK_DIR, "realm", "spectrace-realm.json")
SAMPLE_FILE = os.path.join(KEYCLOAK_DIR, "claims", "sample-access-token-claims.json")

# Keycloak grants these built-in realm roles to every user, so the realm-role
# mapper writes them into `roles` as well. They carry no SpecTrace permission.
KEYCLOAK_DEFAULT_ROLES = [
    "default-roles-spectrace",
    "offline_access",
    "uma_authorization",
]

# Roles that create or change business state (the "maker" side of BR-06) and
# roles that decide on a pending version (the "checker" side). The two sets are
# intentionally disjoint: no demo user can both create and approve. Whether a
# specific actor may approve a specific version is enforced by the Label
# Workflow service (subject vs created_by_subject), not by the realm.
MAKER_ROLES = [
    "LABEL_OFFICER",
    "ADMIN_DATA_MAINTENANCE",
    "CHANGE_MANAGER",
]
CHECKER_ROLES = [
    "APPROVER",
]

REDACTED_FIELDS = [
    "sub",
    "sid",
    "jti",
    "exp",
    "iat",
    "auth_time",
    "nonce",
    "scope",
    "signature",
]


class VerificationError(Exception):
    """Raised when a claim does not match the committed realm contract."""


class _LoginFormParser(HTMLParser):
    """Collects the forms of the Keycloak login page."""

    def __init__(self):
        HTMLParser.__init__(self)
        self.forms = []
        self._current = None

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if tag == "form":
            self._current = {
                "action": attributes.get("action", ""),
                "method": (attributes.get("method") or "get").lower(),
                "inputs": [],
            }
            self.forms.append(self._current)
        elif tag == "input" and self._current is not None:
            name = attributes.get("name")
            if name:
                self._current["inputs"].append(name)

    def handle_endtag(self, tag):
        if tag == "form":
            self._current = None


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Keeps the 302 of the login POST so its Location can be inspected."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class _LocalDevCookiePolicy(http.cookiejar.DefaultCookiePolicy):
    """Keycloak marks AUTH_SESSION_ID as Secure even when it runs on plain HTTP in
    development mode. The standard library policy then refuses to send that
    cookie over http:// and the login POST fails with `cookie_not_found`, so this
    local-only tool ignores the Secure flag towards the local dev server.
    Staging and production run over HTTPS."""

    def return_ok_secure(self, cookie, request):
        return True


def _read_json(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def _b64url_decode(value):
    padding = "=" * (-len(value) % 4)
    return base64.urlsafe_b64decode(value + padding)


def decode_claims(unverified_jwt):
    """Decodes the JWT payload. The signature is intentionally not verified."""

    parts = unverified_jwt.split(".")
    if len(parts) != 3:
        raise VerificationError("expected a three-part JWT, got %d part(s)" % len(parts))
    try:
        return json.loads(_b64url_decode(parts[1]).decode("utf-8"))
    except ValueError as error:
        raise VerificationError("JWT payload is not valid JSON: %s" % error)


def _expect_response(response, what, expected_status=200):
    status = getattr(response, "code", getattr(response, "status", None))
    if status != expected_status:
        raise VerificationError(
            "%s returned HTTP %s, expected %s" % (what, status, expected_status)
        )
    return response


def wait_until_ready(issuer, timeout_seconds):
    discovery_url = issuer + "/.well-known/openid-configuration"
    deadline = time.time() + timeout_seconds
    last_error = None
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(discovery_url, timeout=5) as response:
                if response.getcode() == 200:
                    return json.loads(response.read().decode("utf-8"))
        except Exception as error:  # noqa: BLE001 - readiness probe
            last_error = error
        time.sleep(2)
    raise VerificationError(
        "Keycloak did not become ready at %s within %ss (%s)"
        % (issuer, timeout_seconds, last_error)
    )


def _pick_login_form(page):
    parser = _LoginFormParser()
    parser.feed(page)
    for form in parser.forms:
        if form["method"] == "post" and "username" in form["inputs"]:
            return form
    raise VerificationError(
        "could not find the Keycloak login form in the auth response "
        "(is the browser flow still the default username/password flow?)"
    )


def fetch_tokens(issuer, client_id, redirect_uri, username, password, timeout):
    """Runs auth code + PKCE and returns the token endpoint response."""

    auth_endpoint = issuer + "/protocol/openid-connect/auth"
    token_endpoint = issuer + "/protocol/openid-connect/token"

    code_verifier = secrets.token_urlsafe(64)
    digest = hashlib.sha256(code_verifier.encode("ascii")).digest()
    code_challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode("ascii")
    state = secrets.token_urlsafe(16)

    authorize_url = auth_endpoint + "?" + urllib.parse.urlencode(
        {
            "client_id": client_id,
            "redirect_uri": redirect_uri,
            "response_type": "code",
            "scope": "openid",
            "state": state,
            "nonce": secrets.token_urlsafe(16),
            "code_challenge": code_challenge,
            "code_challenge_method": "S256",
        }
    )

    cookie_jar = http.cookiejar.CookieJar(_LocalDevCookiePolicy())
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(cookie_jar), _NoRedirectHandler()
    )

    login_page_response = _expect_response(
        opener.open(
            urllib.request.Request(authorize_url, headers={"Accept": "text/html"}),
            timeout=timeout,
        ),
        "authorization request",
    )
    login_page = login_page_response.read().decode("utf-8")
    form = _pick_login_form(login_page)

    form_action = urllib.parse.urljoin(auth_endpoint, html.unescape(form["action"]))
    login_request = urllib.request.Request(
        form_action,
        data=urllib.parse.urlencode(
            {"username": username, "password": password, "credentialId": ""}
        ).encode("utf-8"),
    )
    login_request.add_header("Content-Type", "application/x-www-form-urlencoded")
    try:
        login_response = opener.open(login_request, timeout=timeout)
    except urllib.error.HTTPError as error:
        login_response = error

    location = login_response.headers.get("Location")
    if not location:
        raise VerificationError(
            "login for %s did not redirect (HTTP %s). Check the demo password "
            "and that the user is enabled with no required actions."
            % (username, login_response.code)
        )

    query = urllib.parse.parse_qs(urllib.parse.urlparse(location).query)
    if state not in query.get("state", []):
        raise VerificationError("authorization response returned an unexpected state")
    if "code" not in query:
        raise VerificationError(
            "authorization response has no code: %s" % location.split("?", 1)[0]
        )

    token_request = urllib.request.Request(
        token_endpoint,
        data=urllib.parse.urlencode(
            {
                "grant_type": "authorization_code",
                "client_id": client_id,
                "code": query["code"][0],
                "redirect_uri": redirect_uri,
                "code_verifier": code_verifier,
            }
        ).encode("utf-8"),
    )
    token_request.add_header("Content-Type", "application/x-www-form-urlencoded")
    token_response = urllib.request.urlopen(token_request, timeout=timeout)
    return json.loads(_expect_response(token_response, "token request").read().decode("utf-8"))


def expected_users(realm):
    """Reads the demo users and their expected claims from the realm file."""

    users = []
    for user in realm["users"]:
        attributes = user.get("attributes", {})
        org_ids = attributes.get("org_id", [])
        org_types = attributes.get("org_type", [])
        if len(org_ids) != 1 or len(org_types) != 1:
            raise VerificationError(
                "realm user %s must have exactly one org_id and one org_type"
                % user["username"]
            )
        users.append(
            {
                "username": user["username"],
                "org_id": org_ids[0],
                "org_type": org_types[0],
                "business_roles": sorted(user.get("realmRoles", [])),
                "password": user["credentials"][0]["value"],
            }
        )
    return users


def check_claims(sample, claims, strict_extras):
    """Compares live Access Token claims with the expected sample."""

    problems = []
    for claim in ("org_id", "org_type"):
        actual = claims.get(claim)
        if actual != sample[claim]:
            problems.append(
                "%s: expected %s=%r but the token has %r"
                % (sample["username"], claim, sample[claim], actual)
            )

    roles_claim = claims.get("roles")
    if roles_claim is None:
        roles_claim = []
    if not isinstance(roles_claim, list):
        problems.append(
            "%s: roles claim must be an array, got %r" % (sample["username"], roles_claim)
        )
        roles_claim = []

    business = sorted(set(roles_claim) & set(ALL_BUSINESS_ROLES))
    if business != sorted(sample["business_roles"]):
        problems.append(
            "%s: expected business roles %s but the token carries %s"
            % (sample["username"], sorted(sample["business_roles"]), business)
        )

    extras = sorted(set(roles_claim) - set(ALL_BUSINESS_ROLES))
    unexpected = [role for role in extras if role not in KEYCLOAK_DEFAULT_ROLES]
    if unexpected:
        problems.append(
            "%s: token carries non-business roles outside the documented Keycloak "
            "defaults: %s" % (sample["username"], unexpected)
        )
    if strict_extras and sorted(roles_claim) != sorted(sample["access_token"]["roles"]):
        problems.append(
            "%s: strict comparison failed, token roles %s != sample roles %s"
            % (sample["username"], sorted(roles_claim), sorted(sample["access_token"]["roles"]))
        )

    realm_access = claims.get("realm_access") or {}
    realm_roles = realm_access.get("roles") or []
    missing_mirror = [role for role in sample["business_roles"] if role not in realm_roles]
    if missing_mirror:
        problems.append(
            "%s: realm_access.roles does not mirror the business roles: missing %s"
            % (sample["username"], missing_mirror)
        )
    return problems


def cross_organisation_problems(samples, claims_by_user):
    """Asserts the BR-11 isolation properties the demo data must support."""

    problems = []
    org_ids = {}
    for sample in samples:
        org_ids.setdefault(sample["org_id"], set()).add(sample["org_type"])

    suppliers = sorted(
        org_id
        for org_id, types in org_ids.items()
        if "SUPPLIER" in types
    )
    manufacturers = sorted(
        org_id for org_id, types in org_ids.items() if "MANUFACTURER" in types
    )
    if len(suppliers) != 2:
        problems.append("expected exactly two SUPPLIER organisations, found %s" % suppliers)
    if len(manufacturers) != 2:
        problems.append(
            "expected exactly two MANUFACTURER organisations, found %s" % manufacturers
        )
    if set(suppliers) & set(manufacturers):
        problems.append("an organisation is used with both organisation types")

    if len(org_ids) != 4:
        problems.append("expected four distinct organisations, found %d" % len(org_ids))

    # Same organisation, different identity: the maker and the checker must not
    # be distinguishable by organisation but must differ in role.
    for org_id in list(suppliers) + list(manufacturers):
        members = [
            sample for sample in samples if sample["org_id"] == org_id
        ]
        makers = [
            member
            for member in members
            if set(member["business_roles"]) & set(MAKER_ROLES)
        ]
        checkers = [
            member
            for member in members
            if set(member["business_roles"]) & set(CHECKER_ROLES)
        ]
        if not makers or not checkers:
            problems.append(
                "organisation %s needs a maker %s and a checker %s for the BR-06 "
                "demo" % (org_id, MAKER_ROLES, CHECKER_ROLES)
            )
            continue
        for member in members:
            roles = set(member["business_roles"])
            if roles & set(MAKER_ROLES) and roles & set(CHECKER_ROLES):
                problems.append(
                    "%s holds both a maker role and a checker role (%s)"
                    % (member["username"], sorted(roles))
                )
        for maker in makers:
            for checker in checkers:
                if maker["username"] == checker["username"]:
                    problems.append(
                        "%s would be both maker and checker" % maker["username"]
                    )
                live_maker = claims_by_user.get(maker["username"]) or {}
                live_checker = claims_by_user.get(checker["username"]) or {}
                if live_maker.get("org_id") != live_checker.get("org_id"):
                    problems.append(
                        "%s and %s must share the organisation claim"
                        % (maker["username"], checker["username"])
                    )

    no_role_users = [sample for sample in samples if not sample["business_roles"]]
    if not no_role_users:
        problems.append("the demo data needs a user without business roles")
    for sample in no_role_users:
        live = claims_by_user.get(sample["username"]) or {}
        inherited = sorted(set(live.get("roles") or []) & set(ALL_BUSINESS_ROLES))
        if inherited:
            problems.append(
                "%s has no assigned business role but the token carries %s"
                % (sample["username"], inherited)
            )
    return problems


def redacted_sample(sample, claims):
    return {
        "username": sample["username"],
        "org_id": sample["org_id"],
        "org_type": sample["org_type"],
        "business_roles": sorted(sample["business_roles"]),
        "access_token": {
            "iss": claims.get("iss"),
            "typ": claims.get("typ"),
            "azp": claims.get("azp"),
            "org_id": claims.get("org_id"),
            "org_type": claims.get("org_type"),
            "roles": sorted(claims.get("roles") or []),
            "realm_access": {"roles": sorted((claims.get("realm_access") or {}).get("roles") or [])},
        },
    }


ALL_BUSINESS_ROLES = []


def main(argv):
    global ALL_BUSINESS_ROLES

    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--base-url",
        default=os.environ.get("KEYCLOAK_BASE_URL", "http://localhost:8180"),
        help="Keycloak base URL (default: %(default)s)",
    )
    parser.add_argument("--realm", default="spectrace")
    parser.add_argument("--client-id", default="spectrace-web")
    parser.add_argument(
        "--redirect-uri",
        default="http://localhost:5173/",
        help="must be matched by the client's redirect URIs (default: %(default)s)",
    )
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument(
        "--ready-timeout", type=int, default=180, help="seconds to wait for Keycloak"
    )
    parser.add_argument(
        "--write-sample",
        action="store_true",
        help="refresh realm/sample-access-token-claims.json from the live tokens",
    )
    parser.add_argument(
        "--self-check",
        action="store_true",
        help="check the realm file and the sample claims against each other without "
        "contacting Keycloak (static contract check, safe for CI)",
    )
    parser.add_argument(
        "--strict-extras",
        action="store_true",
        help="also require the non-business roles to match the sample exactly",
    )
    args = parser.parse_args(argv)

    realm = _read_json(REALM_FILE)
    sample_file = _read_json(SAMPLE_FILE)
    ALL_BUSINESS_ROLES = sorted(role["name"] for role in realm["roles"]["realm"])

    users = expected_users(realm)
    samples_by_user = {sample["username"]: sample for sample in sample_file["samples"]}
    missing = [user["username"] for user in users if user["username"] not in samples_by_user]
    if missing:
        raise VerificationError(
            "sample-access-token-claims.json is missing demo users: %s" % missing
        )

    claims_by_user = {}
    problems = []
    captured = []

    if args.self_check:
        print(
            "Static self-check: comparing %s with %s"
            % (os.path.basename(REALM_FILE), os.path.basename(SAMPLE_FILE))
        )
        for sample in sample_file["samples"]:
            claims_by_user[sample["username"]] = sample["access_token"]
        for user in users:
            sample = samples_by_user[user["username"]]
            problems.extend(check_claims(sample, claims_by_user[user["username"]], True))
        problems.extend(cross_organisation_problems(sample_file["samples"], claims_by_user))
        if problems:
            print("Static self-check FAILED:")
            for problem in problems:
                print("  - %s" % problem)
            return 1
        print("Static self-check passed: realm users, sample claims and isolation rules agree.")
        return 0

    issuer = "%s/realms/%s" % (args.base_url.rstrip("/"), args.realm)
    wait_until_ready(issuer, args.ready_timeout)
    print("Keycloak is ready at %s" % issuer)

    for user in users:
        tokens = fetch_tokens(
            issuer,
            args.client_id,
            args.redirect_uri,
            user["username"],
            user["password"],
            args.timeout,
        )
        access_claims = decode_claims(tokens["access_token"])
        claims_by_user[user["username"]] = access_claims
        sample = samples_by_user[user["username"]]
        problems.extend(check_claims(sample, access_claims, args.strict_extras))
        captured.append(redacted_sample(sample, access_claims))
        print(
            "  %-28s org_id=%-24s org_type=%-12s roles=%s"
            % (
                user["username"],
                access_claims.get("org_id"),
                access_claims.get("org_type"),
                ",".join(sorted(access_claims.get("roles") or [])),
            )
        )

    problems.extend(cross_organisation_problems(sample_file["samples"], claims_by_user))

    redacted_document = {
        "$comment": sample_file["$comment"],
        "issuer_example": issuer,
        "client_id": args.client_id,
        "claim_locations": sample_file["claim_locations"],
        "keycloak_default_realm_roles": KEYCLOAK_DEFAULT_ROLES,
        "keycloak_default_realm_roles_note": sample_file["keycloak_default_realm_roles_note"],
        "redacted_claims": REDACTED_FIELDS,
        "samples": captured,
    }
    if args.write_sample:
        ordered = {sample["username"]: sample for sample in captured}
        redacted_document["samples"] = [
            ordered[sample["username"]] for sample in sample_file["samples"]
        ]
        with open(SAMPLE_FILE, "w", encoding="utf-8") as handle:
            json.dump(redacted_document, handle, indent=2)
            handle.write("\n")
        print("Wrote redacted samples to %s" % SAMPLE_FILE)

    if problems:
        print("\nVerification FAILED:")
        for problem in problems:
            print("  - %s" % problem)
        return 1

    print("\nAll Access Token claim checks passed.")
    print(json.dumps(redacted_document["samples"][0], indent=2))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv[1:]))
    except VerificationError as failure:
        print("ERROR: %s" % failure, file=sys.stderr)
        sys.exit(2)
