cmake_minimum_required(VERSION 3.22.1)
project("eatmem")

add_library(eatmem SHARED eatmem.cpp)

target_link_options(eatmem PRIVATE "-Wl,-z,max-page-size=16384")

find_library(log-lib log)
target_link_libraries(eatmem ${log-lib})
