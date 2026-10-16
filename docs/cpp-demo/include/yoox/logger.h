#pragma once

#include <string>
#include <string_view>

namespace yoox {

enum class LogLevel { debug, info, warning, error };

class Logger {
public:
    static void set_debug(bool enabled);
    static void log(LogLevel level, std::string_view message);
    static std::string redact(std::string text);
    static std::string mask_identifier(std::string_view value);
};

}  // namespace yoox

