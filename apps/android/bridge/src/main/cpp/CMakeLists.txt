cmake_minimum_required(VERSION 3.22)
project(hermes_bridge C)

# HERMES_PAYLOAD: the directory scripts/android/payload.py assembled (headers + libpython).
add_library(python SHARED IMPORTED)
set_target_properties(python PROPERTIES IMPORTED_LOCATION ${HERMES_PAYLOAD}/jniLibs/${ANDROID_ABI}/libpython3.14.so)

add_library(hermes_bridge SHARED hermes_bridge.c)
target_include_directories(hermes_bridge PRIVATE ${HERMES_PAYLOAD}/include/python3.14)
target_compile_options(hermes_bridge PRIVATE -Wall -Wextra -Werror)
target_link_libraries(hermes_bridge python log)
