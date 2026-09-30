cmake_minimum_required(VERSION 3.22.1)
project(companion_llama LANGUAGES C CXX)

set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

set(LLAMA_SOURCE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../ios/Vendor/llama.cpp")
if(NOT EXISTS "${LLAMA_SOURCE}/CMakeLists.txt")
    message(FATAL_ERROR "Run scripts/android-llama.sh to fetch ios/Vendor/llama.cpp v0.4.1")
endif()
add_subdirectory("${LLAMA_SOURCE}" "${CMAKE_BINARY_DIR}/llama")

add_library(companion_llama SHARED CompanionLlamaJni.cpp)
target_include_directories(companion_llama PRIVATE "${LLAMA_SOURCE}/include")
target_link_libraries(companion_llama PRIVATE llama log)
