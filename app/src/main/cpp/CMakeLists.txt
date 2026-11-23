cmake_minimum_required(VERSION 3.22.1)
project("eatmem" C)

add_library(eatmem SHARED eatmem.c)
target_link_options(eatmem PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections" "-Wl,--strip-all")
