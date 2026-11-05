cmake_minimum_required(VERSION 3.22.1)
project(ultimate_needle C CXX)

# The bridge between Kotlin and Cactus Needle's engine. The engine itself (libneedle.a, Apache-2.0) is fetched by
# scripts/fetch-needle.sh; when it is missing, or for processors it doesn't ship for, the bridge builds as a stub.
set(NEEDLE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/needle/${ANDROID_ABI}")
add_library(ultimate_needle SHARED needle_jni.c)
target_link_libraries(ultimate_needle log m)
if(WITH_NEEDLE AND EXISTS "${NEEDLE_DIR}/libneedle.a")
  target_compile_definitions(ultimate_needle PRIVATE HAVE_NEEDLE=1)
  target_include_directories(ultimate_needle PRIVATE "${NEEDLE_DIR}")
  # The engine is C++; the whole archive keeps its static initialisers.
  target_link_libraries(ultimate_needle "${NEEDLE_DIR}/libneedle.a" atomic)
  set_target_properties(ultimate_needle PROPERTIES LINKER_LANGUAGE CXX)
endif()
