#include "yoox/config.h"
#include "yoox/device_models.h"
#include "yoox/http_client.h"
#include "yoox/kmz.h"
#include "yoox/yoox_client.h"

#include <cstdlib>
#include <deque>
#include <filesystem>
#include <iostream>
#include <memory>

namespace {
int failures = 0;
void check(bool value, const char* expression, int line) {
    if (!value) { std::cerr << "FAIL line " << line << ": " << expression << '\n'; ++failures; }
}
#define CHECK(value) check((value), #value, __LINE__)

class FakeTransport final : public yoox::IHttpTransport {
public:
    std::deque<yoox::HttpResponse> responses;
    std::vector<yoox::HttpRequest> requests;
    bool throw_ambiguous = false;
    yoox::HttpResponse perform(const yoox::HttpRequest& request) override {
        requests.push_back(request);
        if (throw_ambiguous)
            throw yoox::ApiError(request.action, "timeout", true);
        if (responses.empty()) throw std::runtime_error("unexpected request");
        auto response = responses.front();
        responses.pop_front();
        return response;
    }
};

yoox::Config config() {
    return yoox::Config::from_layers({}, {}, {
        {"YOOX_WEB_USERNAME", "admin"}, {"YOOX_WEB_PASSWORD", "safe-test-password"},
        {"YOOX_WORKSPACE_ID", "workspace"}, {"YOOX_BASE_URL", "https://example.test"}
    });
}

void test_timeout_has_no_retry() {
    auto fake = std::make_shared<FakeTransport>();
    fake->throw_ambiguous = true;
    yoox::YooxClient client(config(), fake);
    client.set_token_for_test("token");
    bool ambiguous = false;
    try { client.stop_fly_to_point("gateway"); }
    catch (const yoox::ApiError& error) { ambiguous = error.ambiguous(); }
    CHECK(ambiguous);
    CHECK(fake->requests.size() == 1);
}

void test_login_and_business_error() {
    auto fake = std::make_shared<FakeTransport>();
    fake->responses.push_back({200, R"({"code":0,"data":{"access_token":"token","workspace_id":"ws"}})"});
    yoox::YooxClient client(config(), fake);
    const auto data = client.login();
    CHECK(data.at("workspace_id") == "ws");
    CHECK(fake->requests.size() == 1);
    CHECK(fake->requests[0].body.at("password") == "safe-test-password");
    fake->responses.push_back({200, R"({"code":211001,"message":"No message reply received"})"});
    bool ambiguous = false;
    try { client.acquire_flight_authority("gateway"); }
    catch (const yoox::ApiError& error) { ambiguous = error.ambiguous(); }
    CHECK(ambiguous);
    CHECK(fake->requests.size() == 2);
}

void test_topology_and_read_only_method() {
    const auto parsed = yoox::parse_device(nlohmann::json::parse(
        R"({"device_sn":"gateway","aircraft_sn":"aircraft","remote_controller_sn":"rc","domain":2,"status":true,"children":{"status":true}})"));
    CHECK(parsed.gateway_sn == "gateway");
    CHECK(parsed.aircraft_sn == "aircraft");
    CHECK(parsed.remote_controller_sn == "rc");
    CHECK(parsed.aircraft_online == true);
    CHECK(yoox::mqtt_topic("gateway", "events") == "thing/product/gateway/events");
    auto fake = std::make_shared<FakeTransport>();
    fake->responses.push_back({200, R"({"code":0,"data":null})"});
    yoox::YooxClient client(config(), fake);
    client.set_token_for_test("token");
    client.point_flight_status("gateway");
    CHECK(fake->requests[0].method == yoox::HttpMethod::get);
    CHECK(!fake->requests[0].has_body);
}

void test_websocket_parser() {
    const auto event = yoox::parse_websocket_event(
        R"({"biz_code":"device_osd","data":{"host":{"mode_code":16}}})");
    CHECK(event.biz_code == "device_osd");
    CHECK(event.data.at("host").at("mode_code") == 16);
}

void test_kmz_structure() {
    yoox::KmzMission mission;
    mission.name = "UTF-8 航线";
    mission.waypoints = {{117.1, 39.1, 1}, {117.2, 39.2, 2}};
    mission.gimbal_pitch_actions[0] = -44;
    const auto path = std::filesystem::temp_directory_path() / "yoox-cpp-test.kmz";
    yoox::Kmz::generate(path, mission);
    const auto result = yoox::Kmz::validate(path);
    CHECK(result.valid);
    std::filesystem::remove(path);
}
}

int main() {
    test_login_and_business_error();
    test_topology_and_read_only_method();
    test_websocket_parser();
    test_timeout_has_no_retry();
    test_kmz_structure();
    return failures == 0 ? EXIT_SUCCESS : EXIT_FAILURE;
}

