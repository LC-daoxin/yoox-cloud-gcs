#include "yoox/config.h"
#include "yoox/logger.h"
#include "yoox/safety_guard.h"
#include "yoox/task_tracker.h"

#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>

namespace {
int failures = 0;
void check(bool condition, const char* expression, int line) {
    if (!condition) {
        std::cerr << "FAIL line " << line << ": " << expression << '\n';
        ++failures;
    }
}
#define CHECK(value) check((value), #value, __LINE__)

void test_config_layers() {
    auto config = yoox::Config::from_layers(
        {{"YOOX_SERVER_IP", "file"}, {"YOOX_SERVER_PORT", "8000"}},
        {{"YOOX_SERVER_IP", "environment"}},
        {{"YOOX_SERVER_IP", "cli"}});
    CHECK(config.get("YOOX_SERVER_IP") == "cli");
    CHECK(config.get_int("YOOX_SERVER_PORT", 0) == 8000);
    CHECK(config.is_placeholder("YOUR_DOCK_SN"));
    CHECK(config.is_placeholder("change_me"));
    CHECK(!config.is_placeholder("configured"));
    CHECK(yoox::Config::from_layers({}, {}, {{"YOOX_BASE_URL", "https://example.test/api"}})
              .base_url() == "https://example.test/api");
    bool invalid_number = false;
    try {
        yoox::Config::from_layers({}, {}, {{"YOOX_SERVER_PORT", "not-a-number"}})
            .get_int("YOOX_SERVER_PORT", 0);
    } catch (const yoox::ConfigError&) { invalid_number = true; }
    CHECK(invalid_number);
    bool invalid_url = false;
    try {
        yoox::Config::from_layers({}, {}, {{"YOOX_BASE_URL", "file:///unsafe"}}).base_url();
    } catch (const yoox::ConfigError&) { invalid_url = true; }
    CHECK(invalid_url);
}

void test_env_parser() {
    const auto path = std::filesystem::temp_directory_path() / "yoox-cpp-env-test.env";
    {
        std::ofstream output(path);
        output << "# comment\nexport YOOX_SERVER_IP=10.0.0.1\nYOOX_WEB_USERNAME=\"operator\"\n";
    }
    const auto values = yoox::Config::parse_env_file(path);
    CHECK(values.at("YOOX_SERVER_IP") == "10.0.0.1");
    CHECK(values.at("YOOX_WEB_USERNAME") == "operator");
    std::filesystem::remove(path);
}

void test_url_and_redaction() {
    CHECK(yoox::join_url("https://example.test/", "/api/v1") == "https://example.test/api/v1");
    CHECK(yoox::join_url("https://example.test", "api/v1") == "https://example.test/api/v1");
    const auto redacted = yoox::Logger::redact(
        R"({"password":"secret","access_token":"abc"} ?x-auth-token=jwt-value)");
    CHECK(redacted.find("secret") == std::string::npos);
    CHECK(redacted.find("jwt-value") == std::string::npos);
}

void test_safety_guard() {
    const auto invalid = yoox::SafetyGuard::validate_target({0, 0, 1, 20}, true);
    CHECK(invalid.size() >= 3);
    CHECK(yoox::SafetyGuard::validate_target({39, 117, 30, 5}, true).empty());
    CHECK(yoox::SafetyGuard::is_valid_payload_index("10806-0-0"));
    yoox::SafetyRequest request;
    request.action = "takeoff_to_point";
    request.execute = false;
    request.required_confirmation = "TAKEOFF";
    request.device.gateway_sn = "gateway";
    request.device.aircraft_sn = "aircraft";
    request.device.gateway_online = true;
    request.device.aircraft_online = true;
    auto decision = yoox::SafetyGuard::evaluate(request);
    CHECK(!decision.allowed);
    CHECK(decision.dry_run);
    request.execute = true;
    request.supplied_confirmation = "wrong";
    CHECK(!yoox::SafetyGuard::evaluate(request).allowed);
    request.supplied_confirmation = "TAKEOFF";
    CHECK(yoox::SafetyGuard::evaluate(request).allowed);
}

void test_correlation_and_drc_generation() {
    using namespace std::chrono;
    const auto now = steady_clock::now();
    yoox::CorrelationTracker tracker;
    tracker.begin_generation(1);
    tracker.remember({1, "tid", "bid", 7, now});
    CHECK(!tracker.accept({1, "tid", "wrong", 7, 0, now + milliseconds(10)}, milliseconds(100)));
    CHECK(tracker.accept({1, "tid", "bid", 7, 0, now + milliseconds(20)}, milliseconds(100)));

    yoox::DrcInterlock interlock;
    interlock.reconnect(10);
    interlock.register_probe({10, "a", "a", 0, now}, 0);
    interlock.register_probe({10, "b", "b", 1, now + milliseconds(100)}, 1);
    CHECK(!interlock.accept_probe({10, "b", "b", 1, 0, now + milliseconds(110)}));
    CHECK(!interlock.accept_probe({10, "a", "a", 0, 0, now + milliseconds(120)}));
    CHECK(interlock.accept_probe({10, "b", "b", 1, 0, now + milliseconds(130)}));
    CHECK(interlock.controls_ready());
    interlock.reconnect(11);
    CHECK(!interlock.controls_ready());
    CHECK(!interlock.accept_probe({10, "b", "b", 1, 0, now + milliseconds(140)}));
}

void test_task_tracker() {
    yoox::TaskTracker tracker;
    CHECK(tracker.accept({"flyto", "wayline_progress", "task-1", true, 2, 0}));
    CHECK(tracker.accept({"flyto", "wayline_ok", "task-1", false, 3, 0}));
    CHECK(!tracker.accept({"flyto", "wayline_progress", "task-1", true, 2, 0}));
    CHECK(yoox::TaskTracker::terminal(*tracker.current()));
    yoox::TaskTracker second_aircraft;
    CHECK(second_aircraft.accept({"takeoff", "command_accepted", "task-2", true, 1, 0}));
    CHECK(tracker.current()->task_id == "task-1");
    CHECK(second_aircraft.current()->task_id == "task-2");
}
}

int main() {
    test_config_layers();
    test_env_parser();
    test_url_and_redaction();
    test_safety_guard();
    test_correlation_and_drc_generation();
    test_task_tracker();
    if (failures) std::cerr << failures << " test(s) failed\n";
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

