#include "yoox/demo_runner.h"

#include "yoox/command_models.h"
#include "yoox/config.h"
#include "yoox/http_client.h"
#include "yoox/kmz.h"
#include "yoox/logger.h"
#include "yoox/mqtt_client.h"
#include "yoox/safety_guard.h"
#include "yoox/task_tracker.h"
#include "yoox/websocket_client.h"
#include "yoox/yoox_client.h"

#include <atomic>
#include <algorithm>
#include <chrono>
#include <cctype>
#include <cmath>
#include <csignal>
#include <cstdlib>
#include <filesystem>
#include <future>
#include <iomanip>
#include <iostream>
#include <mutex>
#include <limits>
#include <optional>
#include <regex>
#include <set>
#include <sstream>
#include <thread>
#ifdef _WIN32
#include <windows.h>
#endif

namespace yoox {
namespace {

using json = nlohmann::json;
std::atomic<bool> interrupted{false};

void on_signal(int) { interrupted.store(true); }

std::string action(const Config& config, const std::string& fallback) {
    return config.positional().empty() ? fallback : config.positional().front();
}

double number_option(const Config& config, const std::string& option_name,
                     const std::string& env_name, double fallback) {
    const auto raw = config.option(option_name);
    if (!raw.empty()) {
        try { return std::stod(raw); }
        catch (...) { throw ConfigError("--" + option_name + " must be a number"); }
    }
    return config.get_double(env_name, fallback);
}

int int_option(const Config& config, const std::string& name, int fallback) {
    const auto raw = config.option(name);
    if (raw.empty()) return fallback;
    try { return std::stoi(raw); }
    catch (...) { throw ConfigError("--" + name + " must be an integer"); }
}

std::string required_option(const Config& config, const std::string& name,
                            const std::string& env_name = "") {
    const auto value = config.option(name, env_name.empty() ? "" : config.get(env_name));
    if (config.is_placeholder(value)) throw ConfigError("missing --" + name + (env_name.empty() ? "" : " / " + env_name));
    return value;
}

std::string confirmation(const Config& config, const std::string& phrase) {
    auto supplied = config.option("confirm");
    if (!supplied.empty() || !config.execute()) return supplied;
    std::cout << "Type " << phrase << " to continue: " << std::flush;
    std::getline(std::cin, supplied);
    return supplied;
}

json sanitise_json(json value) {
    if (value.is_object()) {
        for (auto& [key, child] : value.items()) {
            std::string lowered = key;
            std::transform(lowered.begin(), lowered.end(), lowered.begin(), [](unsigned char c) {
                return static_cast<char>(std::tolower(c));
            });
            const bool secret = lowered.find("password") != std::string::npos ||
                                lowered.find("token") != std::string::npos ||
                                lowered.find("secret") != std::string::npos ||
                                lowered.find("private_key") != std::string::npos;
            const bool identifier = lowered == "sn" ||
                (lowered.size() >= 3 && lowered.compare(lowered.size() - 3, 3, "_sn") == 0) ||
                lowered == "workspace_id" || lowered == "user_id" || lowered == "client_id";
            const bool coordinate = lowered == "latitude" || lowered == "longitude" ||
                                    lowered == "target_latitude" || lowered == "target_longitude";
            if (secret) child = "***";
            else if (identifier && child.is_string()) child = Logger::mask_identifier(child.get<std::string>());
            else if (coordinate) child = "***";
            else child = sanitise_json(std::move(child));
        }
    } else if (value.is_array()) {
        for (auto& child : value) child = sanitise_json(std::move(child));
    }
    return value;
}

std::string mask_topic(std::string topic) {
    const std::string prefix = "thing/product/";
    if (topic.rfind(prefix, 0) != 0) return topic;
    const auto end = topic.find('/', prefix.size());
    if (end == std::string::npos) return topic;
    return prefix + Logger::mask_identifier(topic.substr(prefix.size(), end - prefix.size())) +
           topic.substr(end);
}

void print_json(const json& value) {
    std::cout << Logger::redact(sanitise_json(value).dump(2)) << '\n';
}

void print_plan(const std::string& method, const std::string& path, const json& body = json()) {
    std::cout << "[DRY-RUN] " << method << ' ' << path << '\n';
    if (!body.is_null()) print_json(body);
}

std::shared_ptr<HttpClient> http(const Config& config) {
    return std::make_shared<HttpClient>(config);
}

void web_login(YooxClient& client) {
    const auto data = client.login(false);
    const auto token = data.value("access_token", std::string{});
    std::cout << "[OK] Web login; token fingerprint="
              << (token.empty() ? "(missing)" : token.substr(0, std::min<std::size_t>(10, token.size())) + "...")
              << '\n';
}

DeviceSafetyState resolve_device(YooxClient& client, const Config& config,
                                 const std::string& gateway, bool check_task) {
    DeviceSafetyState state;
    state.gateway_sn = gateway;
    const auto configured_aircraft = config.get("YOOX_DRONE_SN");
    for (const auto& device : client.list_devices()) {
        if (device.gateway_sn == gateway) {
            state.gateway_online = device.online.value_or(false);
            state.aircraft_sn = device.aircraft_sn.empty() ? configured_aircraft : device.aircraft_sn;
            state.aircraft_online = device.aircraft_online.value_or(false);
        }
        if (!configured_aircraft.empty() && device.gateway_sn == configured_aircraft) {
            state.aircraft_online = device.online.value_or(state.aircraft_online);
        }
    }
    if (state.aircraft_sn.empty()) state.aircraft_sn = configured_aircraft;
    if (!config.option("mode-code").empty()) state.mode_code = int_option(config, "mode-code", -1);
    if (check_task) {
        const auto task = client.point_flight_status(gateway);
        state.conflicting_task = task && task->active;
    }
    return state;
}

bool authorize(YooxClient& client, const Config& config, const std::string& action_name,
               const std::string& phrase, bool airborne, bool ground_idle,
               std::optional<FlightTarget> target = std::nullopt,
               const std::string& gateway_override = "",
               const std::string& supplied_confirmation = "") {
    const std::string gateway = gateway_override.empty() ? config.require("YOOX_DOCK_SN") : gateway_override;
    if (!config.execute()) {
        if (target) {
            const auto errors = SafetyGuard::validate_target(*target, action_name == "fly_to_point",
                                                              action_name == "look_at" ? 10000 : 1500);
            if (!errors.empty()) throw ConfigError(errors.front());
        }
        std::cout << "[DRY-RUN] Safety gate retained; no authority or write request was sent.\n";
        return false;
    }

    auto device = resolve_device(client, config, gateway,
        action_name == "fly_to_point" || action_name == "takeoff_to_point" ||
        action_name == "wayline_create" || action_name == "drc");
    SafetyRequest request_value;
    request_value.action = action_name;
    request_value.required_confirmation = phrase;
    request_value.supplied_confirmation = supplied_confirmation.empty()
        ? confirmation(config, phrase) : supplied_confirmation;
    request_value.execute = true;
    request_value.needs_airborne = airborne;
    request_value.needs_ground_idle = ground_idle;
    request_value.target = target;
    request_value.device = std::move(device);
    const auto decision = SafetyGuard::evaluate(request_value);
    if (!decision.allowed) {
        std::ostringstream message;
        message << "safety gate rejected " << action_name;
        for (const auto& reason : decision.reasons) message << "\n  - " << reason;
        throw std::runtime_error(message.str());
    }
    return true;
}

MqttOptions regular_mqtt(const Config& config, const std::string& prefix) {
    const bool tls = config.get_bool("YOOX_MQTT_TLS", false);
    const auto host = config.get("YOOX_MQTT_HOST", config.get("YOOX_SERVER_IP", "127.0.0.1"));
    const auto port = config.get_int("YOOX_MQTT_PORT", tls ? 8883 : 1883);
    return {(tls ? "ssl://" : "tcp://") + host + ":" + std::to_string(port),
            prefix + "-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()),
            config.get("YOOX_MQTT_USERNAME"), config.get("YOOX_MQTT_PASSWORD"), tls,
            config.get("YOOX_MQTT_CA_FILE"), 30};
}

void run_mqtt_watch(const Config& config, const std::vector<std::pair<std::string, int>>& topics,
                    int seconds, const std::function<void(const std::string&, const json&)>& handler) {
    MqttClient mqtt(regular_mqtt(config, "yoox-cpp-read"));
    mqtt.set_message_handler([&](const std::string& topic, const std::string& raw, std::uint64_t) {
        try { handler(topic, json::parse(raw)); }
        catch (const std::exception& exception) {
            Logger::log(LogLevel::warning, std::string("invalid MQTT JSON: ") + exception.what());
        }
    });
    if (!mqtt.connect() || !mqtt.subscribe(topics)) throw std::runtime_error("MQTT connect/SUBACK failed");
    std::cout << "[OK] MQTT connected and subscriptions acknowledged\n";
    const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (!interrupted.load() && std::chrono::steady_clock::now() < end)
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    mqtt.disconnect();
}

void run_websocket_watch(const Config& config, const std::string& token, int seconds,
                         std::function<void(const json&)> handler) {
    WebSocketClient websocket;
    websocket.set_message_handler([&](const std::string& raw) {
        try { handler(json::parse(raw)); }
        catch (const std::exception& exception) {
            Logger::log(LogLevel::warning, std::string("invalid WebSocket JSON: ") + exception.what());
        }
    });
    const auto base = config.websocket_url();
    const auto url = base + (base.find('?') == std::string::npos ? "?" : "&") +
                     "x-auth-token=" + url_encode(token);
    if (!websocket.connect(url)) throw std::runtime_error("WebSocket connection timed out");
    std::cout << "[OK] WebSocket connected (token hidden)\n";
    const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (!interrupted.load() && std::chrono::steady_clock::now() < end)
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    websocket.close();
}

FlightTarget target_from(const Config& config, bool takeoff = false, const std::string& prefix = "") {
    const std::string lat_env = prefix.empty() ?
        (takeoff ? "YOOX_TAKEOFF_TARGET_LATITUDE" : "YOOX_TARGET_LATITUDE") : prefix + "LATITUDE";
    const std::string lon_env = prefix.empty() ?
        (takeoff ? "YOOX_TAKEOFF_TARGET_LONGITUDE" : "YOOX_TARGET_LONGITUDE") : prefix + "LONGITUDE";
    const std::string height_env = prefix.empty() ? "YOOX_TARGET_HEIGHT" : prefix + "HEIGHT";
    return {number_option(config, "lat", lat_env, 0), number_option(config, "lon", lon_env, 0),
            number_option(config, "height", height_env, 0),
            number_option(config, "speed", prefix.empty() ? "YOOX_TARGET_MAX_SPEED" : "YOOX_DUAL_MAX_SPEED", 5)};
}

void show_point_state(const std::optional<PointFlightState>& state) {
    if (!state) { std::cout << "No saved point-flight task\n"; return; }
    std::cout << "kind=" << state->kind << " status=" << state->status
              << " active=" << std::boolalpha << state->active
              << " task_id=" << state->task_id << '\n';
}

std::optional<PointFlightState> wait_for_task(YooxClient& client, const std::string& gateway,
                                              const std::string& kind, std::int64_t baseline,
                                              int seconds) {
    const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (!interrupted.load() && std::chrono::steady_clock::now() < end) {
        try {
            auto state = client.point_flight_status(gateway);
            if (state && state->kind == kind && !state->task_id.empty() && state->updated_at > baseline)
                return state;
        } catch (const ApiError&) {}
        std::this_thread::sleep_for(std::chrono::milliseconds(750));
    }
    return std::nullopt;
}

std::optional<PointFlightState> wait_for_terminal_state(
    YooxClient& client, const std::string& gateway, const PointFlightState& initial, int seconds) {
    PointFlightState latest = initial;
    std::string last_status;
    const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (!interrupted.load() && std::chrono::steady_clock::now() < end) {
        if (latest.status != last_status) {
            show_point_state(latest);
            last_status = latest.status;
        }
        if (TaskTracker::terminal(latest)) return latest;
        std::this_thread::sleep_for(std::chrono::milliseconds(1500));
        try {
            const auto state = client.point_flight_status(gateway);
            if (state && state->kind == initial.kind && state->task_id == initial.task_id)
                latest = *state;
        } catch (const ApiError& error) {
            Logger::log(LogLevel::warning, error.what());
        }
    }
    return std::nullopt;
}

int demo_login(const Config& config, YooxClient& client) {
    web_login(client);
    const auto workspace = client.current_workspace();
    std::cout << "workspace_id="
              << Logger::mask_identifier(workspace.value("workspace_id", std::string("(unknown)"))) << '\n';
    if (!config.is_placeholder(config.get("YOOX_PILOT_PASSWORD"))) {
        YooxClient pilot(config, http(config));
        const auto data = pilot.login(true);
        std::cout << "[OK] Pilot login; broker address present=" << data.contains("mqtt_addr") << '\n';
    }
    if (config.has_flag("refresh")) {
        client.refresh_token();
        std::cout << "[OK] Token refresh response accepted; full token hidden.\n";
    }
    return 0;
}

int demo_devices(const Config& config, YooxClient& client) {
    web_login(client);
    const auto user = client.current_user();
    const auto workspace = client.current_workspace();
    std::cout << "current_user_loaded=" << user.is_object()
              << " workspace=" << Logger::mask_identifier(
                     workspace.value("workspace_id", std::string{})) << '\n';
    for (const auto& device : client.list_devices()) {
        std::cout << "gateway=" << Logger::mask_identifier(device.gateway_sn)
                  << " domain=" << device.domain << " online="
                  << (device.online ? (*device.online ? "true" : "false") : "unknown")
                  << " aircraft=" << Logger::mask_identifier(device.aircraft_sn) << '\n';
        const auto detail = client.get_device(device.gateway_sn);
        std::cout << "  detail domain=" << detail.value("domain", -1)
                  << " firmware_present=" << detail.contains("firmware_version") << '\n';
    }
    const auto configured_gateway = config.get("YOOX_DOCK_SN");
    const auto hms = client.get_hms(config.is_placeholder(configured_gateway) ? "" : configured_gateway);
    std::cout << "HMS entries=" << unwrap_items(hms).size() << '\n';
    print_json(client.live_capacity());
    return 0;
}

int demo_websocket(const Config& config, YooxClient& client) {
    web_login(client);
    run_websocket_watch(config, client.token(), int_option(config, "seconds", 30), [](const json& message) {
        const auto code = message.value("biz_code", std::string("unknown"));
        std::cout << "[WS] " << code << ' '
                  << Logger::redact(sanitise_json(message.value("data", json::object())).dump()) << '\n';
    });
    return 0;
}

int demo_mqtt(const Config& config) {
    run_mqtt_watch(config, {{"thing/product/+/osd", 0}}, int_option(config, "seconds", 30),
        [](const std::string& topic, const json& payload) {
            const auto data = payload.value("data", json::object());
            std::cout << "[MQTT] " << mask_topic(topic) << " mode=" << data.value("mode_code", -1)
                      << " height=" << data.value("height", 0.0)
                      << " battery=" << data.value("capacity_percent", -1) << '\n';
        });
    return 0;
}

int payload_demo(const Config& config, YooxClient& client, const std::string& command,
                 json data, const std::string& phrase = "EXECUTE PAYLOAD") {
    const auto gateway = config.require("YOOX_DOCK_SN");
    const auto payload_index = config.require("YOOX_PAYLOAD_INDEX");
    if (!SafetyGuard::is_valid_payload_index(payload_index)) throw ConfigError("invalid YOOX_PAYLOAD_INDEX");
    data["payload_index"] = payload_index;
    print_plan("POST", "/control/api/v1/devices/{gateway}/authority/payload", {{"payload_index", payload_index}});
    print_plan("POST", "/control/api/v1/devices/{gateway}/payload/commands", {{"cmd", command}, {"data", data}});
    if (!config.execute()) return 0;
    web_login(client);
    if (!authorize(client, config, "payload_" + command, phrase, false, false)) return 0;
    client.acquire_payload_authority(gateway, payload_index);
    client.payload_command(gateway, command, data);
    std::cout << "[ACCEPTED] Platform/device accepted payload request; verify OSD/camera state.\n";
    return 0;
}

int demo_flyto(const Config& config, YooxClient& client) {
    const auto gateway = config.require("YOOX_DOCK_SN");
    const auto operation = action(config, "status");
    if (operation == "status") { web_login(client); show_point_state(client.point_flight_status(gateway)); return 0; }
    if (operation == "stop") {
        print_plan("DELETE", "/control/api/v1/devices/{gateway}/jobs/fly-to-point");
        if (!config.execute()) return 0;
        web_login(client);
        const auto state = client.point_flight_status(gateway);
        if (!state || !state->active || state->kind != "flyto") {
            std::cout << "No active FlyTo task; stop was not sent.\n"; return 0;
        }
        if (!authorize(client, config, "stop_fly_to_point", "STOP FLYTO", true, false)) return 0;
        client.acquire_flight_authority(gateway);
        client.stop_fly_to_point(gateway);
        std::cout << "[ACCEPTED] Stop request accepted; query status before any retry.\n";
        return 0;
    }
    if (operation != "go") throw ConfigError("action must be go, stop or status");
    const auto target = target_from(config);
    print_plan("POST", "/control/api/v1/devices/{gateway}/jobs/fly-to-point",
               {{"max_speed", static_cast<int>(target.speed)}, {"points", {{{"latitude", target.latitude},
                 {"longitude", target.longitude}, {"height", target.height}}}}});
    if (!config.execute()) { SafetyGuard::validate_target(target, true); return 0; }
    web_login(client);
    const auto before = client.point_flight_status(gateway);
    const auto baseline = before ? before->updated_at : 0;
    if (!authorize(client, config, "fly_to_point", "FLY TO POINT", true, false, target)) return 0;
    client.acquire_flight_authority(gateway);
    try { client.fly_to_point(gateway, target); }
    catch (const ApiError& error) {
        if (!error.ambiguous()) throw;
        std::cerr << error.what() << '\n';
    }
    const auto recovered = wait_for_task(client, gateway, "flyto", baseline, 15);
    show_point_state(recovered);
    if (!recovered) {
        std::cerr << "Result unknown. Do not repeat; inspect OSD and status.\n"; return 2;
    }
    const auto terminal = wait_for_terminal_state(
        client, gateway, *recovered,
        int_option(config, "wait", config.get_int("YOOX_POINT_FLIGHT_WAIT_SECONDS", 300)));
    if (!terminal) {
        std::cerr << "Wait timed out; the task may still be running. Do not repeat.\n";
        return 2;
    }
    return terminal->result.value_or(0) == 0 && terminal->status != "wayline_failed" ? 0 : 1;
}

int demo_takeoff(const Config& config, YooxClient& client) {
    const auto gateway = config.require("YOOX_DOCK_SN");
    if (action(config, "go") == "status") { web_login(client); show_point_state(client.point_flight_status(gateway)); return 0; }
    const auto target = target_from(config, true);
    print_plan("POST", "/control/api/v1/devices/{gateway}/jobs/takeoff-to-point",
               {{"target_longitude", target.longitude}, {"target_latitude", target.latitude},
                {"target_height", target.height}, {"max_speed", target.speed}});
    if (!config.execute()) return 0;
    web_login(client);
    const auto before = client.point_flight_status(gateway);
    const auto baseline = before ? before->updated_at : 0;
    if (!authorize(client, config, "takeoff_to_point", "TAKEOFF", false, true, target)) return 0;
    client.acquire_flight_authority(gateway);
    try { client.takeoff_to_point(gateway, target); }
    catch (const ApiError& error) { if (!error.ambiguous()) throw; std::cerr << error.what() << '\n'; }
    const auto recovered = wait_for_task(client, gateway, "takeoff", baseline, 15);
    show_point_state(recovered);
    if (!recovered) { std::cerr << "Result unknown. Never repeat takeoff before state/OSD confirmation.\n"; return 2; }
    const auto terminal = wait_for_terminal_state(
        client, gateway, *recovered,
        int_option(config, "wait", config.get_int("YOOX_POINT_FLIGHT_WAIT_SECONDS", 300)));
    if (!terminal) {
        std::cerr << "Wait timed out; takeoff may still be running. Never repeat blindly.\n";
        return 2;
    }
    return terminal->result.value_or(0) == 0 && terminal->status != "wayline_failed" ? 0 : 1;
}

int demo_remote_job(const Config& config, YooxClient& client, const std::string& default_method) {
    const auto method = config.option("method", default_method);
    const std::set<std::string> allowed = {
        "cover_open", "cover_close", "drone_open", "drone_close", "return_home",
        "return_home_cancel", "device_reboot", "charge_open", "charge_close",
        "putter_open", "putter_close", "supplement_light_open", "supplement_light_close",
        "debug_mode_open", "debug_mode_close"
    };
    if (allowed.count(method) == 0) throw ConfigError("unsupported device job method");
    const auto gateway = config.require("YOOX_DOCK_SN");
    print_plan("POST", "/control/api/v1/devices/{gateway}/jobs/" + method);
    if (!config.execute()) return 0;
    web_login(client);
    const bool flight = method == "return_home" || method == "return_home_cancel";
    const auto phrase = method == "return_home_cancel" ? "CANCEL RETURN HOME" :
                        method == "return_home" ? "RETURN HOME" : "EXECUTE DEVICE COMMAND";
    if (!authorize(client, config, method, phrase, flight, false)) return 0;
    if (flight) client.acquire_flight_authority(gateway);
    client.remote_job(gateway, method);
    std::cout << "[ACCEPTED] Request accepted; this does not mean the physical action completed.\n";
    return 0;
}

std::optional<std::filesystem::path> find_on_path(const std::string& executable) {
    const char* raw_path = std::getenv("PATH");
    if (!raw_path) return std::nullopt;
#ifdef _WIN32
    constexpr char separator = ';';
    const std::string filename = executable + ".exe";
#else
    constexpr char separator = ':';
    const std::string filename = executable;
#endif
    std::stringstream stream(raw_path);
    std::string directory;
    while (std::getline(stream, directory, separator)) {
        const auto candidate = std::filesystem::path(directory) / filename;
        if (std::filesystem::is_regular_file(candidate)) return candidate;
    }
    return std::nullopt;
}

std::string playback_url(const Config& config, const std::string& video_id) {
    const auto first = video_id.find('/');
    const auto second = first == std::string::npos ? std::string::npos : video_id.find('/', first + 1);
    if (first == std::string::npos || second == std::string::npos)
        throw ConfigError("video-id must be drone_sn/payload_index/video_type-index");
    const auto drone = video_id.substr(0, first);
    const auto payload = video_id.substr(first + 1, second - first - 1);
    static const std::regex safe(R"(^[A-Za-z0-9._-]+$)");
    const auto host = config.get("YOOX_RTSP_HOST", config.get("YOOX_SERVER_IP", "127.0.0.1"));
    if (!std::regex_match(drone, safe) || !std::regex_match(payload, safe) ||
        !std::regex_match(host, safe)) throw ConfigError("unsafe RTSP host/video identifier");
    return "rtsp://" + host + ":" + std::to_string(config.get_int("YOOX_RTSP_PORT", 8554)) +
           "/" + drone + "-" + payload;
}

std::optional<bool> probe_rtsp(const Config& config, const std::string& video_id) {
    const auto ffprobe = find_on_path("ffprobe");
    if (!ffprobe) {
        std::cout << "[UNKNOWN] ffprobe is not installed; media presence was not inferred as false.\n";
        return std::nullopt;
    }
    const auto url = playback_url(config, video_id);
    std::string command = "\"" + ffprobe->string() +
        "\" -v error -rtsp_transport tcp -show_entries stream=codec_name,width,height "
        "-of default=noprint_wrappers=1 \"" + url + "\"";
#ifdef _WIN32
    command += " >NUL 2>&1";
#else
    command += " >/dev/null 2>&1";
#endif
    const bool success = std::system(command.c_str()) == 0;
    std::cout << (success ? "[OK] RTSP first frame/stream detected\n"
                          : "[NOT READY] ffprobe did not detect a playable RTSP stream\n");
    return success;
}

int demo_live(const Config& config, YooxClient& client) {
    const auto operation = action(config, "capacity");
    if (operation == "capacity") { web_login(client); print_json(client.live_capacity()); return 0; }
    const auto video = required_option(config, "video-id");
    if (operation == "probe") {
        const auto result = probe_rtsp(config, video);
        return result == std::optional<bool>(false) ? 1 : 0;
    }
    json body = {{"video_id", video}};
    if (operation == "start" || operation == "recover")
        body.update({{"url_type", 2}, {"video_quality", int_option(config, "quality", 2)}});
    if (operation == "quality") body["video_quality"] = int_option(config, "quality", 2);
    if (operation == "switch") body["video_type"] = config.option("lens", "zoom");
    print_plan("POST", "/manage/api/v1/live/streams/" +
               std::string(operation == "quality" ? "update" : operation == "recover" ? "start" : operation), body);
    if (!config.execute()) return 0;
    web_login(client);
    if (!authorize(client, config, "livestream_" + operation, "EXECUTE LIVESTREAM", false, false)) return 0;
    if (operation == "start" || operation == "recover") {
        const auto lens = config.option("lens");
        const auto response = client.live_start(video, int_option(config, "quality", 2), lens);
        const auto live_data = response.value("data", json::object());
        std::cout << "publisher reused=" << live_data.value("reused", false)
                  << " started_by_request=" << live_data.value("started_by_request", false) << '\n';
        if (operation == "recover") {
            std::this_thread::sleep_for(std::chrono::seconds(4));
            if (probe_rtsp(config, video) == std::optional<bool>(false)) {
                std::string desired = lens;
                if (desired.empty()) {
                    const auto slash = video.rfind('/');
                    desired = slash == std::string::npos ? "zoom" : video.substr(slash + 1, video.find('-', slash) - slash - 1);
                }
                if (std::set<std::string>{"normal", "wide", "zoom", "ir"}.count(desired) == 0)
                    throw ConfigError("lens does not support safe encoder recovery");
                const auto alternate = desired == "ir" ? "zoom" : "ir";
                client.live_switch(video, alternate);
                std::this_thread::sleep_for(std::chrono::milliseconds(1800));
                client.live_switch(video, desired);
                std::this_thread::sleep_for(std::chrono::milliseconds(1200));
                probe_rtsp(config, video);
            }
        } else {
            probe_rtsp(config, video);
        }
    }
    else if (operation == "stop") client.live_stop(video);
    else if (operation == "quality") client.live_update(video, int_option(config, "quality", 2));
    else if (operation == "switch") client.live_switch(video, config.option("lens", "zoom"));
    else throw ConfigError("action must be capacity, probe, start, recover, stop, quality or switch");
    std::cout << "[ACCEPTED] Live request accepted; verify publisher/first frame separately.\n";
    return 0;
}

int demo_target_detection(const Config& config, YooxClient& client) {
    const bool open = action(config, "status") == "open";
    const auto operation = action(config, "status");
    if (operation == "status") {
        std::cout << "Target-detection results are read through demo_03_websocket_osd.\n"; return 0;
    }
    if (operation != "open" && operation != "close") throw ConfigError("action must be open, close or status");
    const auto gateway = config.require("YOOX_DOCK_SN");
    print_plan(open ? "POST" : "DELETE", "/control/api/v1/devices/{gateway}/target-detection",
               open ? json{{"ai_lens_type", int_option(config, "lens", 0)}, {"scene_type", 0},
                           {"target_type_list", {0, 1}}} : json());
    if (!config.execute()) return 0;
    web_login(client);
    if (!authorize(client, config, "target_detection", "EXECUTE TARGET DETECTION", false, false)) return 0;
    client.acquire_payload_authority(gateway, config.require("YOOX_PAYLOAD_INDEX"));
    client.target_detection(gateway, open, int_option(config, "lens", 0));
    return 0;
}

int demo_look_at(const Config& config, YooxClient& client) {
    const auto target = target_from(config);
    const auto errors = SafetyGuard::validate_target(target, false, 10000);
    if (!errors.empty()) throw ConfigError(errors.front());
    const auto payload = config.require("YOOX_PAYLOAD_INDEX");
    return payload_demo(config, client, "camera_look_at",
        {{"locked", config.has_flag("locked")}, {"latitude", target.latitude},
         {"longitude", target.longitude}, {"height", target.height}}, "LOOK AT");
}

KmzMission default_mission(const Config& config, bool interactive) {
    KmzMission mission;
    mission.name = config.option("name", "YOOX-C++-KMZ");
    mission.height = number_option(config, "height", "YOOX_TARGET_HEIGHT", 18);
    mission.speed = number_option(config, "speed", "YOOX_TARGET_MAX_SPEED", 5);
    mission.takeoff_security_height = number_option(config, "takeoff-security-height", "", 15);
    if (interactive) {
        int count = int_option(config, "points", 0);
        if (count < 2) { std::cout << "Number of waypoints (>=2): "; std::cin >> count; }
        for (int index = 0; index < count; ++index) {
            Waypoint point;
            std::cout << "Waypoint " << index << " longitude latitude ellipsoid_height: ";
            std::cin >> point.longitude >> point.latitude >> point.ellipsoid_height;
            mission.waypoints.push_back(point);
        }
    } else {
        mission.waypoints = {
            {117.72441031430895, 39.04183898359767, 11.620756},
            {117.72461532401013, 39.04167577449695, 11.621939},
            {117.72491771331835, 39.04172453823125, 11.623362},
            {117.72469604657908, 39.04194447220533, 11.622036},
            {117.72448334901588, 39.041896703798756, 11.62105}
        };
        mission.gimbal_pitch_actions[0] = number_option(config, "gimbal-pitch", "", -44);
    }
    return mission;
}

int demo_kmz(const Config& config, bool interactive) {
    auto mission = default_mission(config, interactive);
    const auto output = config.option("output").empty()
        ? std::filesystem::temp_directory_path() / "yoox-cpp-demo" /
              (interactive ? "interactive-mission.kmz" : "generated-mission.kmz")
        : std::filesystem::path(config.option("output"));
    if (config.has_flag("dry-run")) {
        std::cout << "KMZ dry-run: " << mission.waypoints.size() << " waypoints; no file written.\n";
        return 0;
    }
    Kmz::generate(output, mission);
    const auto validation = Kmz::validate(output);
    if (!validation.valid) throw std::runtime_error("generated KMZ failed local validation");
    std::cout << "[OK] KMZ generated and validated: " << output << '\n';
    return 0;
}

int demo_wayline(const Config& config, YooxClient& client) {
    const auto operation = action(config, "list");
    if (operation == "list") {
        web_login(client); print_json(client.list_waylines()); print_json(client.list_jobs()); return 0;
    }
    if (operation == "upload") {
        const auto file = std::filesystem::path(required_option(config, "file", "YOOX_WAYLINE_KMZ"));
        const auto validation = Kmz::validate(file);
        if (!validation.valid) throw ConfigError("KMZ failed local structure validation");
        print_plan("POST multipart", "/wayline/api/v1/workspaces/{workspace}/waylines/file/upload");
        if (!config.execute()) return 0;
        web_login(client);
        if (confirmation(config, "UPLOAD KMZ") != "UPLOAD KMZ") throw std::runtime_error("confirmation mismatch");
        client.upload_wayline(file); return 0;
    }
    const auto job_id = config.option("job-id");
    if (operation == "pause" || operation == "resume" || operation == "cancel") {
        if (job_id.empty()) throw ConfigError("--job-id is required");
        print_plan(operation == "cancel" ? "DELETE" : "PUT", "/wayline/api/v1/workspaces/{workspace}/jobs/{job}");
        if (!config.execute()) return 0;
        web_login(client);
        if (!authorize(client, config, "wayline_" + operation, "EXECUTE WAYLINE", false, false)) return 0;
        if (operation == "cancel") client.cancel_wayline_job(job_id);
        else client.change_wayline_job(job_id, operation == "pause" ? 0 : 1);
        std::cout << "[ACCEPTED] Refresh the target job before any retry.\n"; return 0;
    }
    if (operation == "create") {
        const auto file_id = required_option(config, "file-id");
        const auto gateway = config.require("YOOX_DOCK_SN");
        json body = {{"name", config.option("name", "cpp-demo-wayline")}, {"file_id", file_id},
                     {"dock_sn", gateway}, {"wayline_type", 0}, {"task_type", 0},
                     {"rth_altitude", 100}, {"out_of_control_action", 0},
                     {"min_battery_capacity", 50}, {"min_storage_capacity", 0},
                     {"wayline_precision_type", 0}, {"barrier_switch_state", 1},
                     {"takeoff_altitude", 100}, {"first_waypoint_speed", 10},
                     {"return_speed", 10}, {"media_upload_method", 0},
                     {"alternate_land_point", {{"is_configured", 0}}}};
        print_plan("POST", "/wayline/api/v1/workspaces/{workspace}/flight-tasks", body);
        if (!config.execute()) return 0;
        web_login(client);
        if (!authorize(client, config, "wayline_create", "EXECUTE WAYLINE", false, true)) return 0;
        client.create_wayline_job(body);
        std::cout << "[ACCEPTED] Recover the unique job name from list; do not recreate on timeout.\n";
        return 0;
    }
    throw ConfigError("action must be list, upload, create, pause, resume or cancel");
}

double distance_metres(const FlightTarget& first, const FlightTarget& second) {
    constexpr double radius = 6371000.0;
    const auto radians = [](double value) { return value * 3.14159265358979323846 / 180.0; };
    const double lat1 = radians(first.latitude), lat2 = radians(second.latitude);
    const double dlat = lat2 - lat1, dlon = radians(second.longitude - first.longitude);
    const double value = std::sin(dlat / 2) * std::sin(dlat / 2) +
        std::cos(lat1) * std::cos(lat2) * std::sin(dlon / 2) * std::sin(dlon / 2);
    return radius * 2 * std::atan2(std::sqrt(value), std::sqrt(1 - value));
}

int demo_two_drone(const Config& config, YooxClient& client) {
    const auto operation = action(config, "status");
    const std::string gateway1 = config.require("YOOX_DUAL_GATEWAY_SN_1");
    const std::string gateway2 = config.require("YOOX_DUAL_GATEWAY_SN_2");
    if (gateway1 == gateway2) throw ConfigError("dual gateways must differ");
    if (operation == "status") {
        web_login(client);
        show_point_state(client.point_flight_status(gateway1));
        show_point_state(client.point_flight_status(gateway2));
        return 0;
    }
    const bool takeoff = operation == "takeoff";
    if (!takeoff && operation != "flyto") throw ConfigError("action must be status, takeoff or flyto");
    const std::string prefix1 = takeoff ? "YOOX_DUAL_TAKEOFF_" : "YOOX_DUAL_FLYTO_";
    FlightTarget first{config.get_double(prefix1 + "LATITUDE_1", 0),
                       config.get_double(prefix1 + "LONGITUDE_1", 0),
                       config.get_double(takeoff ? "YOOX_DUAL_TAKEOFF_HEIGHT" : prefix1 + "HEIGHT_1", 0),
                       config.get_double("YOOX_DUAL_MAX_SPEED", 5)};
    FlightTarget second{config.get_double(prefix1 + "LATITUDE_2", 0),
                        config.get_double(prefix1 + "LONGITUDE_2", 0),
                        config.get_double(takeoff ? "YOOX_DUAL_TAKEOFF_HEIGHT" : prefix1 + "HEIGHT_2", 0),
                        config.get_double("YOOX_DUAL_MAX_SPEED", 5)};
    if (!SafetyGuard::validate_target(first, !takeoff).empty() ||
        !SafetyGuard::validate_target(second, !takeoff).empty()) throw ConfigError("invalid dual flight targets");
    if (distance_metres(first, second) < 10) throw ConfigError("dual targets must be at least 10 m apart");
    print_plan("POST x2", takeoff ? "/jobs/takeoff-to-point" : "/jobs/fly-to-point");
    if (!config.execute()) return 0;
    web_login(client);
    const auto expected1 = config.require("YOOX_DUAL_AIRCRAFT_SN_1");
    const auto expected2 = config.require("YOOX_DUAL_AIRCRAFT_SN_2");
    const auto topology = client.list_devices();
    const auto verify_identity = [&](const std::string& gateway, const std::string& expected) {
        const auto found = std::find_if(topology.begin(), topology.end(), [&](const DeviceInfo& value) {
            return value.gateway_sn == gateway;
        });
        if (found == topology.end() || found->aircraft_sn != expected ||
            !found->online.value_or(false) || !found->aircraft_online.value_or(false)) {
            throw std::runtime_error("dual-aircraft topology/online preflight failed for " +
                                     Logger::mask_identifier(gateway));
        }
    };
    verify_identity(gateway1, expected1);
    verify_identity(gateway2, expected2);
    const auto before1 = client.point_flight_status(gateway1);
    const auto before2 = client.point_flight_status(gateway2);
    if ((before1 && before1->active) || (before2 && before2->active))
        throw std::runtime_error("at least one aircraft has an active or unresolved point-flight task");
    const auto baseline1 = before1 ? before1->updated_at : 0;
    const auto baseline2 = before2 ? before2->updated_at : 0;
    const auto phrase = takeoff ? "TAKEOFF TWO" : "FLYTO TWO";
    const auto supplied = confirmation(config, phrase);
    if (!authorize(client, config, takeoff ? "takeoff_to_point" : "fly_to_point", phrase,
                   !takeoff, takeoff, first, gateway1, supplied) ||
        !authorize(client, config, takeoff ? "takeoff_to_point" : "fly_to_point", phrase,
                   !takeoff, takeoff, second, gateway2, supplied)) return 0;
    auto authority1 = std::async(std::launch::async, [&] { return client.acquire_flight_authority(gateway1); });
    auto authority2 = std::async(std::launch::async, [&] { return client.acquire_flight_authority(gateway2); });
    authority1.get(); authority2.get();
    auto command1 = std::async(std::launch::async, [&] {
        return takeoff ? client.takeoff_to_point(gateway1, first) : client.fly_to_point(gateway1, first);
    });
    auto command2 = std::async(std::launch::async, [&] {
        return takeoff ? client.takeoff_to_point(gateway2, second) : client.fly_to_point(gateway2, second);
    });
    bool unknown = false;
    try { command1.get(); } catch (const ApiError& error) { unknown |= error.ambiguous(); if (!error.ambiguous()) throw; }
    try { command2.get(); } catch (const ApiError& error) { unknown |= error.ambiguous(); if (!error.ambiguous()) throw; }
    if (unknown) { std::cerr << "At least one result is unknown; never repeat either action blindly.\n"; return 2; }
    const auto kind = takeoff ? "takeoff" : "flyto";
    auto recover1 = std::async(std::launch::async, [&] {
        return wait_for_task(client, gateway1, kind, baseline1, 15);
    });
    auto recover2 = std::async(std::launch::async, [&] {
        return wait_for_task(client, gateway2, kind, baseline2, 15);
    });
    const auto state1 = recover1.get();
    const auto state2 = recover2.get();
    show_point_state(state1);
    show_point_state(state2);
    if (!state1 || !state2) {
        std::cerr << "At least one task identity is unknown; the two actions are not transactional.\n";
        return 2;
    }
    const int wait_seconds = int_option(
        config, "wait", config.get_int("YOOX_POINT_FLIGHT_WAIT_SECONDS", 300));
    auto terminal1 = std::async(std::launch::async, [&] {
        return wait_for_terminal_state(client, gateway1, *state1, wait_seconds);
    });
    auto terminal2 = std::async(std::launch::async, [&] {
        return wait_for_terminal_state(client, gateway2, *state2, wait_seconds);
    });
    const auto final1 = terminal1.get();
    const auto final2 = terminal2.get();
    if (!final1 || !final2) return 2;
    const auto success = [](const PointFlightState& state) {
        return state.result.value_or(0) == 0 && state.status != "wayline_failed" &&
               state.status != "command_failed" && state.status != "wayline_cancel";
    };
    return success(*final1) && success(*final2) ? 0 : 1;
}

struct DrcRuntime {
    std::unique_ptr<MqttClient> mqtt;
    DrcInterlock interlock;
    CorrelationTracker control_acks;
    std::string down_topic;
    std::string up_topic;
    std::atomic<std::int64_t> sequence{0};
    std::atomic<std::int64_t> first_control_sent_ms{0};
    std::atomic<std::int64_t> first_control_ack_ms{0};
    std::atomic<std::int64_t> last_control_ack_ms{0};
};

std::vector<std::string> json_strings(const json& value) {
    std::vector<std::string> result;
    if (value.is_string()) result.push_back(value.get<std::string>());
    if (value.is_array()) for (const auto& item : value) if (item.is_string()) result.push_back(item.get<std::string>());
    return result;
}

std::optional<int> reply_result(const json& payload) {
    for (const json* node : {&payload, payload.contains("data") ? &payload["data"] : nullptr}) {
        if (node && node->is_object() && node->contains("result") && (*node)["result"].is_number_integer())
            return (*node)["result"].get<int>();
    }
    return std::nullopt;
}

std::optional<std::int64_t> reply_seq(const json& payload) {
    const json* node = &payload;
    if (node->contains("data") && (*node)["data"].is_object()) node = &(*node)["data"];
    if (node->contains("output") && (*node)["output"].is_object()) node = &(*node)["output"];
    if (node->contains("seq") && (*node)["seq"].is_number_integer()) return (*node)["seq"].get<std::int64_t>();
    return std::nullopt;
}

std::int64_t steady_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

void publish_drc(DrcRuntime& runtime, const std::string& method, json data,
                 bool probe = false, int probe_step = -1, bool track_control = false) {
    auto message = make_drc_message(method, std::move(data));
    const CorrelatedRequest correlated{runtime.mqtt->generation(), message.tid, message.bid,
        message.data.contains("seq") ? std::optional<std::int64_t>(message.data.value("seq", std::int64_t{-1}))
                                     : std::nullopt,
        std::chrono::steady_clock::now()};
    if (probe) runtime.interlock.register_probe(correlated, probe_step);
    if (track_control) {
        runtime.control_acks.remember(correlated);
        std::int64_t unset = 0;
        runtime.first_control_sent_ms.compare_exchange_strong(unset, steady_ms());
    }
    if (!runtime.mqtt->publish(runtime.down_topic, to_json(message).dump()))
        throw std::runtime_error("DRC MQTT publish failed");
}

int run_drc(const Config& config, YooxClient& client, const std::string& method,
            double x, double y, double h, double w, int seconds,
            bool monitor_descent = false) {
    const auto gateway = config.require("YOOX_DOCK_SN");
    print_plan("POST", "/control/api/v1/workspaces/{workspace}/drc/connect");
    print_plan("POST", "/control/api/v1/workspaces/{workspace}/drc/enter");
    std::cout << "[DRY-RUN] MQTT CONNACK -> SUBACK -> heartbeat -> two zero probes -> optional control -> zero -> exit\n";
    if (!config.execute()) return 0;
    if (!authorize(client, config, "drc", method == "drone_control" ? "ENTER DRC" : "EMERGENCY STOP",
                   method == "drone_control", false)) return 0;

    const auto broker = client.drc_connect();
    const auto client_id = broker.value("client_id", std::string{});
    if (client_id.empty()) throw std::runtime_error("DRC broker response has no client_id");
    bool enter_attempted = false;
    bool drc_control_failure = false;
    DrcRuntime runtime;
    std::unique_ptr<WebSocketClient> osd_monitor;
    std::atomic<std::int64_t> last_osd_ms{0};
    std::atomic<int> mode_code{-1};
    std::atomic<double> altitude{std::numeric_limits<double>::quiet_NaN()};
    std::atomic<double> vertical_speed{std::numeric_limits<double>::quiet_NaN()};
    if (monitor_descent) {
        const auto expected_drone = config.require("YOOX_DRONE_SN");
        osd_monitor = std::make_unique<WebSocketClient>();
        osd_monitor->set_message_handler([&](const std::string& raw) {
            try {
                const auto event = parse_websocket_event(raw);
                if (event.biz_code != "device_osd" || !event.data.is_object()) return;
                const auto host = event.data.value("host", json::object());
                const auto sn = event.data.value("sn", host.value("sn", std::string{}));
                if (!sn.empty() && sn != expected_drone) return;
                if (host.contains("mode_code") && host["mode_code"].is_number_integer())
                    mode_code.store(host["mode_code"].get<int>());
                if (host.contains("height") && host["height"].is_number())
                    altitude.store(host["height"].get<double>());
                else if (host.contains("elevation") && host["elevation"].is_number())
                    altitude.store(host["elevation"].get<double>());
                if (host.contains("vertical_speed") && host["vertical_speed"].is_number())
                    vertical_speed.store(host["vertical_speed"].get<double>());
                last_osd_ms.store(steady_ms());
            } catch (...) {}
        });
        const auto ws_base = config.websocket_url();
        const auto ws_url = ws_base + (ws_base.find('?') == std::string::npos ? "?" : "&") +
                            "x-auth-token=" + url_encode(client.token());
        if (!osd_monitor->connect(ws_url)) throw std::runtime_error("landing OSD WebSocket timeout");
        const auto osd_deadline = std::chrono::steady_clock::now() + std::chrono::seconds(12);
        while (last_osd_ms.load() == 0 && std::chrono::steady_clock::now() < osd_deadline)
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        if (last_osd_ms.load() == 0) throw std::runtime_error("no target-aircraft OSD for landing safety gate");
        if (mode_code.load() == 0) throw std::runtime_error("aircraft is already in ground IDLE mode");
    }
    try {
        enter_attempted = true;
        const auto acl = client.drc_enter(client_id, gateway);
        const auto pubs = json_strings(acl.value("pub", json::array()));
        const auto subs = json_strings(acl.value("sub", json::array()));
        runtime.down_topic = "thing/product/" + gateway + "/drc/down";
        if (std::find(pubs.begin(), pubs.end(), runtime.down_topic) == pubs.end()) {
            const auto found = std::find_if(pubs.begin(), pubs.end(), [](const std::string& value) {
                return value.size() >= 9 && value.rfind("/drc/down") == value.size() - 9;
            });
            if (found == pubs.end()) throw std::runtime_error("DRC ACL has no drc/down topic");
            runtime.down_topic = *found;
        }
        runtime.up_topic = runtime.down_topic.substr(0, runtime.down_topic.size() - 5) + "up";
        const auto address = broker.value("address", std::string{});
        const bool drc_tls = broker.value("enable_tls", broker.value("enableTls", false)) ||
            address.rfind("ssl://", 0) == 0 || address.rfind("mqtts://", 0) == 0 ||
            address.rfind("wss://", 0) == 0;
        MqttOptions options{address, client_id, broker.value("username", std::string{}),
                            broker.value("password", std::string{}),
                            drc_tls, config.get("YOOX_MQTT_CA_FILE"), 30};
        runtime.mqtt = std::make_unique<MqttClient>(std::move(options));
        runtime.mqtt->set_generation_handler([&](std::uint64_t generation) {
            runtime.interlock.reconnect(generation);
            runtime.control_acks.begin_generation(generation);
            runtime.first_control_sent_ms.store(0);
            runtime.first_control_ack_ms.store(0);
            runtime.last_control_ack_ms.store(0);
        });
        runtime.mqtt->set_message_handler([&](const std::string& topic, const std::string& raw,
                                               std::uint64_t generation) {
            if (topic != runtime.up_topic) return;
            try {
                const auto payload = json::parse(raw);
                if (payload.value("method", std::string{}) != "drone_control") return;
                CorrelatedReply reply{generation, payload.value("tid", std::string{}),
                                      payload.value("bid", std::string{}), reply_seq(payload),
                                      reply_result(payload), std::chrono::steady_clock::now()};
                if (!runtime.interlock.controls_ready()) {
                    if (runtime.interlock.accept_probe(reply))
                        std::cout << "[OK] DRC zero-probe interlock unlocked\n";
                } else if (runtime.control_acks.accept(reply, std::chrono::milliseconds(2000))) {
                    const auto acknowledged = steady_ms();
                    runtime.last_control_ack_ms.store(acknowledged);
                    std::int64_t unset = 0;
                    runtime.first_control_ack_ms.compare_exchange_strong(unset, acknowledged);
                }
            } catch (...) {}
        });
        if (!runtime.mqtt->connect()) throw std::runtime_error("DRC CONNACK timeout");
        std::vector<std::pair<std::string, int>> subscriptions;
        for (const auto& topic : subs) subscriptions.emplace_back(topic, 0);
        if (!runtime.mqtt->subscribe(subscriptions)) throw std::runtime_error("DRC SUBACK timeout");

        publish_drc(runtime, "heart_beat", {{"seq", 1}, {"timestamp",
            std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count()}});
        for (int step = 0; step < 2; ++step) {
            const auto seq = runtime.sequence.fetch_add(1);
            publish_drc(runtime, "drone_control", {{"seq", seq}, {"x", 0}, {"y", 0},
                {"h", 0}, {"w", 0}, {"freq", 10}, {"delay_time", 300}}, true, step);
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        const auto ready_deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        while (!runtime.interlock.controls_ready() && std::chrono::steady_clock::now() < ready_deadline)
            std::this_thread::sleep_for(std::chrono::milliseconds(50));
        if (!runtime.interlock.controls_ready()) throw std::runtime_error("DRC zero probes were not strictly acknowledged");

        if (method == "drone_emergency_stop") {
            publish_drc(runtime, method, json::object());
        } else {
            const auto end = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
            int heartbeat_seq = 2;
            auto next_heartbeat = std::chrono::steady_clock::now();
            const double initial_altitude = altitude.load();
            bool movement_observed = false;
            while (!interrupted.load() && std::chrono::steady_clock::now() < end) {
                if (!runtime.interlock.controls_ready()) break;
                if (monitor_descent) {
                    if (steady_ms() - last_osd_ms.load() > 3000) {
                        Logger::log(LogLevel::error, "landing OSD stale for more than 3 seconds");
                        drc_control_failure = true;
                        break;
                    }
                    if (mode_code.load() == 0) {
                        std::cout << "[OK] Aircraft entered mode_code=0; stopping descent.\n";
                        break;
                    }
                }
                const auto seq = runtime.sequence.fetch_add(1);
                publish_drc(runtime, "drone_control", {{"seq", seq}, {"x", x}, {"y", y},
                    {"h", h}, {"w", w}, {"freq", 10}, {"delay_time", 300}}, false, -1, true);
                if (std::chrono::steady_clock::now() >= next_heartbeat) {
                    publish_drc(runtime, "heart_beat", {{"seq", heartbeat_seq++}, {"timestamp",
                        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count()}});
                    next_heartbeat = std::chrono::steady_clock::now() + std::chrono::seconds(1);
                }
                const auto first_sent = runtime.first_control_sent_ms.load();
                const auto last_ack = runtime.last_control_ack_ms.load();
                if (first_sent > 0 && steady_ms() - std::max(first_sent, last_ack) > 2000) {
                    runtime.interlock.lock();
                    Logger::log(LogLevel::error,
                                "DRC control ACK timeout; locking controls and sending zero vector");
                    drc_control_failure = true;
                    break;
                }
                if (monitor_descent && last_ack > 0) {
                    const double current_altitude = altitude.load();
                    const double current_vertical_speed = vertical_speed.load();
                    movement_observed = movement_observed ||
                        (std::isfinite(current_vertical_speed) && current_vertical_speed < -0.15) ||
                        (std::isfinite(initial_altitude) && std::isfinite(current_altitude) &&
                         initial_altitude - current_altitude >= 0.15);
                    const auto first_ack = runtime.first_control_ack_ms.load();
                    if (!movement_observed && first_ack > 0 && steady_ms() - first_ack > 3000) {
                        Logger::log(LogLevel::error,
                                    "device ACKed descent but OSD showed no descent within 3 seconds");
                        drc_control_failure = true;
                        break;
                    }
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
            if (monitor_descent && mode_code.load() != 0 &&
                (interrupted.load() || std::chrono::steady_clock::now() >= end)) {
                drc_control_failure = true;
                Logger::log(LogLevel::warning,
                            interrupted.load() ? "landing interrupted by operator" : "landing reached maximum duration");
            }
            if (drc_control_failure) {
                runtime.interlock.lock();
            }
        }
        const auto final_seq = runtime.sequence.fetch_add(1);
        publish_drc(runtime, "drone_control", {{"seq", final_seq}, {"x", 0}, {"y", 0},
            {"h", 0}, {"w", 0}, {"freq", 10}, {"delay_time", 300}});
        std::this_thread::sleep_for(std::chrono::milliseconds(150));
        runtime.mqtt->disconnect();
        client.drc_exit(client_id, gateway);
        enter_attempted = false;
        if (osd_monitor) osd_monitor->close();
        return drc_control_failure ? 2 : 0;
    } catch (...) {
        if (runtime.mqtt) {
            try {
                const auto seq = runtime.sequence.fetch_add(1);
                publish_drc(runtime, "drone_control", {{"seq", seq}, {"x", 0}, {"y", 0},
                    {"h", 0}, {"w", 0}, {"freq", 10}, {"delay_time", 300}});
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                runtime.mqtt->disconnect();
            } catch (...) {}
        }
        if (enter_attempted) {
            try { client.drc_exit(client_id, gateway); } catch (...) {}
        }
        if (osd_monitor) osd_monitor->close();
        throw;
    }
}

int demo_drc(const Config& config, YooxClient& client, bool landing = false) {
    if (config.execute()) web_login(client);
    if (landing) {
        const double speed = number_option(config, "speed", "", 1.0);
        if (speed < 0.3 || speed > 4) throw ConfigError("landing speed must be 0.3-4.0 m/s");
        return run_drc(config, client, "drone_control", 0, 0, -speed, 0,
                       int_option(config, "max-seconds", 180), true);
    }
    const auto operation = action(config, "probe");
    if (operation == "probe") return run_drc(config, client, "drone_control", 0, 0, 0, 0, 0);
    if (operation == "stop") return run_drc(config, client, "drone_emergency_stop", 0, 0, 0, 0, 0);
    if (operation != "control") throw ConfigError("action must be probe, control or stop");
    return run_drc(config, client, "drone_control", number_option(config, "x", "", 0),
                   number_option(config, "y", "", 0), number_option(config, "h", "", 0),
                   number_option(config, "w", "", 0), int_option(config, "seconds", 5));
}

int dispatch(int number, const Config& config, YooxClient& client) {
    switch (number) {
        case 1: return demo_login(config, client);
        case 2: return demo_devices(config, client);
        case 3: return demo_websocket(config, client);
        case 4: return demo_mqtt(config);
        case 5: return payload_demo(config, client, "camera_focal_length_set",
                    [&] {
                        const auto camera_type = config.option("camera-type", "zoom");
                        const auto zoom = number_option(config, "zoom", "", 5);
                        if (camera_type != "zoom" && camera_type != "ir")
                            throw ConfigError("camera-type must be zoom or ir");
                        if (zoom < 1 || zoom > (camera_type == "zoom" ? 160 : 16))
                            throw ConfigError("zoom factor is outside the supported range");
                        return json{{"zoom_factor", zoom}, {"camera_type", camera_type}};
                    }());
        case 6: {
            const auto op = action(config, "stop");
            if (op == "reset") return payload_demo(config, client, "gimbal_reset",
                {{"reset_mode", int_option(config, "mode", 0)}});
            const auto pitch = number_option(config, "pitch", "", 0);
            const auto yaw = number_option(config, "yaw", "", 0);
            if (std::abs(pitch) > 1 || std::abs(yaw) > 1)
                throw ConfigError("pitch/yaw speed must be within -1..1");
            return payload_demo(config, client, "camera_screen_drag",
                {{"pitch_speed", pitch}, {"yaw_speed", yaw},
                 {"locked", config.has_flag("locked")}});
        }
        case 7: {
            const auto op = action(config, "photo");
            const std::map<std::string, std::string> commands = {
                {"photo", "camera_photo_take"}, {"rec_start", "camera_recording_start"},
                {"rec_stop", "camera_recording_stop"}};
            const auto found = commands.find(op);
            if (found == commands.end()) throw ConfigError("action must be photo, rec_start or rec_stop");
            if (op == "rec_stop") return payload_demo(config, client, found->second, json::object());
            const auto gateway = config.require("YOOX_DOCK_SN");
            const auto payload_index = config.require("YOOX_PAYLOAD_INDEX");
            const int camera_mode = op == "photo" ? 0 : 1;
            print_plan("POST", "/control/api/v1/devices/{gateway}/payload/commands",
                       {{"cmd", "camera_mode_switch"},
                        {"data", {{"payload_index", payload_index}, {"camera_mode", camera_mode}}}});
            if (!config.execute()) {
                print_plan("POST", "/control/api/v1/devices/{gateway}/payload/commands",
                           {{"cmd", found->second}, {"data", {{"payload_index", payload_index}}}});
                return 0;
            }
            web_login(client);
            if (!authorize(client, config, "camera_" + op, "EXECUTE PAYLOAD", false, false)) return 0;
            client.acquire_payload_authority(gateway, payload_index);
            client.payload_command(gateway, "camera_mode_switch",
                                   {{"payload_index", payload_index}, {"camera_mode", camera_mode}});
            client.payload_command(gateway, found->second, {{"payload_index", payload_index}});
            std::cout << "[ACCEPTED] Camera requests accepted in sequence; verify OSD.\n";
            return 0;
        }
        case 8: return demo_flyto(config, client);
        case 9: return demo_remote_job(config, client, "return_home");
        case 10: return demo_takeoff(config, client);
        case 11: return demo_live(config, client);
        case 12: return demo_drc(config, client);
        case 13: {
            const auto command = config.option("command", "camera_screen_drag");
            json data = json::object();
            if (command == "camera_screen_drag") data = {{"pitch_speed", number_option(config, "pitch", "", 0)},
                                                          {"yaw_speed", number_option(config, "yaw", "", 0)},
                                                          {"locked", config.has_flag("locked")}};
            else if (command == "camera_focal_length_drag") data = {{"zoom_type", int_option(config, "zoom-type", 0)},
                                                                      {"camera_type", config.option("camera-type", "zoom")}};
            else if (command == "photo_storage_set") data = {{"photo_storage_settings", {"zoom", "wide"}}};
            else if (command == "video_storage_set") data = {{"video_storage_settings", {"zoom", "wide"}}};
            else throw ConfigError("unsupported advanced payload --command");
            return payload_demo(config, client, command, data);
        }
        case 14: return demo_target_detection(config, client);
        case 15: {
            const auto op = action(config, "return_home");
            if (op == "emergency_stop") {
                if (config.execute()) web_login(client);
                return run_drc(config, client, "drone_emergency_stop", 0, 0, 0, 0, 0);
            }
            return demo_remote_job(config, client, op);
        }
        case 16: return demo_look_at(config, client);
        case 17: return demo_wayline(config, client);
        case 18: return demo_drc(config, client, true);
        case 19: {
            const auto op = action(config, "watch");
            if (op == "watch") {
                const auto drone = config.get("YOOX_DRONE_SN", "+");
                run_mqtt_watch(config, {{"thing/product/" + drone + "/osd", 0},
                                        {"thing/product/" + config.require("YOOX_DOCK_SN") + "/events", 0}},
                    int_option(config, "seconds", 30), [](const std::string& topic, const json& payload) {
                        const auto data = payload.value("data", json::object());
                        std::cout << "[RTH WATCH] " << mask_topic(topic) << " method="
                                  << payload.value("method", std::string{}) << " mode="
                                  << data.value("mode_code", -1) << '\n';
                    });
                return 0;
            }
            return demo_remote_job(config, client, op == "cancel" ? "return_home_cancel" : "return_home");
        }
        case 20: return demo_kmz(config, false);
        case 21: return demo_kmz(config, true);
        case 22: return demo_two_drone(config, client);
        default: throw ConfigError("unknown demo number");
    }
}

}  // namespace

int run_demo(int demo_number, const std::string& demo_name, int argc, char** argv) {
#ifdef _WIN32
    SetConsoleOutputCP(CP_UTF8);
    SetConsoleCP(CP_UTF8);
#endif
    std::signal(SIGINT, on_signal);
    try {
        Config config = Config::load(argc, argv);
        Logger::set_debug(config.has_flag("debug"));
        auto transport = http(config);
        YooxClient client(config, transport);
        std::cout << "YOOX Windows C++ Demo: " << demo_name
                  << " | mode=" << (config.execute() ? "EXECUTE" : "DRY-RUN") << '\n';
        if (!config.env_file().empty()) std::cout << "env file loaded (values hidden)\n";
        return dispatch(demo_number, config, client);
    } catch (const ApiError& error) {
        Logger::log(LogLevel::error, error.what());
        if (error.ambiguous()) {
            std::cerr << "请求结果未知。命令可能已经被设备接收，禁止立即重复发送，请先查询任务或设备状态。\n";
            return 2;
        }
        return 1;
    } catch (const std::exception& error) {
        Logger::log(LogLevel::error, error.what());
        return 1;
    }
}

}  // namespace yoox

