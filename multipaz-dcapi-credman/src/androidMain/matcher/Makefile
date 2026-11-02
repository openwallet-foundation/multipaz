
WASM_CLANG = ~/wasi-sdk-20.0/bin/clang
WASM_CLANGPP = ~/wasi-sdk-20.0/bin/clang++

C_SOURCES = cJSON.c
CXX_SOURCES = Request.cpp CredentialDatabase.cpp cppbor.cpp cppbor_parse.cpp main.cpp matcher.cpp logger.cpp dcql.cpp paths.cpp

OBJECTS = $(patsubst %.c, build/%.o,$(C_SOURCES)) $(patsubst %.cpp, build/%.o,$(CXX_SOURCES))

ISSUANCE_CXX_SOURCES = cppbor.cpp cppbor_parse.cpp issuance_main.cpp issuance_matcher.cpp

ISSUANCE_OBJECTS = $(patsubst %.c, build/%.o,$(C_SOURCES)) $(patsubst %.cpp, build/%.o,$(ISSUANCE_CXX_SOURCES))

CFLAGS =  -c -O3 -fno-exceptions
CXXFLAGS =  -c -std=c++20 -O3 -fno-exceptions

all : build/matcher.wasm build/issuance_matcher.wasm

build:
	mkdir -p $@

build/matcher.wasm: $(OBJECTS) build
	$(WASM_CLANGPP) -o build/matcher.wasm $(OBJECTS) -O3 -fno-exceptions -lstdc++

build/issuance_matcher.wasm: $(ISSUANCE_OBJECTS) build
	$(WASM_CLANGPP) -o build/issuance_matcher.wasm $(ISSUANCE_OBJECTS) -O3 -fno-exceptions -lstdc++

build/%.o: %.c build
	$(WASM_CLANG) -c -o $@ $< $(CFLAGS)

build/%.o: %.cpp build
	$(WASM_CLANGPP) -c -o $@ $< $(CXXFLAGS)

clean:
	rm -rf build

