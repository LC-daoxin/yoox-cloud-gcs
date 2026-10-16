#include "yoox/websocket_client.h"

#include "yoox/logger.h"

#include <ixwebsocket/IXNetSystem.h>
#include <ixwebsocket/IXWebSocket.h>

#include <atomic>
#include <condition_variable>
#include <mutex>

namespace yoox {
namespace {
std::once_flag network_initialised;
}

class WebSocketClient::Impl {
public:
    Impl() { std::call_once(network_initialised, [] { ix::initNetSystem(); }); }

    bool connect(const std::string& url, std::chrono::seconds timeout) {
        socket.setUrl(url);
        socket.setPingInterval(10);
        socket.enableAutomaticReconnection();
        socket.setOnMessageCallback([this](const ix::WebSocketMessagePtr& message) {
            if (message->type == ix::WebSocketMessageType::Open) {
                connected.store(true);
                condition.notify_all();
                if (state_handler) state_handler(true, "connected");
            } else if (message->type == ix::WebSocketMessageType::Message) {
                if (message_handler) message_handler(message->str);
            } else if (message->type == ix::WebSocketMessageType::Error) {
                if (state_handler) state_handler(false, message->errorInfo.reason);
            } else if (message->type == ix::WebSocketMessageType::Close) {
                connected.store(false);
                if (state_handler) state_handler(false, message->closeInfo.reason);
            }
        });
        socket.start();
        std::unique_lock<std::mutex> lock(mutex);
        return condition.wait_for(lock, timeout, [&] { return connected.load(); });
    }

    ix::WebSocket socket;
    std::atomic<bool> connected{false};
    std::mutex mutex;
    std::condition_variable condition;
    MessageHandler message_handler;
    StateHandler state_handler;
};

WebSocketClient::WebSocketClient() : impl_(std::make_unique<Impl>()) {}
WebSocketClient::~WebSocketClient() { close(); }
void WebSocketClient::set_message_handler(MessageHandler handler) { impl_->message_handler = std::move(handler); }
void WebSocketClient::set_state_handler(StateHandler handler) { impl_->state_handler = std::move(handler); }
bool WebSocketClient::connect(const std::string& url, std::chrono::seconds timeout) {
    return impl_->connect(url, timeout);
}
void WebSocketClient::close() { impl_->socket.stop(); impl_->connected.store(false); }
bool WebSocketClient::connected() const { return impl_->connected.load(); }

}  // namespace yoox

