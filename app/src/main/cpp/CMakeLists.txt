cmake_minimum_required(VERSION 3.22.1)
project(librehu_disc C)

add_library(librehu_disc SHARED librehu_disc.c)
target_compile_options(librehu_disc PRIVATE -Wall -Wextra -Werror)
target_link_libraries(librehu_disc log)
