#pragma once

#include <optional>
#include <string>
#include <vector>

namespace yoox {

struct FlightTarget {
    double latitude = 0;
    double longitude = 0;
    double height = 0;
    double speed = 0;
};

struct DeviceSafetyState {
    std::string gateway_sn;
    std::string aircraft_sn;
    bool gateway_online = false;
    bool aircraft_online = false;
    std::optional<int> mode_code;
    bool conflicting_task = false;
    bool authority_allowed = true;
};

struct SafetyRequest {
    std::string action;
    std::string required_confirmation;
    std::string supplied_confirmation;
    bool execute = false;
    bool needs_aircraft = true;
    bool needs_airborne = false;
    bool needs_ground_idle = false;
    std::optional<FlightTarget> target;
    DeviceSafetyState device;
};

struct SafetyDecision {
    bool allowed = false;
    bool dry_run = true;
    std::vector<std::string> reasons;
};

class SafetyGuard {
public:
    static SafetyDecision evaluate(const SafetyRequest& request);
    static std::vector<std::string> validate_target(
        const FlightTarget& target,
        bool integer_speed = false,
        double maximum_height = 1500.0);
    static bool is_valid_payload_index(const std::string& value);
};

}  // namespace yoox

