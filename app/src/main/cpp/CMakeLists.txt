cmake_minimum_required(VERSION 3.22.1)
project(private_record_whisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
        whisper_cpp
        GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
        GIT_TAG v1.8.4
)
FetchContent_MakeAvailable(whisper_cpp)

add_library(private_record_whisper SHARED private_record_whisper.cpp)
target_link_libraries(private_record_whisper whisper android log)
