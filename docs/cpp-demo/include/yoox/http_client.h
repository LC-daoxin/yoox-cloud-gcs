#pragma once

#include "yoox/config.h"

#include <chrono>
#include <filesystem>
#include <map>
#include <nlohmann/json.hpp>
#include <optional>
#include <stdexcept>
#include <string>

namespace yoox {

enum class HttpMethod { get, post, put, delete_ };

struct HttpRequest {
    HttpMethod method = HttpMethod::get;
    std::string path;
    std::string action;
    std::string base_url;
    std::string token;
    nlohmann::json body;
    bool has_body = false;
    bool side_effecting = true;
    std::map<std::string, std::string> query;
    std::optional<std::filesystem::path> upload_file;
    std::string upload_field = "file";
    std::chrono::milliseconds connect_timeout{5000};
    std::chrono::milliseconds total_timeout{20000};
};

struct HttpResponse {
    long status = 0;
    std::string body;
};

class ApiError : public std::runtime_error {
public:
    ApiError(std::string action, std::string message, bool ambiguous,
             long http_status = 0, std::optional<int> api_code = std::nullopt);
    bool ambiguous() const noexcept { return ambiguous_; }
    long http_status() const noexcept { return http_status_; }
    std::optional<int> api_code() const noexcept { return api_code_; }

private:
    bool ambiguous_;
    long http_status_;
    std::optional<int> api_code_;
};

class IHttpTransport {
public:
    virtual ~IHttpTransport() = default;
    virtual HttpResponse perform(const HttpRequest& request) = 0;
};

class HttpClient final : public IHttpTransport {
public:
    explicit HttpClient(const Config& config);
    HttpResponse perform(const HttpRequest& request) override;

private:
    bool verify_tls_ = true;
    std::string ca_bundle_;
};

bool is_mutating(HttpMethod method);
std::string url_encode(const std::string& value);

}  // namespace yoox

