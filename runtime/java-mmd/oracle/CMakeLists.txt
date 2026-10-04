cmake_minimum_required(VERSION 3.20)
project(ysm_mmd_oracle LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 14)
file(GLOB SABA_BASE "${SABA_SOURCE}/src/Saba/Base/*.cpp")
file(GLOB SABA_MMD "${SABA_SOURCE}/src/Saba/Model/MMD/*.cpp")
add_executable(saba_oracle saba_oracle.cpp ${SABA_BASE} ${SABA_MMD})
target_include_directories(saba_oracle PRIVATE "${SABA_SOURCE}/src" "${SABA_SOURCE}/external/glm/include"
    "${SABA_SOURCE}/external/spdlog/include" "${BULLET_SOURCE}/src")
target_compile_definitions(saba_oracle PRIVATE BT_NO_PROFILE UNICODE _UNICODE)
target_link_libraries(saba_oracle PRIVATE
    "${BULLET_BUILD}/BulletDynamics/libBulletDynamics.a"
    "${BULLET_BUILD}/BulletCollision/libBulletCollision.a"
    "${BULLET_BUILD}/LinearMath/libLinearMath.a")
