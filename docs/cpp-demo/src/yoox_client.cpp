#include "yoox/yoox_client.h"

#include "yoox/logger.h"
#include "yoox/safety_guard.h"
#include "yoox/command_models.h"

#include <algorithm>
#include <cctype>
#include <chrono>
#include <random>
#include <sstream>

namespace yoox {
namespace {

std::string response_message(const nlohmann::json& payload, const std::string& body) {
    if (payload.is_object()) {
        if (payload.contains("message") && payload["message"].is_string()) return payload["message"];
        if (payload.contains("msg") && payload["msg"].is_string()) return payload["msg"];
    }
    return body.substr(0, 300);
}

bool unknown_result(int code, const std::string& message) {
    std::string lowered = message;
    std::transform(lowered.begin(), lowered.end(), lowered.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return code == 211001 || lowered.find("211001") != std::string::npos ||
           lowered.find("no message reply") != std::string::npos ||
           lowered.find("status is unknown") != std::string::npos ||
           lowered.find("reply was empty") != std::string::npos;
}

std::string json_string(const nlohmann::json& value, const char* key) {
    if (!value.is_object() || !value.contains(key) || value[key].is_null()) return {};
    if (value[key].is_string()) return value[key].get<std::string>();
    return value[key].dump();
}

}  // namespace

std::vector<nlohmann::json> unwrap_items(const nlohmann::json& value) {
    if (value.is_array()) return value.get<std::vector<nlohmann::json>>();
    if (value.is_object()) {
        for (const char* key : {"list", "records", "items"}) {
            if (value.contains(key) && value[key].is_array()) {
                return value[key].get<std::vector<nlohmann::json>>();
            }
        }
    }
    return {};
}

DeviceInfo parse_device(const nlohmann::json& value) {
    DeviceInfo result;
    result.raw = value;
    result.gateway_sn = json_string(value, "device_sn");
    result.aircraft_sn = json_string(value, "aircraft_sn");
    if (result.aircraft_sn.empty()) result.aircraft_sn = json_string(value, "child_device_sn");
    result.remote_controller_sn = json_string(value, "remote_controller_sn");
    result.domain = value.value("domain", -1);
    if (value.contains("status") && value["status"].is_boolean()) result.online = value["status"].get<bool>();
    if (value.contains("children") && value["children"].is_object() &&
        value["children"].contains("status") && value["children"]["status"].is_boolean()) {
        result.aircraft_online = value["children"]["status"].get<bool>();
    }
    return result;
}

std::string mqtt_topic(const std::string& serial_number, const std::string& suffix) {
    if (serial_number.empty() || serial_number.find('/') != std::string::npos)
        throw std::invalid_argument("invalid device serial number for MQTT topic");
    if (suffix.empty() || suffix.front() == '/' || suffix.find("..") != std::string::npos)
        throw std::invalid_argument("invalid MQTT topic suffix");
    return "thing/product/" + serial_number + "/" + suffix;
}

WebSocketEvent parse_websocket_event(const std::string& raw) {
    const auto value = nlohmann::json::parse(raw);
    if (!value.is_object()) throw std::invalid_argument("WebSocket message must be an object");
    WebSocketEvent event;
    event.biz_code = value.value("biz_code", std::string{});
    event.data = value.value("data", nlohmann::json::object());
    return event;
}

YooxClient::YooxClient(const Config& config, std::shared_ptr<IHttpTransport> transport)
    : config_(config), transport_(std::move(transport)), workspace_id_(config.get("YOOX_WORKSPACE_ID")) {}

HttpRequest YooxClient::request(HttpMethod method, std::string path, std::string action,
                                bool pilot_base) const {
    HttpRequest value;
    value.method = method;
    value.path = std::move(path);
    value.action = std::move(action);
    value.base_url = pilot_base ? config_.pilot_base_url() : config_.base_url();
    value.token = token_;
    value.connect_timeout = std::chrono::milliseconds(
        static_cast<long long>(config_.get_double("YOOX_HTTP_CONNECT_TIMEOUT", 5) * 1000));
    value.total_timeout = std::chrono::milliseconds(
        static_cast<long long>(config_.get_double("YOOX_HTTP_CONTROL_TIMEOUT", 20) * 1000));
    return value;
}

nlohmann::json YooxClient::call(HttpRequest request_value, bool allow_api_error) {
    const bool mutating = request_value.side_effecting && is_mutating(request_value.method);
    const auto response = transport_->perform(request_value);
    nlohmann::json payload;
    try {
        payload = nlohmann::json::parse(response.body);
    } catch (const nlohmann::json::exception&) {
        const bool ambiguous = mutating &&
            (response.status == 408 || response.status >= 500 ||
             (response.status >= 200 && response.status < 300));
        throw ApiError(request_value.action,
                       "HTTP " + std::to_string(response.status) + " returned non-JSON data",
                       ambiguous, response.status);
    }
    if (response.status < 200 || response.status >= 300) {
        const int code = payload.is_object() ? payload.value("code", -1) : -1;
        const bool ambiguous = mutating && (response.status == 408 || response.status >= 500);
        throw ApiError(request_value.action,
                       "HTTP " + std::to_string(response.status) + ": " +
                           response_message(payload, response.body),
                       ambiguous, response.status, code);
    }
    if (!payload.is_object()) {
        throw ApiError(request_value.action, "JSON response is not an object", mutating, response.status);
    }
    const int code = payload.value("code", -1);
    if (code != 0 && !allow_api_error) {
        const auto message = response_message(payload, response.body);
        throw ApiError(request_value.action, message, mutating && unknown_result(code, message),
                       response.status, code);
    }
    return payload;
}

nlohmann::json YooxClient::login(bool pilot) {
    const std::string username_key = pilot ? "YOOX_PILOT_USERNAME" : "YOOX_WEB_USERNAME";
    const std::string password_key = pilot ? "YOOX_PILOT_PASSWORD" : "YOOX_WEB_PASSWORD";
    const std::string flag_key = pilot ? "YOOX_PILOT_FLAG" : "YOOX_WEB_FLAG";
    auto value = request(HttpMethod::post, "/manage/api/v1/login", pilot ? "Pilot login" : "Web login", pilot);
    value.has_body = true;
    value.side_effecting = false;
    value.body = {{"username", config_.require(username_key)},
                  {"password", config_.require(password_key)},
                  {"flag", config_.get_int(flag_key, pilot ? 2 : 1)}};
    value.token.clear();
    auto payload = call(std::move(value));
    const auto data = payload.value("data", nlohmann::json::object());
    token_ = json_string(data, "access_token");
    if (token_.empty()) throw ApiError("login", "success response has no access_token", false);
    const auto response_workspace = json_string(data, "workspace_id");
    if (!response_workspace.empty()) workspace_id_ = response_workspace;
    return data;
}

nlohmann::json YooxClient::refresh_token() {
    auto value = request(HttpMethod::post, "/manage/api/v1/token/refresh", "refresh token");
    value.side_effecting = false;
    auto payload = call(std::move(value));
    const auto data = payload.value("data", nlohmann::json::object());
    const auto refreshed = json_string(data, "access_token");
    if (!refreshed.empty()) token_ = refreshed;
    return data;
}

nlohmann::json YooxClient::current_workspace() {
    return call(request(HttpMethod::get, "/manage/api/v1/workspaces/current", "get current workspace"))
        .value("data", nlohmann::json::object());
}

nlohmann::json YooxClient::current_user() {
    return call(request(HttpMethod::get, "/manage/api/v1/users/current", "get current user"))
        .value("data", nlohmann::json::object());
}

std::vector<DeviceInfo> YooxClient::list_devices() {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    const auto data = call(request(HttpMethod::get,
        "/manage/api/v1/devices/" + workspace + "/devices", "list devices")).value("data", nlohmann::json::array());
    std::vector<DeviceInfo> result;
    for (const auto& item : unwrap_items(data)) result.push_back(parse_device(item));
    return result;
}

nlohmann::json YooxClient::get_device(const std::string& gateway_sn) {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    return call(request(HttpMethod::get,
        "/manage/api/v1/devices/" + workspace + "/devices/" + gateway_sn,
        "get device")).value("data", nlohmann::json::object());
}

nlohmann::json YooxClient::get_hms(const std::string& device_sn) {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    auto path = "/manage/api/v1/devices/" + workspace + "/devices/hms";
    if (!device_sn.empty()) path += "/" + device_sn;
    return call(request(HttpMethod::get, path, "get HMS")).value("data", nlohmann::json::array());
}

nlohmann::json YooxClient::live_capacity() {
    return call(request(HttpMethod::get, "/manage/api/v1/live/capacity", "get live capacity"))
        .value("data", nlohmann::json::array());
}

nlohmann::json YooxClient::acquire_flight_authority(const std::string& gateway_sn) {
    return call(request(HttpMethod::post, "/control/api/v1/devices/" + gateway_sn +
        "/authority/flight", "acquire flight authority"));
}

nlohmann::json YooxClient::acquire_payload_authority(
    const std::string& gateway_sn, const std::string& payload_index) {
    auto value = request(HttpMethod::post, "/control/api/v1/devices/" + gateway_sn +
        "/authority/payload", "acquire payload authority");
    value.has_body = true;
    value.body = {{"payload_index", payload_index}};
    return call(std::move(value));
}

nlohmann::json YooxClient::payload_command(const std::string& gateway_sn,
                                            const std::string& command,
                                            const nlohmann::json& data) {
    auto value = request(HttpMethod::post, "/control/api/v1/devices/" + gateway_sn +
        "/payload/commands", "payload command " + command);
    value.has_body = true;
    value.body = {{"cmd", command}, {"data", data}};
    return call(std::move(value));
}

nlohmann::json YooxClient::remote_job(const std::string& gateway_sn, const std::string& method,
                                       const std::optional<nlohmann::json>& body) {
    auto value = request(HttpMethod::post, "/control/api/v1/devices/" + gateway_sn +
        "/jobs/" + method, method);
    if (body) { value.has_body = true; value.body = *body; }
    return call(std::move(value));
}

nlohmann::json YooxClient::fly_to_point(const std::string& gateway_sn, const FlightTarget& target) {
    auto value = request(HttpMethod::post, "/control/api/v1/devices/" + gateway_sn +
        "/jobs/fly-to-point", "fly to point");
    value.has_body = true;
    value.body = {{"max_speed", static_cast<int>(target.speed)},
                  {"points", {{{"latitude", target.latitude}, {"longitude", target.longitude},
                                {"height", target.height}}}}};
    return call(std::move(value));
}

nlohmann::json YooxClient::stop_fly_to_point(const std::string& gateway_sn) {
    return call(request(HttpMethod::delete_, "/control/api/v1/devices/" + gateway_sn +
        "/jobs/fly-to-point", "stop fly to point"));
}

nlohmann::json YooxClient::takeoff_to_point(const std::string& gateway_sn,
                                             const FlightTarget& target) {
    auto value = request(HttpMethod::post, "/control/api/v1/devices/" + gateway_sn +
        "/jobs/takeoff-to-point", "takeoff to point");
    value.has_body = true;
    value.body = {{"target_longitude", target.longitude}, {"target_latitude", target.latitude},
                  {"target_height", target.height}, {"max_speed", target.speed}};
    return call(std::move(value));
}

std::optional<PointFlightState> YooxClient::point_flight_status(const std::string& gateway_sn) {
    const auto data = call(request(HttpMethod::get, "/control/api/v1/devices/" + gateway_sn +
        "/jobs/point-flight/status", "get point flight status")).value("data", nlohmann::json());
    if (!data.is_object()) return std::nullopt;
    PointFlightState state;
    state.kind = json_string(data, "kind");
    state.status = json_string(data, "status");
    state.task_id = json_string(data, "fly_to_id");
    if (state.task_id.empty()) state.task_id = json_string(data, "flight_id");
    state.active = data.value("active", false);
    state.updated_at = data.value("updated_at", std::int64_t{0});
    if (data.contains("result") && data["result"].is_number_integer()) state.result = data["result"].get<int>();
    return state;
}

nlohmann::json YooxClient::target_detection(const std::string& gateway_sn, bool open,
                                             int lens, const std::vector<int>& targets) {
    auto value = request(open ? HttpMethod::post : HttpMethod::delete_,
        "/control/api/v1/devices/" + gateway_sn + "/target-detection",
        open ? "open target detection" : "close target detection");
    if (open) {
        value.has_body = true;
        value.body = {{"ai_lens_type", lens}, {"scene_type", 0}, {"target_type_list", targets}};
    }
    return call(std::move(value));
}

nlohmann::json YooxClient::live_start(const std::string& video_id, int quality,
                                       const std::string& lens) {
    auto value = request(HttpMethod::post, "/manage/api/v1/live/streams/start", "start live stream");
    value.total_timeout = std::chrono::seconds(65);
    value.has_body = true;
    value.body = {{"url_type", 2}, {"video_id", video_id}, {"video_quality", quality}};
    if (!lens.empty()) value.body["video_type"] = lens;
    return call(std::move(value));
}

nlohmann::json YooxClient::live_stop(const std::string& video_id) {
    auto value = request(HttpMethod::post, "/manage/api/v1/live/streams/stop", "stop live stream");
    value.total_timeout = std::chrono::seconds(65); value.has_body = true;
    value.body = {{"video_id", video_id}};
    return call(std::move(value));
}

nlohmann::json YooxClient::live_update(const std::string& video_id, int quality) {
    auto value = request(HttpMethod::post, "/manage/api/v1/live/streams/update", "update live quality");
    value.total_timeout = std::chrono::seconds(65); value.has_body = true;
    value.body = {{"video_id", video_id}, {"video_quality", quality}};
    return call(std::move(value));
}

nlohmann::json YooxClient::live_switch(const std::string& video_id, const std::string& lens) {
    auto value = request(HttpMethod::post, "/manage/api/v1/live/streams/switch", "switch live lens");
    value.total_timeout = std::chrono::seconds(65); value.has_body = true;
    value.body = {{"video_id", video_id}, {"video_type", lens}};
    return call(std::move(value));
}

nlohmann::json YooxClient::drc_connect() {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    auto value = request(HttpMethod::post, "/control/api/v1/workspaces/" + workspace +
        "/drc/connect", "get DRC credentials");
    value.has_body = true; value.body = {{"client_id", ""}, {"expire_sec", 3600}};
    return call(std::move(value)).value("data", nlohmann::json::object());
}

nlohmann::json YooxClient::drc_enter(const std::string& client_id, const std::string& gateway_sn) {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    auto value = request(HttpMethod::post, "/control/api/v1/workspaces/" + workspace +
        "/drc/enter", "enter DRC");
    value.has_body = true;
    value.body = {{"client_id", client_id}, {"dock_sn", gateway_sn}, {"expire_sec", 3600},
                  {"device_info", {{"osd_frequency", 10}, {"hsi_frequency", 1}}}};
    return call(std::move(value)).value("data", nlohmann::json::object());
}

nlohmann::json YooxClient::drc_exit(const std::string& client_id, const std::string& gateway_sn) {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    auto value = request(HttpMethod::post, "/control/api/v1/workspaces/" + workspace +
        "/drc/exit", "exit DRC");
    value.has_body = true; value.body = {{"client_id", client_id}, {"dock_sn", gateway_sn}};
    return call(std::move(value));
}

nlohmann::json YooxClient::list_waylines() {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    auto value = request(HttpMethod::get, "/wayline/api/v1/workspaces/" + workspace +
        "/waylines", "list waylines");
    value.query = {{"page", "1"}, {"page_size", "50"}, {"order_by", "update_time desc"}};
    return call(std::move(value)).value("data", nlohmann::json::object());
}

nlohmann::json YooxClient::upload_wayline(const std::filesystem::path& file) {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    auto value = request(HttpMethod::post, "/wayline/api/v1/workspaces/" + workspace +
        "/waylines/file/upload", "upload KMZ");
    value.upload_file = file; value.total_timeout = std::chrono::seconds(60);
    return call(std::move(value));
}

nlohmann::json YooxClient::list_jobs() {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    auto value = request(HttpMethod::get, "/wayline/api/v1/workspaces/" + workspace +
        "/jobs", "list wayline jobs");
    value.query = {{"page", "1"}, {"page_size", "50"}};
    return call(std::move(value)).value("data", nlohmann::json::object());
}

nlohmann::json YooxClient::create_wayline_job(const nlohmann::json& body) {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    auto value = request(HttpMethod::post, "/wayline/api/v1/workspaces/" + workspace +
        "/flight-tasks", "create wayline job");
    value.has_body = true; value.body = body; value.total_timeout = std::chrono::seconds(45);
    return call(std::move(value));
}

nlohmann::json YooxClient::change_wayline_job(const std::string& job_id, int status) {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    auto value = request(HttpMethod::put, "/wayline/api/v1/workspaces/" + workspace +
        "/jobs/" + job_id, status == 0 ? "pause wayline" : "resume wayline");
    value.has_body = true; value.body = {{"status", status}};
    return call(std::move(value));
}

nlohmann::json YooxClient::cancel_wayline_job(const std::string& job_id) {
    const auto workspace = workspace_id_.empty() ? config_.require("YOOX_WORKSPACE_ID") : workspace_id_;
    auto value = request(HttpMethod::delete_, "/wayline/api/v1/workspaces/" + workspace +
        "/jobs", "cancel wayline job");
    value.query = {{"job_id", job_id}}; value.total_timeout = std::chrono::seconds(30);
    return call(std::move(value));
}

nlohmann::json to_json(const DrcMessage& message) {
    return {{"tid", message.tid}, {"bid", message.bid}, {"timestamp", message.timestamp},
            {"method", message.method}, {"data", message.data}};
}

DrcMessage make_drc_message(const std::string& method, nlohmann::json data) {
    static thread_local std::mt19937_64 random(std::random_device{}());
    std::ostringstream id;
    id << std::hex << random() << random();
    const auto now = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    return {id.str().substr(0, 32), id.str().substr(0, 32), now, method, std::move(data)};
}

}  // namespace yoox

