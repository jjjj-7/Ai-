cmake_minimum_required(VERSION 3.22.1)
project(autopilot_pty C)

add_library(autopilot_pty SHARED pty.c)
target_link_libraries(autopilot_pty log)
