cmake_minimum_required(VERSION 3.16)
project(multipaz_openjpeg_jni C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED OPENJPEG_SRC_DIR)
    message(FATAL_ERROR "OPENJPEG_SRC_DIR must be set to the path of the extracted OpenJPEG source root")
endif()

set(OPJ_LIB_DIR "${OPENJPEG_SRC_DIR}/src/lib/openjp2")

# Generate opj_config.h and opj_config_private.h for POSIX little/big-endian targets
set(OPJ_GEN_DIR "${CMAKE_CURRENT_BINARY_DIR}/opj_generated")
file(MAKE_DIRECTORY "${OPJ_GEN_DIR}")

file(WRITE "${OPJ_GEN_DIR}/opj_config.h" [=[
#ifndef OPJ_CONFIG_H_INCLUDED
#define OPJ_CONFIG_H_INCLUDED
#define OPJ_VERSION_MAJOR 2
#define OPJ_VERSION_MINOR 5
#define OPJ_VERSION_BUILD 4
#endif
]=])

file(WRITE "${OPJ_GEN_DIR}/opj_config_private.h" [=[
#ifndef OPJ_CONFIG_PRIVATE_H_INCLUDED
#define OPJ_CONFIG_PRIVATE_H_INCLUDED
#define OPJ_PACKAGE_VERSION "2.5.4"
#define _LARGEFILE_SOURCE 1
#define _FILE_OFFSET_BITS 64
#define OPJ_HAVE_FSEEKO 1
#define OPJ_HAVE_POSIX_MEMALIGN 1
#if !defined(_POSIX_C_SOURCE)
#define _POSIX_C_SOURCE 200112L
#endif
#if defined(__BIG_ENDIAN__) || (defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_BIG_ENDIAN__))
#define OPJ_BIG_ENDIAN 1
#endif
#endif
]=])

set(OPENJPEG_SRCS
    "${OPJ_LIB_DIR}/thread.c"
    "${OPJ_LIB_DIR}/bio.c"
    "${OPJ_LIB_DIR}/cio.c"
    "${OPJ_LIB_DIR}/dwt.c"
    "${OPJ_LIB_DIR}/event.c"
    "${OPJ_LIB_DIR}/ht_dec.c"
    "${OPJ_LIB_DIR}/image.c"
    "${OPJ_LIB_DIR}/invert.c"
    "${OPJ_LIB_DIR}/j2k.c"
    "${OPJ_LIB_DIR}/jp2.c"
    "${OPJ_LIB_DIR}/mct.c"
    "${OPJ_LIB_DIR}/mqc.c"
    "${OPJ_LIB_DIR}/openjpeg.c"
    "${OPJ_LIB_DIR}/opj_clock.c"
    "${OPJ_LIB_DIR}/pi.c"
    "${OPJ_LIB_DIR}/t1.c"
    "${OPJ_LIB_DIR}/t2.c"
    "${OPJ_LIB_DIR}/tcd.c"
    "${OPJ_LIB_DIR}/tgt.c"
    "${OPJ_LIB_DIR}/function_list.c"
    "${OPJ_LIB_DIR}/opj_malloc.c"
    "${OPJ_LIB_DIR}/sparse_array.c"
)

add_library(multipaz_openjpeg_jni SHARED
    openjpeg_jni.c
    ${OPENJPEG_SRCS}
)

target_include_directories(multipaz_openjpeg_jni PRIVATE
    "${OPJ_GEN_DIR}"
    "${OPJ_LIB_DIR}"
)

if(DEFINED JNI_INCLUDE_DIRS)
    target_include_directories(multipaz_openjpeg_jni PRIVATE ${JNI_INCLUDE_DIRS})
endif()

target_compile_definitions(multipaz_openjpeg_jni PRIVATE
    OPJ_STATIC
    MUTEX_pthread
)

target_compile_options(multipaz_openjpeg_jni PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

if(APPLE)
    target_link_options(multipaz_openjpeg_jni PRIVATE -Wl,-dead_strip)
else()
    target_link_options(multipaz_openjpeg_jni PRIVATE
        -Wl,--gc-sections
        -Wl,-z,max-page-size=16384
    )
endif()

find_package(Threads REQUIRED)
target_link_libraries(multipaz_openjpeg_jni PRIVATE m Threads::Threads)
