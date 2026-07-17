cmake_minimum_required(VERSION 3.22.1)
project("ble1507_qwen" CXX)

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

# Enable ARM dot-product + FP16 instructions on aarch64 Android.
# DOTPROD (sdot/udot) is supported on Cortex-A76/A55 and all Snapdragon 7xx/8xx
# from ~2019 onwards.  This typically gives a 5-10x throughput improvement
# for Q4/Q8 quantised models compared to plain armv8-a.
if(ANDROID AND CMAKE_SYSTEM_PROCESSOR STREQUAL "aarch64")
    set(GGML_CPU_ARM_ARCH  "armv8-a+dotprod+fp16" CACHE STRING "" FORCE)
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
