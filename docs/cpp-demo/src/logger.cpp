#include "yoox/logger.h"

#include <algorithm>
#include <iostream>
#include <mutex>
#include <regex>

namespace yoox {
namespace {
bool debug_enabled = false;
std::mutex output_mutex;
}

void Logger::set_debug(bool enabled) { debug_enabled = enabled; }

void Logger::log(LogLevel level, std::string_view message) {
    if (level == LogLevel::debug && !debug_enabled) return;
    const char* prefix = "[INFO]";
    if (level == LogLevel::debug) prefix = "[DEBUG]";
    if (level == LogLevel::warning) prefix = "[WARN]";
    if (level == LogLevel::error) prefix = "[ERROR]";
    std::lock_guard<std::mutex> lock(output_mutex);
    std::cerr << prefix << ' ' << redact(std::string(message)) << '\n';
}

std::string Logger::redact(std::string text) {
    static const std::regex json_secret(
        R"REGEX(("(?:password|access_token|refresh_token|token|authorization|mqtt_password)"\s*:\s*")[^"]*("))REGEX",
        std::regex::icase);
    static const std::regex query_secret(
        R"(((?:x-auth-token|access_token|token|password)=)[^&\s]+)", std::regex::icase);
    static const std::regex bearer(R"((Bearer\s+)[A-Za-z0-9._~+/=-]+)", std::regex::icase);
    text = std::regex_replace(text, json_secret, "$1***$2");
    text = std::regex_replace(text, query_secret, "$1***");
    text = std::regex_replace(text, bearer, "$1***");
    return text;
}

std::string Logger::mask_identifier(std::string_view value) {
    if (value.size() <= 6) return "***";
    return std::string(value.substr(0, 3)) + "***" +
           std::string(value.substr(value.size() - 3));
}

}  // namespace yoox

