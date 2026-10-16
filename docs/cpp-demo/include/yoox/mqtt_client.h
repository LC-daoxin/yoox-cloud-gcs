#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace yoox {

struct MqttOptions {
    std::string address;
    std::string client_id;
    std::string username;
    std::string password;
    bool tls = false;
    std::string ca_file;
    int keep_alive_seconds = 30;
};

class MqttClient {
public:
    using MessageHandler = std::function<void(const std::string&, const std::string&, std::uint64_t)>;
    using GenerationHandler = std::function<void(std::uint64_t)>;

    explicit MqttClient(MqttOptions options);
    ~MqttClient();
    MqttClient(const MqttClient&) = delete;
    MqttClient& operator=(const MqttClient&) = delete;

    void set_message_handler(MessageHandler handler);
    void set_generation_handler(GenerationHandler handler);
    bool connect(std::chrono::seconds timeout = std::chrono::seconds(8));
    bool subscribe(const std::vector<std::pair<std::string, int>>& topics,
                   std::chrono::seconds timeout = std::chrono::seconds(8));
    bool publish(const std::string& topic, const std::string& payload, int qos = 0,
                 bool retained = false);
    void disconnect();
    bool connected() const;
    std::uint64_t generation() const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace yoox

