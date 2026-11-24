cmake_minimum_required(VERSION 3.22.1)
project(jni_latinime CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

file(GLOB_RECURSE LATINIME_CORE_SRC CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp)

add_library(jni_latinime SHARED
        com_android_inputmethod_keyboard_ProximityInfo.cpp
        com_android_inputmethod_latin_BinaryDictionary.cpp
        com_android_inputmethod_latin_BinaryDictionaryUtils.cpp
        com_android_inputmethod_latin_DicTraverseSession.cpp
        jni_common.cpp
        ${LATINIME_CORE_SRC})

target_include_directories(jni_latinime PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_options(jni_latinime PRIVATE
        -Wno-unused-parameter -Wno-unused-function -Wno-deprecated-declarations
        -Wno-unqualified-std-cast-call -Os)
target_link_libraries(jni_latinime log)
target_link_options(jni_latinime PRIVATE "-Wl,-z,max-page-size=16384")
