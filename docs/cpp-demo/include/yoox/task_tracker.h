#pragma once

#include <chrono>
#include <cstdint>
#include <map>
#include <mutex>
#include <optional>
#include <string>

namespace yoox {

struct CorrelatedRequest {
    std::uint64_t generation = 0;
    std::string tid;
    std::string bid;
    std::optional<std::int64_t> seq;
    std::chrono::steady_clock::time_point sent_at;
};

struct CorrelatedReply {
    std::uint64_t generation = 0;
    std::string tid;
    std::string bid;
    std::optional<std::int64_t> seq;
    std::optional<int> result;
    std::chrono::steady_clock::time_point received_at;
};

class CorrelationTracker {
public:
    void begin_generation(std::uint64_t generation);
    void remember(const CorrelatedRequest& request);
    bool accept(const CorrelatedReply& reply, std::chrono::milliseconds maximum_age);
    void clear();
    std::uint64_t generation() const;

private:
    mutable std::mutex mutex_;
    std::uint64_t generation_ = 0;
    std::map<std::string, CorrelatedRequest> pending_;
};

class DrcInterlock {
public:
    void reconnect(std::uint64_t generation);
    void register_probe(const CorrelatedRequest& request, int step);
    bool accept_probe(const CorrelatedReply& reply);
    bool controls_ready() const;
    void lock();

private:
    mutable std::mutex mutex_;
    std::uint64_t generation_ = 0;
    int next_step_ = 0;
    bool ready_ = false;
    std::map<std::string, std::pair<CorrelatedRequest, int>> probes_;
};

struct PointFlightState {
    std::string kind;
    std::string status;
    std::string task_id;
    bool active = false;
    std::int64_t updated_at = 0;
    std::optional<int> result;
};

class TaskTracker {
public:
    static bool terminal(const PointFlightState& state);
    bool accept(const PointFlightState& state);
    std::optional<PointFlightState> current() const;

private:
    mutable std::mutex mutex_;
    std::optional<PointFlightState> current_;
};

}  // namespace yoox

