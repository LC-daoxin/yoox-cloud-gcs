#include "yoox/mqtt_client.h"

#include "yoox/logger.h"

#include <mqtt/async_client.h>

#include <condition_variable>
#include <mutex>

namespace yoox {

class MqttClient::Impl final : public virtual mqtt::callback {
public:
    explicit Impl(MqttOptions options)
        : options_(std::move(options)), client_(options_.address, options_.client_id) {
        client_.set_callback(*this);
    }

    bool connect(std::chrono::seconds timeout) {
        mqtt::connect_options_builder builder;
        builder.clean_session(true)
            .automatic_reconnect(std::chrono::seconds(1), std::chrono::seconds(30))
            .keep_alive_interval(std::chrono::seconds(options_.keep_alive_seconds));
        if (!options_.username.empty()) {
            builder.user_name(options_.username).password(options_.password);
        }
        if (options_.tls) {
            mqtt::ssl_options_builder ssl;
            ssl.enable_server_cert_auth(true);
            if (!options_.ca_file.empty()) ssl.trust_store(options_.ca_file);
            builder.ssl(std::move(ssl).finalize());
        }
        try {
            auto token = client_.connect(std::move(builder).finalize());
            if (!token->wait_for(timeout)) return false;
            std::unique_lock<std::mutex> lock(mutex_);
            return condition_.wait_for(lock, timeout, [&] { return connected_; });
        } catch (const mqtt::exception& exception) {
            Logger::log(LogLevel::error, std::string("MQTT connect failed: ") + exception.what());
            return false;
        }
    }

    bool subscribe(const std::vector<std::pair<std::string, int>>& topics,
                   std::chrono::seconds timeout) {
        std::vector<std::string> names;
        std::vector<int> qos;
        for (const auto& [topic, level] : topics) {
            names.push_back(topic);
            qos.push_back(level);
        }
        {
            std::lock_guard<std::mutex> lock(mutex_);
            subscriptions_ = topics;
        }
        try {
            if (names.empty()) return true;
            auto token = client_.subscribe(mqtt::string_collection::create(names), qos);
            return token->wait_for(timeout);
        } catch (const mqtt::exception& exception) {
            Logger::log(LogLevel::error, std::string("MQTT subscribe failed: ") + exception.what());
            return false;
        }
    }

    bool publish(const std::string& topic, const std::string& payload, int qos, bool retained) {
        if (!client_.is_connected()) return false;
        try {
            auto message = mqtt::make_message(topic, payload, qos, retained);
            client_.publish(message);
            return true;
        } catch (const mqtt::exception& exception) {
            Logger::log(LogLevel::error, std::string("MQTT publish failed: ") + exception.what());
            return false;
        }
    }

    void disconnect() {
        try {
            if (client_.is_connected()) client_.disconnect()->wait_for(std::chrono::seconds(2));
        } catch (const mqtt::exception& exception) {
            Logger::log(LogLevel::warning, std::string("MQTT disconnect failed: ") + exception.what());
        }
        std::lock_guard<std::mutex> lock(mutex_);
        connected_ = false;
    }

    void connected(const std::string&) override {
        std::vector<std::pair<std::string, int>> subscriptions;
        GenerationHandler generation_handler;
        std::uint64_t generation;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            connected_ = true;
            generation = ++generation_;
            subscriptions = subscriptions_;
            generation_handler = generation_handler_;
        }
        condition_.notify_all();
        if (generation_handler) generation_handler(generation);
        if (!subscriptions.empty()) {
            std::vector<std::string> names;
            std::vector<int> qos;
            for (const auto& [topic, level] : subscriptions) {
                names.push_back(topic);
                qos.push_back(level);
            }
            try { client_.subscribe(mqtt::string_collection::create(names), qos); }
            catch (const mqtt::exception& exception) {
                Logger::log(LogLevel::error, std::string("MQTT resubscribe failed: ") + exception.what());
            }
        }
    }

    void connection_lost(const std::string& cause) override {
        std::lock_guard<std::mutex> lock(mutex_);
        connected_ = false;
        Logger::log(LogLevel::warning, "MQTT disconnected: " + cause);
    }

    void message_arrived(mqtt::const_message_ptr message) override {
        MessageHandler handler;
        std::uint64_t generation;
        {
            std::lock_guard<std::mutex> lock(mutex_);
            handler = message_handler_;
            generation = generation_;
        }
        if (handler) handler(message->get_topic(), message->to_string(), generation);
    }

    void delivery_complete(mqtt::delivery_token_ptr) override {}

    mutable std::mutex mutex_;
    std::condition_variable condition_;
    MqttOptions options_;
    mqtt::async_client client_;
    bool connected_ = false;
    std::uint64_t generation_ = 0;
    std::vector<std::pair<std::string, int>> subscriptions_;
    MessageHandler message_handler_;
    GenerationHandler generation_handler_;
};

MqttClient::MqttClient(MqttOptions options) : impl_(std::make_unique<Impl>(std::move(options))) {}
MqttClient::~MqttClient() { impl_->disconnect(); }
void MqttClient::set_message_handler(MessageHandler handler) {
    std::lock_guard<std::mutex> lock(impl_->mutex_); impl_->message_handler_ = std::move(handler);
}
void MqttClient::set_generation_handler(GenerationHandler handler) {
    std::lock_guard<std::mutex> lock(impl_->mutex_); impl_->generation_handler_ = std::move(handler);
}
bool MqttClient::connect(std::chrono::seconds timeout) { return impl_->connect(timeout); }
bool MqttClient::subscribe(const std::vector<std::pair<std::string, int>>& topics,
                           std::chrono::seconds timeout) { return impl_->subscribe(topics, timeout); }
bool MqttClient::publish(const std::string& topic, const std::string& payload, int qos, bool retained) {
    return impl_->publish(topic, payload, qos, retained);
}
void MqttClient::disconnect() { impl_->disconnect(); }
bool MqttClient::connected() const { return impl_->client_.is_connected(); }
std::uint64_t MqttClient::generation() const {
    std::lock_guard<std::mutex> lock(impl_->mutex_); return impl_->generation_;
}

}  // namespace yoox

