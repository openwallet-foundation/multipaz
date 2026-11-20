# AP2 interoperability check

`DelegateAp2InteropTest` answers AP2 delegation requests through a real OpenID4VP
presentment and writes each response to `build/ap2-interop/<scenario>.json`.
`verify_ap2_interop.py` runs every one through the verifier in AP2's own Python SDK
(`ap2.sdk.sdjwt.chain.verify_chain`), so "AP2's SDK verifies our responses" can be
re-run by anyone.

It checks, per scenario, that the SDK accepts the whole chain (issuer signature,
`typ`, `sd_hash`, key binding, `cnf` rules), that the KB-JWT carries the expected
`nonce` and `aud` (the SDK enforces them only on a terminal hop), and that the
mandates the SDK resolves, with selectively disclosed terms filled in, are the ones
requested. It exits non-zero on any failure.

```bash
# 1. Write the responses.
./gradlew :multipaz-utopia:jvmTest --tests '*DelegateAp2InteropTest' -Pdisable.web.targets=true

# 2. Check them against AP2 v0.2.0 (commit b4587ac1d055888a73b4b21750973cffba961793),
#    the release the schemas in src/jvmTest/resources/ap2-schemas come from.
git clone --depth 1 --branch v0.2.0 https://github.com/google-agentic-commerce/AP2.git /tmp/AP2
python3 -m venv /tmp/ap2-venv
/tmp/ap2-venv/bin/pip install cryptography==46.0.5 jwcrypto==1.5.6 pydantic==2.12.5 sd-jwt==0.10.4
PYTHONPATH=/tmp/AP2/code/sdk/python /tmp/ap2-venv/bin/python \
    multipaz-utopia/scripts/verify_ap2_interop.py multipaz-utopia/build/ap2-interop
```

The versions are those AP2 pins in its `pyproject.toml`.
