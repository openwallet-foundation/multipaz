This directory contains a Credman matcher written in C/C++.

To compile it you need the [WASI SDK](https://github.com/WebAssembly/wasi-sdk/releases)
toolchain installed, specifically version 20. It should be installed in `~/wasi-sdk-20.0`.

The bundled `Makefile` will build the `build/matcher.wasm` binary which can be copied
into `../assets/identitycredentialmatcher.wasm` where it will get picked up as part
of the identity-appsupport library. The following command-line does this

```shell
$ make clean && make -j && cp build/matcher.wasm ../assets/identitycredentialmatcher.wasm
```

The [cJSON library](https://github.com/DaveGamble/cJSON) is bundled as `cJSON.[c, h]` with
license in `cJSON-LICENSE` file.

The [LibCppBor library](https://android.googlesource.com/platform/system/libcppbor/) is
bundled as `cppbor.[cpp, h]` and `cppbor_parse.[cpp, h]`. This is licensed under the Apache
License, Version 2.0.
