#pragma once

#include <filesystem>
#include <map>
#include <string>
#include <vector>

namespace yoox {

struct Waypoint {
    double longitude = 0;
    double latitude = 0;
    double ellipsoid_height = 0;
};

struct KmzMission {
    std::string name;
    double height = 18;
    double speed = 5;
    double takeoff_security_height = 15;
    int drone_enum_value = 67;
    int payload_enum_value = 806;
    std::vector<Waypoint> waypoints;
    std::map<std::size_t, double> gimbal_pitch_actions;
};

struct KmzValidation {
    bool valid = false;
    std::vector<std::string> errors;
};

class Kmz {
public:
    static void generate(const std::filesystem::path& output, const KmzMission& mission);
    static KmzValidation validate(const std::filesystem::path& path);
    static std::string build_template(const KmzMission& mission);
    static std::string build_waylines(const KmzMission& mission);
};

}  // namespace yoox

