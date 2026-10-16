#include "yoox/http_client.h"

#include "yoox/logger.h"

#include <curl/curl.h>

#include <mutex>
#include <sstream>

namespace yoox {
namespace {

std::once_flag curl_initialised;

std::size_t write_callback(char* data, std::size_t size, std::size_t count, void* user_data) {
    auto* target = static_cast<std::string*>(user_data);
    target->append(data, size * count);
    return size * count;
}

const char* method_name(HttpMethod method) {
    switch (method) {
        case HttpMethod::get: return "GET";
        case HttpMethod::post: return "POST";
        case HttpMethod::put: return "PUT";
        case HttpMethod::delete_: return "DELETE";
    }
    return "GET";
}

}  // namespace

ApiError::ApiError(std::string action, std::string message, bool ambiguous,
                   long http_status, std::optional<int> api_code)
    : std::runtime_error(std::move(action) + " failed: " + std::move(message) +
                         (ambiguous ? " (result unknown; do not retry before querying state)" : "")),
      ambiguous_(ambiguous), http_status_(http_status), api_code_(api_code) {}

bool is_mutating(HttpMethod method) { return method != HttpMethod::get; }

std::string url_encode(const std::string& value) {
    std::call_once(curl_initialised, [] { curl_global_init(CURL_GLOBAL_DEFAULT); });
    CURL* handle = curl_easy_init();
    if (!handle) throw std::runtime_error("curl_easy_init failed");
    char* encoded = curl_easy_escape(handle, value.c_str(), static_cast<int>(value.size()));
    std::string result = encoded ? encoded : "";
    curl_free(encoded);
    curl_easy_cleanup(handle);
    return result;
}

HttpClient::HttpClient(const Config& config)
    : verify_tls_(config.get_bool("YOOX_TLS_VERIFY", true)),
      ca_bundle_(config.get("YOOX_CA_BUNDLE")) {
    std::call_once(curl_initialised, [] { curl_global_init(CURL_GLOBAL_DEFAULT); });
}

HttpResponse HttpClient::perform(const HttpRequest& request) {
    const bool may_have_side_effect = request.side_effecting && is_mutating(request.method);
    CURL* curl = curl_easy_init();
    if (!curl) throw ApiError(request.action, "unable to initialise libcurl", may_have_side_effect);

    std::string url = join_url(request.base_url, request.path);
    if (!request.query.empty()) {
        bool first = true;
        url += '?';
        for (const auto& [key, value] : request.query) {
            if (!first) url += '&';
            first = false;
            url += url_encode(key) + "=" + url_encode(value);
        }
    }

    std::string response_body;
    std::string json_body;
    curl_slist* headers = nullptr;
    curl_mime* mime = nullptr;
    curl_easy_setopt(curl, CURLOPT_URL, url.c_str());
    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, method_name(request.method));
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, write_callback);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &response_body);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT_MS,
                     static_cast<long>(request.connect_timeout.count()));
    curl_easy_setopt(curl, CURLOPT_TIMEOUT_MS, static_cast<long>(request.total_timeout.count()));
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYPEER, verify_tls_ ? 1L : 0L);
    curl_easy_setopt(curl, CURLOPT_SSL_VERIFYHOST, verify_tls_ ? 2L : 0L);
    if (!ca_bundle_.empty()) curl_easy_setopt(curl, CURLOPT_CAINFO, ca_bundle_.c_str());
    if (!request.token.empty()) {
        headers = curl_slist_append(headers, ("x-auth-token: " + request.token).c_str());
    }
    headers = curl_slist_append(headers, "Accept: application/json");

    if (request.upload_file) {
        mime = curl_mime_init(curl);
        auto* part = curl_mime_addpart(mime);
        curl_mime_name(part, request.upload_field.c_str());
        const auto upload_path = request.upload_file->u8string();
        curl_mime_filedata(part, upload_path.c_str());
        curl_easy_setopt(curl, CURLOPT_MIMEPOST, mime);
    } else if (request.has_body) {
        json_body = request.body.dump();
        headers = curl_slist_append(headers, "Content-Type: application/json; charset=utf-8");
        curl_easy_setopt(curl, CURLOPT_POSTFIELDS, json_body.data());
        curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, static_cast<long>(json_body.size()));
    }
    curl_easy_setopt(curl, CURLOPT_HTTPHEADER, headers);

    Logger::log(LogLevel::debug, std::string(method_name(request.method)) + " " + url);
    const CURLcode code = curl_easy_perform(curl);
    long status = 0;
    curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &status);
    if (mime) curl_mime_free(mime);
    if (headers) curl_slist_free_all(headers);
    curl_easy_cleanup(curl);

    if (code != CURLE_OK) {
        throw ApiError(request.action, curl_easy_strerror(code), may_have_side_effect);
    }
    return {status, std::move(response_body)};
}

}  // namespace yoox

