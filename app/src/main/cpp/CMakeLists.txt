cmake_minimum_required(VERSION 3.22.1)
project("ble1507_qwen" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---------------------------------------------------------------
# llama.cpp source location
#   Place a shallow clone here before building:
#   cd app/src/main/cpp
#   git clone --depth=1 https://github.com/ggerganov/llama.cpp llama.cpp
# ---------------------------------------------------------------
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")

if(NOT EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "\n[ble1507_qwen] llama.cpp not found at:\n"
        "  ${LLAMA_CPP_DIR}\n\n"
        "Clone it once:\n"
        "  cd app/src/main/cpp\n"
        "  git clone --depth=1 https://github.com/ggerganov/llama.cpp llama.cpp\n")
endif()

# Disable everything we don't need so the build stays fast
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_METAL           OFF CACHE BOOL "" FORCE)  # no Metal on Android
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # pthreads only
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# Android's Debug variant otherwise builds llama.cpp without optimization,
# making a sub-billion-parameter model several times slower on device.
if(ANDROID)
    string(APPEND CMAKE_C_FLAGS_DEBUG " -O3 -DNDEBUG")
    string(APPEND CMAKE_CXX_FLAGS_DEBUG " -O3 -DNDEBUG")
endif()

# Enable ARM dot-product + FP16 instructions on aarch64 Android.
# DOTPROD (sdot/udot) is supported on Cortex-A76/A55 and all Snapdragon 7xx/8xx
# from ~2019 onwards.  This typically gives a 5-10x throughput improvement
# for Q4/Q8 quantised models compared to plain armv8-a.
if(ANDROID AND CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
    # Xperia 1 VII / Snapdragon 8 Elite exposes dotprod, FP16 and i8mm.
    set(GGML_CPU_ARM_ARCH  "armv8.6-a+dotprod+fp16+i8mm" CACHE STRING "" FORCE)
    # KleidiAI: Arm’s optimized compute kernels bundled with llama.cpp.
    # Typically gives an additional 2-4x matmul speedup on Cortex-A7xx/X-series.
    set(GGML_CPU_KLEIDIAI  ON  CACHE BOOL   "" FORCE)
endif()

add_subdirectory(${LLAMA_CPP_DIR} llama_build EXCLUDE_FROM_ALL)

# Include paths differ between old (root) and new (include/) layouts
if(EXISTS "${LLAMA_CPP_DIR}/include/llama.h")
    set(LLAMA_INCLUDE_DIRS
        "${LLAMA_CPP_DIR}/include"
        "${LLAMA_CPP_DIR}/ggml/include")
else()
    set(LLAMA_INCLUDE_DIRS "${LLAMA_CPP_DIR}")
endif()

add_library(ble1507_qwen SHARED ble1507_qwen.cpp)

target_include_directories(ble1507_qwen PRIVATE ${LLAMA_INCLUDE_DIRS})

target_link_libraries(ble1507_qwen
    PRIVATE
    llama
    android
    log
)

target_compile_options(ble1507_qwen PRIVATE
    -fvisibility=hidden
    -O2
)

# InertialMotionLib2 is vendored directly in Spright so the app does not
# depend on the complete sprbox-SDK Gradle project. The JNI class keeps its
# original JVM package because that package is encoded in the native symbols.
set(INERTIAL_MOTION_LIB2_DIR "${CMAKE_CURRENT_SOURCE_DIR}/inertialmotionlib2")
set(INERTIAL_MOTION_LIB2_PREBUILT
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libinertialmotionlib2.so")

if(NOT EXISTS "${INERTIAL_MOTION_LIB2_PREBUILT}")
    message(FATAL_ERROR
        "InertialMotionLib2 prebuilt library is missing for ABI ${ANDROID_ABI}: "
        "${INERTIAL_MOTION_LIB2_PREBUILT}")
endif()

add_library(inertialmotionlib2 SHARED IMPORTED)
set_target_properties(inertialmotionlib2 PROPERTIES
    IMPORTED_LOCATION "${INERTIAL_MOTION_LIB2_PREBUILT}"
    INTERFACE_INCLUDE_DIRECTORIES "${INERTIAL_MOTION_LIB2_DIR}/include"
)

add_library(inertialmotionlib2_jni SHARED
    "${INERTIAL_MOTION_LIB2_DIR}/inertialmotionlib2_jni.c")

target_include_directories(inertialmotionlib2_jni PRIVATE
    "${INERTIAL_MOTION_LIB2_DIR}/include")

target_link_libraries(inertialmotionlib2_jni
    PRIVATE
    inertialmotionlib2
    android
    log
    m
)
