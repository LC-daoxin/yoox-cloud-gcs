#pragma once

#include <chrono>
#include <functional>
#include <memory>
#include <string>

namespace yoox {

class WebSocketClient {
public:
    using MessageHandler = std::function<void(const std::string&)>;
    using StateHandler = std::function<void(bool, const std::string&)>;

    WebSocketClient();
    ~WebSocketClient();
    WebSocketClient(const WebSocketClient&) = delete;
    WebSocketClient& operator=(const WebSocketClient&) = delete;

    void set_message_handler(MessageHandler handler);
    void set_state_handler(StateHandler handler);
    bool connect(const std::string& url, std::chrono::seconds timeout = std::chrono::seconds(8));
    void close();
    bool connected() const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace yoox

