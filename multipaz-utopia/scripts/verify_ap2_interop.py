#!/usr/bin/env python3
"""Verifies the responses written by DelegateAp2InteropTest with AP2's own Python SDK.

`DelegateAp2InteropTest` answers AP2 delegation requests through a real OpenID4VP
presentment and writes each response to `multipaz-utopia/build/ap2-interop/<scenario>.json`.
This script feeds every one to the SDK's `verify_chain`, the verifier a merchant or an agent
runs, and checks that the mandates it resolves are the ones that were requested.

Usage (see the README next to this script):

    PYTHONPATH=<AP2>/code/sdk/python python3 verify_ap2_interop.py [build/ap2-interop]

Needs `cryptography`, `jwcrypto`, `pydantic` and `sd-jwt` (AP2's SDK dependencies). Exits
non-zero if any scenario fails, so it can gate a CI job.
"""

import base64
import hashlib
import json
import pathlib
import sys

from jwcrypto.jwk import JWK

from ap2.sdk.sdjwt import chain
from ap2.sdk.sdjwt.common import parse_token

COMPACT_JWT_PARTS = 3


def canonical_segment(segment: str, index: int, total: int) -> str:
    """Restores the trailing `~` that splitting on `~~` strips (as ap2.sdk.mandate does)."""
    if index == total - 1 or segment.endswith('~'):
        return segment
    if len(segment.rsplit('~', 1)[-1].split('.')) == COMPACT_JWT_PARTS:
        return segment
    return segment + '~'


def b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()


def resolve(value, disclosures: dict[str, list]):
    """Replaces RFC 9901 digests with what their disclosures reveal, as a verifier does."""
    if isinstance(value, list):
        out = []
        for item in value:
            if isinstance(item, dict) and list(item) == ['...']:
                out.append(resolve(disclosures[item['...']][1], disclosures))
            else:
                out.append(resolve(item, disclosures))
        return out
    if isinstance(value, dict):
        out = {k: resolve(v, disclosures) for k, v in value.items() if k != '_sd'}
        for digest in value.get('_sd', []):
            _salt, name, revealed = disclosures[digest]
            out[name] = resolve(revealed, disclosures)
        return out
    return value


def verify(scenario: dict) -> list[str]:
    """Returns what is wrong with one response; empty when AP2's SDK accepts it."""
    segments = scenario['vp_token'].split('~~')
    tokens = [
        parse_token(canonical_segment(s, i, len(segments)))
        for i, s in enumerate(segments)
    ]
    issuer_key = JWK(**scenario['issuer_jwk'])
    payloads = chain.verify_chain(
        tokens,
        lambda _token: issuer_key,
        expected_aud=scenario['expected_aud'],
        expected_nonce=scenario['expected_nonce'],
    )
    problems = []
    # The SDK enforces the nonce and audience only on a terminal hop (`kb+sd-jwt`); an
    # intermediate one (`kb-sd-jwt+kb`) is not checked, so the verifier must do it.
    kb_jwt = next(p for p in segments[-1].split('~') if len(p.split('.')) == COMPACT_JWT_PARTS)
    kb_body = kb_jwt.split('.')[1]
    kb_payload = json.loads(base64.urlsafe_b64decode(kb_body + '=' * (-len(kb_body) % 4)))
    for claim, expected in (('nonce', scenario['expected_nonce']), ('aud', scenario['expected_aud'])):
        if kb_payload.get(claim) != expected:
            problems.append(f"KB-JWT '{claim}' is {kb_payload.get(claim)!r}, expected {expected!r}")
    disclosures = {}
    for disclosure in scenario.get('delegate_disclosures', []):
        padded = disclosure + '=' * (-len(disclosure) % 4)
        decoded = json.loads(base64.urlsafe_b64decode(padded))
        disclosures[b64url(hashlib.sha256(disclosure.encode()).digest())] = decoded
    requested = [resolve(mandate, disclosures) for mandate in scenario['mandates']]
    # The terminal hop's resolved items are the delegated mandates, in request order.
    resolved = payloads[-len(requested):]
    if resolved != requested:
        problems.append(f'resolved mandates differ from the requested ones: {resolved}')
    return problems


def main() -> int:
    directory = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else 'build/ap2-interop')
    files = sorted(directory.glob('*.json'))
    if not files:
        print(f'no responses in {directory}: run DelegateAp2InteropTest first', file=sys.stderr)
        return 2
    failed = 0
    for path in files:
        scenario = json.loads(path.read_text())
        try:
            problems = verify(scenario)
        except Exception as err:  # noqa: BLE001 (any rejection is a failure to report)
            problems = [f'{type(err).__name__}: {err}']
        print(f'{"FAIL" if problems else "pass"}  {scenario["scenario"]}')
        for problem in problems:
            print(f'      {problem}')
        failed += bool(problems)
    print(f'\n{len(files) - failed}/{len(files)} scenarios verified by AP2\'s SDK')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
