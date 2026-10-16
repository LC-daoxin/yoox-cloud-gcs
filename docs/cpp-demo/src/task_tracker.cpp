#include "yoox/task_tracker.h"

#include <set>

namespace yoox {

void CorrelationTracker::begin_generation(std::uint64_t generation) {
    std::lock_guard<std::mutex> lock(mutex_);
    generation_ = generation;
    pending_.clear();
}

void CorrelationTracker::remember(const CorrelatedRequest& request) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (request.generation == generation_ && !request.tid.empty()) pending_[request.tid] = request;
}

bool CorrelationTracker::accept(const CorrelatedReply& reply, std::chrono::milliseconds maximum_age) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (reply.generation != generation_ || reply.tid.empty()) return false;
    const auto found = pending_.find(reply.tid);
    if (found == pending_.end()) return false;
    const auto& request = found->second;
    if (request.bid != reply.bid || request.seq != reply.seq ||
        reply.received_at < request.sent_at || reply.received_at - request.sent_at > maximum_age) {
        return false;
    }
    pending_.erase(found);
    return true;
}

void CorrelationTracker::clear() {
    std::lock_guard<std::mutex> lock(mutex_);
    pending_.clear();
}

std::uint64_t CorrelationTracker::generation() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return generation_;
}

void DrcInterlock::reconnect(std::uint64_t generation) {
    std::lock_guard<std::mutex> lock(mutex_);
    generation_ = generation;
    next_step_ = 0;
    ready_ = false;
    probes_.clear();
}

void DrcInterlock::register_probe(const CorrelatedRequest& request, int step) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (request.generation == generation_ && (step == 0 || step == 1)) {
        probes_[request.tid] = {request, step};
    }
}

bool DrcInterlock::accept_probe(const CorrelatedReply& reply) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (reply.generation != generation_ || reply.result != std::optional<int>(0)) return false;
    const auto found = probes_.find(reply.tid);
    if (found == probes_.end()) return false;
    const auto& request = found->second.first;
    const int step = found->second.second;
    if (request.bid != reply.bid || request.seq != reply.seq || step != next_step_ ||
        reply.received_at < request.sent_at ||
        reply.received_at - request.sent_at > std::chrono::milliseconds(1500)) return false;
    probes_.erase(found);
    if (step == 0) {
        next_step_ = 1;
        return false;
    }
    probes_.clear();
    next_step_ = 0;
    ready_ = true;
    return true;
}

bool DrcInterlock::controls_ready() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return ready_;
}

void DrcInterlock::lock() {
    std::lock_guard<std::mutex> lock(mutex_);
    ready_ = false;
    next_step_ = 0;
    probes_.clear();
}

bool TaskTracker::terminal(const PointFlightState& state) {
    static const std::set<std::string> terminal_states = {
        "task_finish", "wayline_cancel", "wayline_failed", "wayline_ok",
        "command_failed", "cancel_confirmed"
    };
    return !state.active || terminal_states.count(state.status) != 0;
}

bool TaskTracker::accept(const PointFlightState& state) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (state.task_id.empty()) return false;
    if (current_) {
        if (state.task_id != current_->task_id && state.updated_at <= current_->updated_at) return false;
        if (state.task_id == current_->task_id && state.updated_at < current_->updated_at) return false;
        if (terminal(*current_) && state.task_id == current_->task_id && !terminal(state)) return false;
    }
    current_ = state;
    return true;
}

std::optional<PointFlightState> TaskTracker::current() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return current_;
}

}  // namespace yoox

