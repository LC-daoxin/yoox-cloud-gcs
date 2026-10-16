#pragma once

#include <filesystem>
#include <optional>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <vector>

namespace yoox {

class ConfigError : public std::runtime_error {
public:
    using std::runtime_error::runtime_error;
};

class Config {
public:
    static Config load(int argc, char** argv);
    static Config from_layers(
        const std::unordered_map<std::string, std::string>& file_values,
        const std::unordered_map<std::string, std::string>& environment_values,
        const std::unordered_map<std::string, std::string>& cli_values);
    static std::unordered_map<std::string, std::string> parse_env_file(
        const std::filesystem::path& path);

    std::string get(const std::string& key, const std::string& fallback = "") const;
    std::string require(const std::string& key) const;
    int get_int(const std::string& key, int fallback) const;
    double get_double(const std::string& key, double fallback) const;
    std::optional<double> get_optional_double(const std::string& key) const;
    bool get_bool(const std::string& key, bool fallback) const;
    bool is_placeholder(const std::string& value) const;

    bool execute() const noexcept { return execute_; }
    bool has_flag(const std::string& name) const;
    std::string option(const std::string& name, const std::string& fallback = "") const;
    const std::vector<std::string>& positional() const noexcept { return positional_; }
    const std::filesystem::path& env_file() const noexcept { return env_file_; }

    std::string base_url() const;
    std::string pilot_base_url() const;
    std::string websocket_url() const;

private:
    static const std::vector<std::string>& known_environment_keys();
    std::unordered_map<std::string, std::string> values_;
    std::unordered_map<std::string, std::string> options_;
    std::vector<std::string> positional_;
    std::filesystem::path env_file_;
    bool execute_ = false;
};

std::string join_url(const std::string& base, const std::string& path);

}  // namespace yoox

