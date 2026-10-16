#pragma once

#include "yoox/config.h"
#include "yoox/device_models.h"
#include "yoox/http_client.h"
#include "yoox/safety_guard.h"
#include "yoox/task_tracker.h"

#include <filesystem>
#include <memory>
#include <nlohmann/json.hpp>
#include <optional>
#include <string>
#include <vector>

namespace yoox {

class YooxClient {
public:
    YooxClient(const Config& config, std::shared_ptr<IHttpTransport> transport);

    nlohmann::json login(bool pilot = false);
    nlohmann::json refresh_token();
    nlohmann::json current_workspace();
    nlohmann::json current_user();
    std::vector<DeviceInfo> list_devices();
    nlohmann::json get_device(const std::string& gateway_sn);
    nlohmann::json get_hms(const std::string& device_sn = "");
    nlohmann::json live_capacity();

    nlohmann::json acquire_flight_authority(const std::string& gateway_sn);
    nlohmann::json acquire_payload_authority(const std::string& gateway_sn,
                                              const std::string& payload_index);
    nlohmann::json payload_command(const std::string& gateway_sn, const std::string& command,
                                   const nlohmann::json& data);
    nlohmann::json remote_job(const std::string& gateway_sn, const std::string& method,
                              const std::optional<nlohmann::json>& body = std::nullopt);
    nlohmann::json fly_to_point(const std::string& gateway_sn, const FlightTarget& target);
    nlohmann::json stop_fly_to_point(const std::string& gateway_sn);
    nlohmann::json takeoff_to_point(const std::string& gateway_sn, const FlightTarget& target);
    std::optional<PointFlightState> point_flight_status(const std::string& gateway_sn);

    nlohmann::json target_detection(const std::string& gateway_sn, bool open,
                                    int lens = 0, const std::vector<int>& targets = {0, 1});
    nlohmann::json live_start(const std::string& video_id, int quality = 2,
                              const std::string& lens = "");
    nlohmann::json live_stop(const std::string& video_id);
    nlohmann::json live_update(const std::string& video_id, int quality);
    nlohmann::json live_switch(const std::string& video_id, const std::string& lens);

    nlohmann::json drc_connect();
    nlohmann::json drc_enter(const std::string& client_id, const std::string& gateway_sn);
    nlohmann::json drc_exit(const std::string& client_id, const std::string& gateway_sn);

    nlohmann::json list_waylines();
    nlohmann::json upload_wayline(const std::filesystem::path& file);
    nlohmann::json list_jobs();
    nlohmann::json create_wayline_job(const nlohmann::json& body);
    nlohmann::json change_wayline_job(const std::string& job_id, int status);
    nlohmann::json cancel_wayline_job(const std::string& job_id);

    const std::string& token() const noexcept { return token_; }
    const std::string& workspace_id() const noexcept { return workspace_id_; }
    void set_token_for_test(std::string token) { token_ = std::move(token); }

private:
    nlohmann::json call(HttpRequest request, bool allow_api_error = false);
    HttpRequest request(HttpMethod method, std::string path, std::string action,
                        bool pilot_base = false) const;

    Config config_;
    std::shared_ptr<IHttpTransport> transport_;
    std::string token_;
    std::string workspace_id_;
};

}  // namespace yoox

