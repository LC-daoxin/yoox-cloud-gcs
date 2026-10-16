#include "yoox/kmz.h"

#include "yoox/safety_guard.h"

#include <zip.h>

#include <chrono>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <sstream>
#include <stdexcept>

namespace yoox {
namespace {

constexpr const char* kKmlNs = "http://www.opengis.net/kml/2.2";
constexpr const char* kWpmlNs = "http://www.autel.com/wpmz/1.0.0";

std::string xml_escape(std::string value) {
    const std::pair<const char*, const char*> replacements[] = {
        {"&", "&amp;"}, {"<", "&lt;"}, {">", "&gt;"}, {"\"", "&quot;"}, {"'", "&apos;"}
    };
    for (const auto& [from, to] : replacements) {
        std::size_t position = 0;
        while ((position = value.find(from, position)) != std::string::npos) {
            value.replace(position, std::char_traits<char>::length(from), to);
            position += std::char_traits<char>::length(to);
        }
    }
    return value;
}

std::string action_group(std::size_t index, double pitch) {
    std::ostringstream stream;
    stream << "<wpml:actionGroup><wpml:actionGroupId>0</wpml:actionGroupId>"
           << "<wpml:actionGroupStartIndex>" << index << "</wpml:actionGroupStartIndex>"
           << "<wpml:actionGroupEndIndex>" << index << "</wpml:actionGroupEndIndex>"
           << "<wpml:actionGroupMode>sequence</wpml:actionGroupMode>"
           << "<wpml:actionTrigger><wpml:actionTriggerType>reachPoint</wpml:actionTriggerType>"
           << "</wpml:actionTrigger><wpml:action><wpml:actionId>0</wpml:actionId>"
           << "<wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>"
           << "<wpml:actionActuatorFuncParam><wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>"
           << "<wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>"
           << "<wpml:gimbalPitchRotateAngle>" << pitch << "</wpml:gimbalPitchRotateAngle>"
           << "<wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable>"
           << "<wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable>"
           << "</wpml:actionActuatorFuncParam></wpml:action></wpml:actionGroup>";
    return stream.str();
}

void validate_mission(const KmzMission& mission) {
    if (mission.name.empty()) throw std::invalid_argument("KMZ mission name is required");
    if (mission.waypoints.size() < 2) throw std::invalid_argument("KMZ requires at least two waypoints");
    for (const auto& point : mission.waypoints) {
        const auto errors = SafetyGuard::validate_target(
            {point.latitude, point.longitude, mission.height, mission.speed}, false, 1500);
        if (!errors.empty()) throw std::invalid_argument(errors.front());
    }
    if (mission.takeoff_security_height < 1 || mission.takeoff_security_height > 1500) {
        throw std::invalid_argument("takeoff security height must be 1-1500 m");
    }
}

void add_file(zip_t* archive, const char* name, const std::string& data) {
    void* owned = std::malloc(data.size());
    if (!owned) throw std::bad_alloc();
    std::memcpy(owned, data.data(), data.size());
    auto* source = zip_source_buffer(archive, owned, data.size(), 1);
    if (!source) std::free(owned);
    if (!source) throw std::runtime_error("unable to allocate ZIP source");
    if (zip_file_add(archive, name, source, ZIP_FL_ENC_UTF_8 | ZIP_FL_OVERWRITE) < 0) {
        zip_source_free(source);
        throw std::runtime_error("unable to add file to KMZ");
    }
}

std::string read_zip_entry(zip_t* archive, const char* name) {
    zip_stat_t stat{};
    zip_stat_init(&stat);
    if (zip_stat(archive, name, ZIP_FL_ENC_UTF_8, &stat) != 0) return {};
    zip_file_t* file = zip_fopen(archive, name, ZIP_FL_ENC_UTF_8);
    if (!file) return {};
    std::string content(static_cast<std::size_t>(stat.size), '\0');
    const auto count = zip_fread(file, content.data(), stat.size);
    zip_fclose(file);
    if (count < 0 || static_cast<zip_uint64_t>(count) != stat.size) return {};
    return content;
}

}  // namespace

std::string Kmz::build_template(const KmzMission& mission) {
    validate_mission(mission);
    const auto timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
    std::ostringstream out;
    out << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        << "<kml xmlns=\"" << kKmlNs << "\" xmlns:wpml=\"" << kWpmlNs << "\"><Document>"
        << "<wpml:author>YOOX</wpml:author><wpml:createTime>" << timestamp << "</wpml:createTime>"
        << "<Folder><wpml:templateId>0</wpml:templateId><wpml:templateType>waypoint</wpml:templateType>"
        << "<wpml:autoFlightSpeed>" << mission.speed << "</wpml:autoFlightSpeed>"
        << "<wpml:globalHeight>" << mission.height << "</wpml:globalHeight>"
        << "<wpml:globalWaypointHeadingParam><wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>"
        << "<wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle></wpml:globalWaypointHeadingParam>"
        << "<wpml:waylineCoordinateSysParam><wpml:coordinateMode>WGS84</wpml:coordinateMode>"
        << "<wpml:heightMode>relativeToStartPoint</wpml:heightMode></wpml:waylineCoordinateSysParam>";
    for (std::size_t index = 0; index < mission.waypoints.size(); ++index) {
        const auto& point = mission.waypoints[index];
        out << "<Placemark><wpml:index>" << index << "</wpml:index>"
            << "<wpml:ellipsoidHeight>" << point.ellipsoid_height << "</wpml:ellipsoidHeight>"
            << "<wpml:height>" << mission.height << "</wpml:height>"
            << "<wpml:waypointSpeed>" << mission.speed << "</wpml:waypointSpeed>"
            << "<Point><coordinates>" << std::setprecision(15) << point.longitude << ','
            << point.latitude << "</coordinates></Point>";
        const auto action = mission.gimbal_pitch_actions.find(index);
        if (action != mission.gimbal_pitch_actions.end()) out << action_group(index, action->second);
        out << "</Placemark>";
    }
    out << "</Folder><wpml:missionConfig><wpml:name>" << xml_escape(mission.name) << "</wpml:name>"
        << "<wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode><wpml:finishAction>noAction</wpml:finishAction>"
        << "<wpml:exitOnRCLost>goContinue</wpml:exitOnRCLost>"
        << "<wpml:takeOffSecurityHeight>" << mission.takeoff_security_height << "</wpml:takeOffSecurityHeight>"
        << "<wpml:droneInfo><wpml:droneEnumValue>" << mission.drone_enum_value << "</wpml:droneEnumValue>"
        << "<wpml:droneSubEnumValue>0</wpml:droneSubEnumValue></wpml:droneInfo>"
        << "<wpml:payloadInfo><wpml:payloadEnumValue>" << mission.payload_enum_value << "</wpml:payloadEnumValue>"
        << "<wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue>"
        << "<wpml:payloadPositionIndex>0</wpml:payloadPositionIndex></wpml:payloadInfo>"
        << "</wpml:missionConfig><wpml:updateTime>" << timestamp
        << "</wpml:updateTime><wpml:version>2.0</wpml:version></Document></kml>";
    return out.str();
}

std::string Kmz::build_waylines(const KmzMission& mission) {
    validate_mission(mission);
    std::ostringstream out;
    out << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        << "<kml xmlns=\"" << kKmlNs << "\" xmlns:wpml=\"" << kWpmlNs << "\"><Document>"
        << "<wpml:author>YOOX</wpml:author><Folder><wpml:templateId>0</wpml:templateId>"
        << "<wpml:waylineId>0</wpml:waylineId><wpml:autoFlightSpeed>" << mission.speed
        << "</wpml:autoFlightSpeed><wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>";
    for (std::size_t index = 0; index < mission.waypoints.size(); ++index) {
        const auto& point = mission.waypoints[index];
        out << "<Placemark><wpml:index>" << index << "</wpml:index>"
            << "<wpml:executeHeight>" << mission.height << "</wpml:executeHeight>"
            << "<wpml:waypointSpeed>" << mission.speed << "</wpml:waypointSpeed>"
            << "<wpml:waypointHeadingParam><wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>"
            << "<wpml:waypointHeadingAngle>0</wpml:waypointHeadingAngle></wpml:waypointHeadingParam>"
            << "<wpml:waypointTurnParam><wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature"
            << "</wpml:waypointTurnMode><wpml:waypointTurnDampingDist>0</wpml:waypointTurnDampingDist>"
            << "</wpml:waypointTurnParam><Point><coordinates>" << std::setprecision(15)
            << point.longitude << ',' << point.latitude << "</coordinates></Point>";
        const auto action = mission.gimbal_pitch_actions.find(index);
        if (action != mission.gimbal_pitch_actions.end()) out << action_group(index, action->second);
        out << "</Placemark>";
    }
    out << "</Folder><wpml:missionConfig><wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>"
        << "<wpml:finishAction>noAction</wpml:finishAction><wpml:exitOnRCLost>goContinue</wpml:exitOnRCLost>"
        << "<wpml:takeOffSecurityHeight>" << mission.takeoff_security_height << "</wpml:takeOffSecurityHeight>"
        << "<wpml:droneInfo><wpml:droneEnumValue>" << mission.drone_enum_value
        << "</wpml:droneEnumValue><wpml:droneSubEnumValue>0</wpml:droneSubEnumValue></wpml:droneInfo>"
        << "<wpml:payloadInfo><wpml:payloadEnumValue>" << mission.payload_enum_value
        << "</wpml:payloadEnumValue><wpml:payloadSubEnumValue>0</wpml:payloadSubEnumValue>"
        << "<wpml:payloadPositionIndex>0</wpml:payloadPositionIndex></wpml:payloadInfo>"
        << "</wpml:missionConfig><wpml:version>2.0</wpml:version></Document></kml>";
    return out.str();
}

void Kmz::generate(const std::filesystem::path& output, const KmzMission& mission) {
    validate_mission(mission);
    std::filesystem::create_directories(output.parent_path());
    int error = 0;
    const auto output_utf8 = output.u8string();
    zip_t* archive = zip_open(output_utf8.c_str(), ZIP_CREATE | ZIP_TRUNCATE, &error);
    if (!archive) throw std::runtime_error("unable to create KMZ: libzip error " + std::to_string(error));
    try {
        add_file(archive, "wpmz/template.kml", build_template(mission));
        add_file(archive, "wpmz/waylines.wpml", build_waylines(mission));
        if (zip_close(archive) != 0) throw std::runtime_error("unable to finalise KMZ");
    } catch (...) {
        zip_discard(archive);
        throw;
    }
}

KmzValidation Kmz::validate(const std::filesystem::path& path) {
    KmzValidation result;
    int error = 0;
    const auto path_utf8 = path.u8string();
    zip_t* archive = zip_open(path_utf8.c_str(), ZIP_RDONLY, &error);
    if (!archive) {
        result.errors.push_back("cannot open ZIP/KMZ");
        return result;
    }
    const auto template_xml = read_zip_entry(archive, "wpmz/template.kml");
    const auto waylines_xml = read_zip_entry(archive, "wpmz/waylines.wpml");
    zip_close(archive);
    if (template_xml.empty()) result.errors.push_back("missing wpmz/template.kml");
    if (waylines_xml.empty()) result.errors.push_back("missing wpmz/waylines.wpml");
    for (const auto& [name, content] : std::vector<std::pair<std::string, std::string>>{
             {"template.kml", template_xml}, {"waylines.wpml", waylines_xml}}) {
        if (content.empty()) continue;
        if (content.rfind("<?xml version=\"1.0\" encoding=\"UTF-8\"?>", 0) != 0)
            result.errors.push_back(name + " is not explicitly UTF-8 XML");
        if (content.find(kKmlNs) == std::string::npos || content.find(kWpmlNs) == std::string::npos)
            result.errors.push_back(name + " is missing required namespaces");
        if (content.find("<wpml:missionConfig>") == std::string::npos)
            result.errors.push_back(name + " is missing missionConfig");
    }
    result.valid = result.errors.empty();
    return result;
}

}  // namespace yoox

