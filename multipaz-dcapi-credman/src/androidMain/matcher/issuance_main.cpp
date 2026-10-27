extern "C" void issuance_matcher(void);

// This is the entrypoint used in the issuance WASM binary.
extern "C" int main() {
    issuance_matcher();
    return 0;
}
