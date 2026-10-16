#include "yoox/safety_guard.h"

#include <cmath>
#include <regex>

namespace yoox {

std::vector<std::string> SafetyGuard::validate_target(
    const FlightTarget& target, bool integer_speed, double maximum_height) {
    std::vector<std::string> errors;
    if (!std::isfinite(target.latitude) || !std::isfinite(target.longitude) ||
        target.latitude < -90 || target.latitude > 90 ||
        target.longitude < -180 || target.longitude > 180 ||
        (target.latitude == 0 && target.longitude == 0)) {
        errors.emplace_back("target must be a non-zero WGS84 coordinate");
    }
    if (!std::isfinite(target.height) || target.height < 2 || target.height > maximum_height) {
        errors.emplace_back("target height is outside the allowed range");
    }
    if (!std::isfinite(target.speed) || target.speed < 1 || target.speed > 15) {
        errors.emplace_back("speed must be between 1 and 15 m/s");
    }
    if (integer_speed && std::floor(target.speed) != target.speed) {
        errors.emplace_back("FlyTo speed must be an integer");
    }
    return errors;
}

bool SafetyGuard::is_valid_payload_index(const std::string& value) {
    static const std::regex pattern(R"(^\d+-\d+-\d+$)");
    return std::regex_match(value, pattern);
}

SafetyDecision SafetyGuard::evaluate(const SafetyRequest& request) {
    SafetyDecision decision;
    decision.dry_run = !request.execute;
    if (!request.execute) decision.reasons.emplace_back("dry-run is active; pass --execute to enable writes");
    if (request.device.gateway_sn.empty()) decision.reasons.emplace_back("gateway SN is missing");
    if (!request.device.gateway_online) decision.reasons.emplace_back("gateway is not online");
    if (request.needs_aircraft && request.device.aircraft_sn.empty()) {
        decision.reasons.emplace_back("aircraft SN is missing");
    }
    if (request.needs_aircraft && !request.device.aircraft_online) {
        decision.reasons.emplace_back("aircraft is not online");
    }
    if (request.device.conflicting_task) decision.reasons.emplace_back("a conflicting task is active or unresolved");
    if (!request.device.authority_allowed) decision.reasons.emplace_back("control authority state does not allow the action");
    if (request.needs_airborne && !request.device.mode_code) {
        decision.reasons.emplace_back("aircraft mode is unknown; pass the freshly observed --mode-code");
    } else if (request.needs_airborne && *request.device.mode_code == 0) {
        decision.reasons.emplace_back("aircraft is on the ground");
    }
    if (request.needs_ground_idle && !request.device.mode_code) {
        decision.reasons.emplace_back("aircraft mode is unknown; pass the freshly observed --mode-code");
    } else if (request.needs_ground_idle && *request.device.mode_code != 0) {
        decision.reasons.emplace_back("aircraft is not in ground IDLE mode");
    }
    if (request.action == "fly_to_point" && request.device.mode_code &&
        *request.device.mode_code != 3) {
        decision.reasons.emplace_back("FlyTo requires freshly observed MANUAL mode_code=3");
    }
    if (request.target) {
        const auto target_errors = validate_target(*request.target, request.action == "fly_to_point");
        decision.reasons.insert(decision.reasons.end(), target_errors.begin(), target_errors.end());
    }
    if (request.execute && !request.required_confirmation.empty() &&
        request.supplied_confirmation != request.required_confirmation) {
        decision.reasons.emplace_back("confirmation phrase does not match");
    }
    decision.allowed = decision.reasons.empty();
    return decision;
}

}  // namespace yoox

