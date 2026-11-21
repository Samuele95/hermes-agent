cmake_minimum_required(VERSION 3.22)
project(hermes_bridge C)

# HERMES_PAYLOAD: the directory scripts/android/payload.py assembled (headers + libpython).
add_library(python SHARED IMPORTED)
set_target_properties(python PROPERTIES IMPORTED_LOCATION ${HERMES_PAYLOAD}/jniLibs/${ANDROID_ABI}/libpython3.14.so)

add_library(hermes_bridge SHARED hermes_bridge.c)
target_include_directories(hermes_bridge PRIVATE ${HERMES_PAYLOAD}/include/python3.14)
target_compile_options(hermes_bridge PRIVATE -Wall -Wextra -Werror)
target_link_libraries(hermes_bridge python log)

# The app's Python executable ($HOME/bin/python3, hermes_android.launcher). Named like a library so the APK carries it
# among the native libraries, the one place an app may exec from; it finds libpython beside itself.
add_executable(hermes_pyexec hermes_pyexec.c)
target_include_directories(hermes_pyexec PRIVATE ${HERMES_PAYLOAD}/include/python3.14)
target_compile_options(hermes_pyexec PRIVATE -Wall -Wextra -Werror)
target_link_options(hermes_pyexec PRIVATE "-Wl,-rpath,\$ORIGIN" "-Wl,--enable-new-dtags")
target_link_libraries(hermes_pyexec python)
set_target_properties(hermes_pyexec PROPERTIES OUTPUT_NAME libhermes_pyexec.so PREFIX "" SUFFIX "")
