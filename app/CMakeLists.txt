cmake_minimum_required(VERSION 3.22.1)
project(vrclaude_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

find_library(ANDROID_LIB android)
find_library(GLESv2_LIB GLESv2)
find_library(LOG_LIB log)

# The CI workflow checks out googlevr/cardboard next to the app folder.
set(CARDBOARD_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../cardboard)
set(CARDBOARD_SDK_DIR ${CARDBOARD_DIR}/sdk)

# libGfxPluginCardboard.so, extracted from the SDK's AAR by the workflow (link only;
# the AAR itself packages it into the APK).
add_library(GfxPluginCardboard SHARED IMPORTED)
set_target_properties(GfxPluginCardboard PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/libs/jni/${ANDROID_ABI}/libGfxPluginCardboard.so)

add_library(cardboard_sdk_jni SHARED
    ${CARDBOARD_DIR}/java_api/src/main/jni/cardboard_jni.cc
    ${CARDBOARD_SDK_DIR}/jni_utils/android/jni_utils.cc)

target_include_directories(cardboard_sdk_jni PRIVATE ${CARDBOARD_SDK_DIR})

target_link_libraries(cardboard_sdk_jni PRIVATE
    GfxPluginCardboard
    ${ANDROID_LIB}
    ${LOG_LIB}
    ${GLESv2_LIB})

target_link_options(cardboard_sdk_jni PRIVATE "-Wl,-z,max-page-size=16384")
