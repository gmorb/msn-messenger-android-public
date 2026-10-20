# libmsnwma.so: decodes the user's own MSN Messenger 8 WMA sounds to WAV during import.
# Links a cut-down FFmpeg n6.1.1 (LGPL-2.1+: asf demuxer, wmav1/wmav2 decoders only).
# Rebuild the static FFmpeg libs with build_ffmpeg.sh (needs the FFmpeg n6.1.1 source and the NDK).
cmake_minimum_required(VERSION 3.22.1)
project(msnwma C)
set(FF ${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg/${ANDROID_ABI})
# cabjni.c + libmspack 0.11alpha (LGPL-2.1, mspack/COPYING.LIB): extracts MsnMsgs.msi from the user's
# MSN Messenger 8 .exe (LZX cabinet). Only the CAB decompressor parts are compiled.
set(MSPACK ${CMAKE_CURRENT_SOURCE_DIR}/mspack)
add_library(msnwma SHARED wmadec.c cabjni.c
    ${MSPACK}/cabd.c ${MSPACK}/lzxd.c ${MSPACK}/mszipd.c ${MSPACK}/qtmd.c ${MSPACK}/system.c)
target_include_directories(msnwma PRIVATE ${FF}/include ${MSPACK})
# No _FILE_OFFSET_BITS=64/LARGEFILE_SUPPORT: 32-bit bionic lacks 64-bit fseeko below API 24 (minSdk 23),
# and the installer files are ~16 MB, far below 2 GB.
target_compile_definitions(msnwma PRIVATE HAVE_INTTYPES_H HAVE_LIMITS_H HAVE_STRING_H)
target_link_libraries(msnwma ${FF}/lib/libavformat.a ${FF}/lib/libavcodec.a ${FF}/lib/libavutil.a m log)
target_link_options(msnwma PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
