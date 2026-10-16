#include "yoox/config.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <regex>
#include <sstream>

namespace yoox {
namespace {

std::string trim(std::string value) {
    const auto not_space = [](unsigned char c) { return !std::isspace(c); };
    value.erase(value.begin(), std::find_if(value.begin(), value.end(), not_space));
    value.erase(std::find_if(value.rbegin(), value.rend(), not_space).base(), value.end());
    return value;
}

std::string lower(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

std::string strip_quotes(std::string value) {
    if (value.size() >= 2 && ((value.front() == '"' && value.back() == '"') ||
                            (value.front() == '\'' && value.back() == '\''))) {
        return value.substr(1, value.size() - 2);
    }
    return value;
}

void overlay(std::unordered_map<std::string, std::string>& target,
             const std::unordered_map<std::string, std::string>& source) {
    for (const auto& [key, value] : source) {
        target[key] = value;
    }
}

std::string take_value(int& index, int argc, char** argv, const std::string& name) {
    if (index + 1 >= argc) {
        throw ConfigError(name + " requires a value");
    }
    return argv[++index];
}

std::string validated_url(std::string value, const std::regex& pattern, const std::string& key) {
    while (!value.empty() && value.back() == '/') value.pop_back();
    if (!std::regex_match(value, pattern)) throw ConfigError(key + " is not a valid URL");
    return value;
}

}  // namespace

const std::vector<std::string>& Config::known_environment_keys() {
    static const std::vector<std::string> keys = {
        "YOOX_SERVER_IP", "YOOX_SERVER_PORT", "YOOX_BASE_URL",
        "YOOX_PILOT_PORT", "YOOX_PILOT_BASE_URL", "YOOX_WS_URL",
        "YOOX_WEB_USERNAME", "YOOX_WEB_PASSWORD", "YOOX_WEB_FLAG",
        "YOOX_PILOT_USERNAME", "YOOX_PILOT_PASSWORD", "YOOX_PILOT_FLAG",
        "YOOX_WORKSPACE_ID", "YOOX_DOCK_SN", "YOOX_DRONE_SN", "YOOX_PAYLOAD_INDEX",
        "YOOX_MQTT_HOST", "YOOX_MQTT_PORT", "YOOX_MQTT_WS_PORT",
        "YOOX_MQTT_USERNAME", "YOOX_MQTT_PASSWORD", "YOOX_MQTT_TLS",
        "YOOX_MQTT_CA_FILE", "YOOX_RTSP_HOST", "YOOX_RTSP_PORT",
        "YOOX_TARGET_LATITUDE", "YOOX_TARGET_LONGITUDE", "YOOX_TARGET_HEIGHT",
        "YOOX_TARGET_MAX_SPEED", "YOOX_TAKEOFF_TARGET_LATITUDE",
        "YOOX_TAKEOFF_TARGET_LONGITUDE", "YOOX_WAYLINE_KMZ",
        "YOOX_HTTP_CONNECT_TIMEOUT", "YOOX_HTTP_CONTROL_TIMEOUT",
        "YOOX_POINT_FLIGHT_WAIT_SECONDS", "YOOX_TLS_VERIFY", "YOOX_CA_BUNDLE",
        "YOOX_DUAL_GATEWAY_SN_1", "YOOX_DUAL_AIRCRAFT_SN_1",
        "YOOX_DUAL_GATEWAY_SN_2", "YOOX_DUAL_AIRCRAFT_SN_2",
        "YOOX_DUAL_TAKEOFF_LATITUDE_1", "YOOX_DUAL_TAKEOFF_LONGITUDE_1",
        "YOOX_DUAL_TAKEOFF_LATITUDE_2", "YOOX_DUAL_TAKEOFF_LONGITUDE_2",
        "YOOX_DUAL_TAKEOFF_HEIGHT", "YOOX_DUAL_FLYTO_LATITUDE_1",
        "YOOX_DUAL_FLYTO_LONGITUDE_1", "YOOX_DUAL_FLYTO_HEIGHT_1",
        "YOOX_DUAL_FLYTO_LATITUDE_2", "YOOX_DUAL_FLYTO_LONGITUDE_2",
        "YOOX_DUAL_FLYTO_HEIGHT_2", "YOOX_DUAL_MAX_SPEED"
    };
    return keys;
}

Config Config::from_layers(
    const std::unordered_map<std::string, std::string>& file_values,
    const std::unordered_map<std::string, std::string>& environment_values,
    const std::unordered_map<std::string, std::string>& cli_values) {
    Config config;
    config.values_ = {
        {"YOOX_SERVER_IP", "127.0.0.1"}, {"YOOX_SERVER_PORT", "8080"},
        {"YOOX_PILOT_PORT", "9000"}, {"YOOX_WEB_USERNAME", "admin"},
        {"YOOX_WEB_FLAG", "1"}, {"YOOX_PILOT_USERNAME", "pilot"},
        {"YOOX_PILOT_FLAG", "2"}, {"YOOX_MQTT_PORT", "1883"},
        {"YOOX_MQTT_WS_PORT", "9001"}, {"YOOX_RTSP_PORT", "8554"},
        {"YOOX_TARGET_MAX_SPEED", "5"}, {"YOOX_HTTP_CONNECT_TIMEOUT", "5"},
        {"YOOX_HTTP_CONTROL_TIMEOUT", "20"},
        {"YOOX_POINT_FLIGHT_WAIT_SECONDS", "300"}, {"YOOX_TLS_VERIFY", "true"},
        {"YOOX_MQTT_TLS", "false"}, {"YOOX_DUAL_MAX_SPEED", "5"}
    };
    overlay(config.values_, file_values);
    overlay(config.values_, environment_values);
    overlay(config.values_, cli_values);
    return config;
}

std::unordered_map<std::string, std::string> Config::parse_env_file(
    const std::filesystem::path& path) {
    std::ifstream input(path);
    if (!input) {
        throw ConfigError("cannot open env file: " + path.string());
    }
    std::unordered_map<std::string, std::string> result;
    std::string line;
    std::size_t line_number = 0;
    while (std::getline(input, line)) {
        ++line_number;
        line = trim(line);
        if (line.empty() || line.front() == '#') {
            continue;
        }
        if (line.rfind("export ", 0) == 0) {
            line = trim(line.substr(7));
        }
        const auto separator = line.find('=');
        if (separator == std::string::npos) {
            throw ConfigError("invalid env line " + std::to_string(line_number));
        }
        auto key = trim(line.substr(0, separator));
        auto value = strip_quotes(trim(line.substr(separator + 1)));
        if (key.empty()) {
            throw ConfigError("empty env key on line " + std::to_string(line_number));
        }
        result[key] = value;
    }
    return result;
}

Config Config::load(int argc, char** argv) {
    std::filesystem::path explicit_env;
    std::unordered_map<std::string, std::string> cli_values;
    std::unordered_map<std::string, std::string> options;
    std::vector<std::string> positional;
    bool execute = false;

    const std::unordered_map<std::string, std::string> aliases = {
        {"base-url", "YOOX_BASE_URL"}, {"pilot-base-url", "YOOX_PILOT_BASE_URL"},
        {"ws-url", "YOOX_WS_URL"}, {"workspace", "YOOX_WORKSPACE_ID"},
        {"gateway", "YOOX_DOCK_SN"}, {"drone", "YOOX_DRONE_SN"},
        {"payload", "YOOX_PAYLOAD_INDEX"}
    };

    for (int i = 1; i < argc; ++i) {
        std::string arg = argv[i];
        if (arg == "--execute") {
            execute = true;
        } else if (arg == "--dry-run") {
            execute = false;
            options["dry-run"] = "true";
        } else if (arg == "--env-file") {
            explicit_env = take_value(i, argc, argv, arg);
        } else if (arg == "--set") {
            const auto assignment = take_value(i, argc, argv, arg);
            const auto separator = assignment.find('=');
            if (separator == std::string::npos) {
                throw ConfigError("--set expects NAME=VALUE");
            }
            cli_values[assignment.substr(0, separator)] = assignment.substr(separator + 1);
        } else if (arg.rfind("--", 0) == 0) {
            const auto name = arg.substr(2);
            std::string value = "true";
            if (i + 1 < argc && std::string(argv[i + 1]).rfind("--", 0) != 0) {
                value = argv[++i];
            }
            options[name] = value;
            if (const auto found = aliases.find(name); found != aliases.end()) {
                cli_values[found->second] = value;
            }
        } else {
            positional.push_back(arg);
        }
    }

    std::filesystem::path env_file = explicit_env;
    if (env_file.empty()) {
        const std::vector<std::filesystem::path> candidates = {
            std::filesystem::current_path() / "docs/cpp-demo/.env",
            std::filesystem::current_path() / "docs/python-demo/.env",
            std::filesystem::current_path() / ".env",
            std::filesystem::current_path() / "../python-demo/.env"
        };
        for (const auto& candidate : candidates) {
            if (std::filesystem::is_regular_file(candidate)) {
                env_file = std::filesystem::weakly_canonical(candidate);
                break;
            }
        }
    }

    std::unordered_map<std::string, std::string> file_values;
    if (!env_file.empty()) {
        file_values = parse_env_file(env_file);
    }
    std::unordered_map<std::string, std::string> environment_values;
    for (const auto& key : known_environment_keys()) {
        if (const char* value = std::getenv(key.c_str())) {
            environment_values[key] = value;
        }
    }
    Config config = from_layers(file_values, environment_values, cli_values);
    config.env_file_ = env_file;
    config.options_ = std::move(options);
    config.positional_ = std::move(positional);
    config.execute_ = execute;
    return config;
}

std::string Config::get(const std::string& key, const std::string& fallback) const {
    const auto found = values_.find(key);
    return found == values_.end() ? fallback : found->second;
}

bool Config::is_placeholder(const std::string& value) const {
    const auto normalised = lower(trim(value));
    return normalised.empty() || normalised.rfind("your_", 0) == 0 ||
           normalised == "change_me" || normalised == "changeme" ||
           normalised == "replace_me";
}

std::string Config::require(const std::string& key) const {
    const auto value = get(key);
    if (is_placeholder(value)) {
        throw ConfigError("missing configuration: " + key);
    }
    return value;
}

int Config::get_int(const std::string& key, int fallback) const {
    const auto value = get(key);
    if (value.empty()) return fallback;
    try { return std::stoi(value); }
    catch (...) { throw ConfigError(key + " must be an integer"); }
}

double Config::get_double(const std::string& key, double fallback) const {
    const auto value = get(key);
    if (value.empty()) return fallback;
    try { return std::stod(value); }
    catch (...) { throw ConfigError(key + " must be a number"); }
}

std::optional<double> Config::get_optional_double(const std::string& key) const {
    const auto value = get(key);
    if (value.empty()) return std::nullopt;
    return get_double(key, 0);
}

bool Config::get_bool(const std::string& key, bool fallback) const {
    const auto value = lower(trim(get(key)));
    if (value.empty()) return fallback;
    if (value == "true" || value == "1" || value == "yes" || value == "on") return true;
    if (value == "false" || value == "0" || value == "no" || value == "off") return false;
    throw ConfigError(key + " must be a boolean");
}

bool Config::has_flag(const std::string& name) const {
    const auto found = options_.find(name);
    return found != options_.end() && lower(found->second) != "false";
}

std::string Config::option(const std::string& name, const std::string& fallback) const {
    const auto found = options_.find(name);
    return found == options_.end() ? fallback : found->second;
}

std::string Config::base_url() const {
    static const std::regex http_url(R"(^https?://[^\s/]+(?::[0-9]+)?(?:/[^\s]*)?$)", std::regex::icase);
    const auto explicit_value = get("YOOX_BASE_URL");
    const auto value = !explicit_value.empty() ? explicit_value :
        "http://" + get("YOOX_SERVER_IP", "127.0.0.1") + ":" +
        std::to_string(get_int("YOOX_SERVER_PORT", 8080));
    return validated_url(value, http_url, "YOOX_BASE_URL");
}

std::string Config::pilot_base_url() const {
    static const std::regex http_url(R"(^https?://[^\s/]+(?::[0-9]+)?(?:/[^\s]*)?$)", std::regex::icase);
    const auto explicit_value = get("YOOX_PILOT_BASE_URL");
    const auto value = !explicit_value.empty() ? explicit_value :
        "http://" + get("YOOX_SERVER_IP", "127.0.0.1") + ":" +
        std::to_string(get_int("YOOX_PILOT_PORT", 9000));
    return validated_url(value, http_url, "YOOX_PILOT_BASE_URL");
}

std::string Config::websocket_url() const {
    static const std::regex ws_url(R"(^wss?://[^\s/]+(?::[0-9]+)?/[^\s]*$)", std::regex::icase);
    const auto explicit_value = get("YOOX_WS_URL");
    const auto value = !explicit_value.empty() ? explicit_value :
        "ws://" + get("YOOX_SERVER_IP", "127.0.0.1") + ":" +
        std::to_string(get_int("YOOX_PILOT_PORT", 9000)) + "/api/v1/ws";
    return validated_url(value, ws_url, "YOOX_WS_URL");
}

std::string join_url(const std::string& base, const std::string& path) {
    if (base.empty()) return path;
    if (path.empty()) return base;
    if (base.back() == '/' && path.front() == '/') return base.substr(0, base.size() - 1) + path;
    if (base.back() != '/' && path.front() != '/') return base + "/" + path;
    return base + path;
}

}  // namespace yoox

