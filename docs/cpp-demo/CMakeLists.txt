cmake_minimum_required(VERSION 3.24)
project(yoox_cpp_demo VERSION 1.0.0 LANGUAGES CXX)

option(YOOX_BUILD_TESTS "Build offline unit tests" ON)
option(YOOX_BUILD_NETWORK "Build HTTP/MQTT/WebSocket demos" ON)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(MSVC)
  add_compile_options(/utf-8 /W4 /permissive- /EHsc)
else()
  add_compile_options(-Wall -Wextra -Wpedantic)
endif()

add_library(yoox_core
  src/config.cpp
  src/logger.cpp
  src/safety_guard.cpp
  src/task_tracker.cpp
)
target_include_directories(yoox_core PUBLIC include)

if(YOOX_BUILD_NETWORK)
  find_package(CURL CONFIG REQUIRED)
  find_package(nlohmann_json CONFIG REQUIRED)
  find_package(PahoMqttCpp CONFIG REQUIRED)
  find_package(ixwebsocket CONFIG REQUIRED)
  find_package(libzip CONFIG REQUIRED)

  add_library(yoox_client
    src/http_client.cpp
    src/kmz.cpp
    src/mqtt_client.cpp
    src/websocket_client.cpp
    src/yoox_client.cpp
    src/demo_runner.cpp
  )
  target_include_directories(yoox_client PUBLIC include)
  target_link_libraries(yoox_client
    PUBLIC
      yoox_core
      CURL::libcurl
      nlohmann_json::nlohmann_json
      PahoMqttCpp::paho-mqttpp3-static
      ixwebsocket::ixwebsocket
      libzip::zip
  )

  set(YOOX_DEMOS
    demo_01_login
    demo_02_devices
    demo_03_websocket_osd
    demo_04_mqtt_osd
    demo_05_gimbal_zoom
    demo_06_gimbal_pitch
    demo_07_camera
    demo_08_fly_to_point
    demo_09_dock_control
    demo_10_takeoff_to_point
    demo_11_livestream
    demo_12_drc
    demo_13_payload_advanced
    demo_14_target_detection
    demo_15_emergency
    demo_16_look_at
    demo_17_wayline
    demo_18_continuous_landing
    demo_19_return_home
    demo_20_kmz_generate
    demo_21_kmz_interactive
    demo_22_two_drone_control
  )
  foreach(demo IN LISTS YOOX_DEMOS)
    add_executable(${demo} demos/${demo}.cpp)
    target_link_libraries(${demo} PRIVATE yoox_client)
  endforeach()
endif()

if(YOOX_BUILD_TESTS)
  enable_testing()
  add_executable(yoox_core_tests tests/test_core.cpp)
  target_link_libraries(yoox_core_tests PRIVATE yoox_core)
  add_test(NAME yoox_core_tests COMMAND yoox_core_tests)

  if(YOOX_BUILD_NETWORK)
    add_executable(yoox_client_tests tests/test_client.cpp)
    target_link_libraries(yoox_client_tests PRIVATE yoox_client)
    add_test(NAME yoox_client_tests COMMAND yoox_client_tests)
  endif()
endif()

