# Speech written out on the phone: whisper.cpp (MIT) built from its pinned source (fetched and checked by the app's
# build, WHISPER_SRC), with one CPU variant per generation of chip, the right one chosen when it starts.
# The library is named for the app (libkeyboardwhisper.so), so two apps never share a name.
# Taken from SMS's app/src/main/cpp/CMakeLists.txt, which follows whisper.cpp's own build options
# https://github.com/ggml-org/whisper.cpp/blob/v1.9.4/CMakeLists.txt
cmake_minimum_required(VERSION 3.22)
project(keyboardwhisper CXX C)

set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_SRC} whisper)

add_library(keyboardwhisper SHARED whisper_jni.cpp)
target_link_libraries(keyboardwhisper PRIVATE whisper ggml log)
target_compile_options(keyboardwhisper PRIVATE -O2 -fvisibility=hidden)
