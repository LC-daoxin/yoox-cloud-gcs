#pragma once

#include <cstdint>
#include <nlohmann/json.hpp>
#include <string>

namespace yoox {

struct DrcMessage {
    std::string tid;
    std::string bid;
    std::int64_t timestamp = 0;
    std::string method;
    nlohmann::json data = nlohmann::json::object();
};

nlohmann::json to_json(const DrcMessage& message);
DrcMessage make_drc_message(const std::string& method, nlohmann::json data);

}  // namespace yoox

