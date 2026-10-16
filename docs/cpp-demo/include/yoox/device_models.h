#pragma once

#include <nlohmann/json.hpp>
#include <optional>
#include <string>
#include <vector>

namespace yoox {

struct DeviceInfo {
    std::string gateway_sn;
    std::string aircraft_sn;
    std::string remote_controller_sn;
    int domain = -1;
    std::optional<bool> online;
    std::optional<bool> aircraft_online;
    nlohmann::json raw;
};

std::vector<nlohmann::json> unwrap_items(const nlohmann::json& value);
DeviceInfo parse_device(const nlohmann::json& value);
std::string mqtt_topic(const std::string& serial_number, const std::string& suffix);

struct WebSocketEvent {
    std::string biz_code;
    nlohmann::json data;
};

WebSocketEvent parse_websocket_event(const std::string& raw);

}  // namespace yoox

