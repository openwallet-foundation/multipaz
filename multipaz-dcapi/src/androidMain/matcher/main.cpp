
extern "C" void matcher(void);

// This is the entrypoint used in the WASM binary.
extern "C" int main() {
    matcher();
    return 0;
}
