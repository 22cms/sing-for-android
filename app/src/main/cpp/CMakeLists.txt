cmake_minimum_required(VERSION 3.22.1)

project("sing_dsp")

include_directories(${CMAKE_CURRENT_SOURCE_DIR}/mnn/include)

set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O3 -ffast-math -funroll-loops")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O3 -ffast-math -funroll-loops")

if(CMAKE_SYSTEM_PROCESSOR STREQUAL "x86_64" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
  add_compile_options(
    -O3 -mavx2 -mfma -ffast-math -fno-math-errno -ffp-contract=fast
    -funroll-loops -march=x86-64-v3)
else()
  add_compile_options(-O3 -ffast-math -funroll-loops)
endif()

set(MNN_BRIDGE "")
if((CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
   AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI}/libMNN.so")
    set(MNN_BRIDGE mnn_bridge.cpp)
endif()

set(KISSFFT_DATATYPE "float" CACHE STRING "" FORCE)
set(KISSFFT_STATIC ON CACHE BOOL "" FORCE)
set(KISSFFT_TEST OFF CACHE BOOL "" FORCE)
set(KISSFFT_TOOLS OFF CACHE BOOL "" FORCE)
set(KISSFFT_PKGCONFIG OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../extern/kissfft ${CMAKE_CURRENT_BINARY_DIR}/kissfft EXCLUDE_FROM_ALL)

add_library(sing_dsp SHARED
    stft_engine.cpp
    sinc_resampler.cpp
    vr_dsp_engine.cpp
    jni_bridge.cpp
    vinet_jni.cpp
    ${MNN_BRIDGE}
)

target_compile_options(sing_dsp PRIVATE
    -O3
    -ffast-math
    -funroll-loops
)

find_library(log-lib log)

if((CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
   AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI}/libMNN.so")
    add_library(MNN SHARED IMPORTED)
    set_target_properties(MNN PROPERTIES IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${CMAKE_ANDROID_ARCH_ABI}/libMNN.so)
    target_link_libraries(sing_dsp MNN)
endif()

target_link_libraries(sing_dsp
    kissfft
    ${log-lib}
    dl
)

# Fallback stub for libdl_android.so: SONAME kept identical so the DT_NEEDED
# from libvndksupport resolves against it when the real vendor/system library
# cannot be loaded (see libdl_android_stub.c).
add_library(dl_android_stub SHARED libdl_android_stub.c)
set_target_properties(dl_android_stub PROPERTIES OUTPUT_NAME "dl_android_stub")
target_link_options(dl_android_stub PRIVATE
    "-Wl,-soname,libdl_android.so"
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/libdl_android_stub.map"
)

