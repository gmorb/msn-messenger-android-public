#!/bin/bash
set -e
NDK=${ANDROID_NDK:-$HOME/Android/Sdk/ndk/26.3.11579264}
TC=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=23
SRC=${FFMPEG_SRC:-$HOME/msn-wmadec/ffmpeg}
OUT=$(cd "$(dirname "$0")" && pwd)/ffmpeg
build() {
  ABI=$1; ARCH=$2; CPU=$3; CC=$4; EXTRA=$5
  echo "===== $ABI"
  B=$HOME/msn-wmadec/build-$ABI; rm -rf $B; mkdir -p $B; cd $B
  $SRC/configure --prefix=$OUT/$ABI --target-os=android --arch=$ARCH --cpu=$CPU \
    --enable-cross-compile --cc=$TC/bin/$CC --cxx=$TC/bin/${CC}++ \
    --ar=$TC/bin/llvm-ar --nm=$TC/bin/llvm-nm --ranlib=$TC/bin/llvm-ranlib --strip=$TC/bin/llvm-strip \
    --sysroot=$TC/sysroot --enable-pic --enable-static --disable-shared \
    --disable-everything --disable-programs --disable-doc --disable-network \
    --disable-avdevice --disable-avfilter --disable-swscale --disable-swresample --disable-postproc \
    --disable-autodetect --disable-debug --enable-small \
    --enable-demuxer=asf --enable-decoder=wmav1,wmav2 --enable-protocol=file \
    --extra-cflags="-O2 -ffunction-sections -fdata-sections" $EXTRA
  make -j20 >/dev/null
  make install >/dev/null
  ls -la $OUT/$ABI/lib
}
build arm64-v8a aarch64 armv8-a aarch64-linux-android$API-clang ""
build armeabi-v7a arm armv7-a armv7a-linux-androideabi$API-clang "--disable-neon"
build x86_64 x86_64 x86-64 x86_64-linux-android$API-clang "--disable-asm"
echo ALL_DONE
