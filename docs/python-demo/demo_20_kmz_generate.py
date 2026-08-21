"""离线生成 Autel wpmz 航线 KMZ 文件（不依赖服务器、MQTT 或设备）。

参考 ``新建任务-2026.08.20-145803.kmz`` 的结构重新构建：

- KMZ 本质是 ZIP 包，内部固定包含 ``wpmz/template.kml``（任务模板）与
  ``wpmz/waylines.wpml``（执行航线）两个 UTF-8 XML 文件；
- 默认生成与原文件完全一致的 5 个航点（天津示例区域）、18 m 相对高度、
  5 m/s 巡航速度，以及第 0 号航点的 gimbalRotate（俯仰 -44°）动作组；
- 生成的 KMZ 可直接交给 ``demo_17_wayline.py`` 上传，用于服务端
  ``waylines/file/upload`` 的离线造数与联调。

用法：

    ./run.sh demo_20_kmz_generate.py                       # 生成到 kmz/ 目录
    ./run.sh demo_20_kmz_generate.py --name 巡检航线-A
    ./run.sh demo_20_kmz_generate.py --gimbal-pitch -60    # 首点云台俯仰
    ./run.sh demo_20_kmz_generate.py --dry-run             # 只预览航点
"""
from __future__ import annotations

import argparse
import time
import zipfile
from dataclasses import dataclass
from pathlib import Path


# 默认航点与参考 KMZ 完全一致：(经度, 纬度, 椭球高)。
# 高度模式为 relativeToStartPoint，实际飞行高度以起飞点为基准取 height 字段。
DEFAULT_WAYPOINTS: tuple[tuple[float, float, float], ...] = (
    (117.72441031430895, 39.04183898359767, 11.620756),
    (117.72461532401013, 39.04167577449695, 11.621939),
    (117.72491771331835, 39.04172453823125, 11.623362),
    (117.72469604657908, 39.04194447220533, 11.622036),
    (117.72448334901588, 39.041896703798756, 11.62105),
)

WPML_NS = "http://www.autel.com/wpmz/1.0.0"
KML_NS = "http://www.opengis.net/kml/2.2"
XML_DECL = '<?xml version="1.0" encoding="UTF-8"?>'

# 机型/负载枚举与参考文件保持一致（droneEnumValue=67、payloadEnumValue=806）。
DRONE_ENUM_VALUE = 67
DRONE_SUB_ENUM_VALUE = 0
PAYLOAD_ENUM_VALUE = 806
PAYLOAD_SUB_ENUM_VALUE = 0
PAYLOAD_POSITION_INDEX = 0


@dataclass(frozen=True)
class Waypoint:
    """单个航点。ellipsoid_height 仅 template.kml 使用。"""

    longitude: float
    latitude: float
    ellipsoid_height: float


def build_action_group_reach_point(index: int, gimbal_pitch: float) -> str:
    """到达 index 号航点触发的云台绝对角度旋转动作组。"""
    return f"""        <wpml:actionGroup>
          <wpml:actionGroupEndIndex>{index}</wpml:actionGroupEndIndex>
          <wpml:actionGroupId>0</wpml:actionGroupId>
          <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
          <wpml:actionGroupStartIndex>{index}</wpml:actionGroupStartIndex>
          <wpml:actionTrigger>
            <wpml:actionTriggerType>reachPoint</wpml:actionTriggerType>
          </wpml:actionTrigger>
          <wpml:action>
            <wpml:actionActuatorFunc>gimbalRotate</wpml:actionActuatorFunc>
            <wpml:actionActuatorFuncParam>
              <wpml:gimbalPitchRotateAngle>{gimbal_pitch}</wpml:gimbalPitchRotateAngle>
              <wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>
              <wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable>
              <wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>
              <wpml:gimbalRotateTimeEnable>0</wpml:gimbalRotateTimeEnable>
            </wpml:actionActuatorFuncParam>
            <wpml:actionId>0</wpml:actionId>
          </wpml:action>
        </wpml:actionGroup>"""


def build_action_group_evenly_rotate(
    group_id: int, start_index: int, end_index: int, pitch_rotate_angle: float
) -> str:
    """相邻航点间均匀旋转云台的动作组（执行文件 wpml 使用）。

    ``pitch_rotate_angle`` 为段内俯仰旋转量；参考文件对每段都生成 0.0 的
    默认过渡组。
    """
    return f"""        <wpml:actionGroup>
          <wpml:actionGroupEndIndex>{end_index}</wpml:actionGroupEndIndex>
          <wpml:actionGroupId>{group_id}</wpml:actionGroupId>
          <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
          <wpml:actionGroupStartIndex>{start_index}</wpml:actionGroupStartIndex>
          <wpml:actionTrigger>
            <wpml:actionTriggerType>betweenAdjacentPoints</wpml:actionTriggerType>
          </wpml:actionTrigger>
          <wpml:action>
            <wpml:actionActuatorFunc>gimbalEvenlyRotate</wpml:actionActuatorFunc>
            <wpml:actionActuatorFuncParam>
              <wpml:gimbalPitchRotateAngle>{pitch_rotate_angle}</wpml:gimbalPitchRotateAngle>
            </wpml:actionActuatorFuncParam>
            <wpml:actionId>0</wpml:actionId>
          </wpml:action>
        </wpml:actionGroup>"""


def build_waypoint_placemark_template(
    index: int, wp: Waypoint, speed: float, height: float, actions: str = ""
) -> str:
    """template.kml 中的航点 Placemark，包含 interestingPointId 与 useGlobal*。"""
    action_block = f"{actions}\n" if actions else ""
    return f"""      <Placemark>
{action_block}        <droneHeadingControlValue>1</droneHeadingControlValue>
        <wpml:ellipsoidHeight>{wp.ellipsoid_height}</wpml:ellipsoidHeight>
        <wpml:gimbalPitchAngle>0.0</wpml:gimbalPitchAngle>
        <wpml:height>{height}</wpml:height>
        <wpml:index>{index}</wpml:index>
        <wpml:interestingPointId></wpml:interestingPointId>
        <Point>
          <coordinates>{wp.longitude},{wp.latitude}</coordinates>
        </Point>
        <wpml:useGlobalHeadingParam>1</wpml:useGlobalHeadingParam>
        <wpml:useGlobalHeight>1</wpml:useGlobalHeight>
        <wpml:useGlobalSpeed>1</wpml:useGlobalSpeed>
        <wpml:waypointHeadingParam>
          <wpml:waypointHeadingAngle>0.0</wpml:waypointHeadingAngle>
          <wpml:waypointHeadingAngleEnable>0</wpml:waypointHeadingAngleEnable>
          <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>
          <wpml:waypointHeadingPoiIndex>0</wpml:waypointHeadingPoiIndex>
        </wpml:waypointHeadingParam>
        <wpml:waypointSpeed>{speed}</wpml:waypointSpeed>
        <wpml:waypointTurnParam>
          <wpml:waypointTurnDampingDist>0.0</wpml:waypointTurnDampingDist>
          <wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode>
        </wpml:waypointTurnParam>
        <wpml:waypointType>0</wpml:waypointType>
      </Placemark>"""


def build_waypoint_placemark_wpml(
    index: int, wp: Waypoint, speed: float, height: float, actions: str = ""
) -> str:
    """waylines.wpml 中的航点 Placemark，不含 interestingPointId 与 useGlobal*。"""
    action_block = f"{actions}\n" if actions else ""
    return f"""      <Placemark>
{action_block}        <wpml:executeHeight>{height}</wpml:executeHeight>
        <wpml:index>{index}</wpml:index>
        <Point>
          <coordinates>{wp.longitude},{wp.latitude}</coordinates>
        </Point>
        <wpml:waypointHeadingParam>
          <wpml:waypointHeadingAngle>0.0</wpml:waypointHeadingAngle>
          <wpml:waypointHeadingAngleEnable>0</wpml:waypointHeadingAngleEnable>
          <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>
          <wpml:waypointHeadingPoiIndex>0</wpml:waypointHeadingPoiIndex>
        </wpml:waypointHeadingParam>
        <wpml:waypointSpeed>{speed}</wpml:waypointSpeed>
        <wpml:waypointTurnParam>
          <wpml:waypointTurnDampingDist>0.0</wpml:waypointTurnDampingDist>
          <wpml:waypointTurnMode>toPointAndStopWithDiscontinuityCurvature</wpml:waypointTurnMode>
        </wpml:waypointTurnParam>
        <wpml:waypointType>0</wpml:waypointType>
      </Placemark>"""


def build_template_kml(
    waypoints: tuple[Waypoint, ...],
    *,
    mission_name: str,
    height: float,
    speed: float,
    gimbal_actions: dict[int, float] | None = None,
    takeoff_security_height: float,
    create_ms: int,
    update_ms: int,
) -> str:
    """构建 wpmz/template.kml（任务模板文件）。

    ``gimbal_actions``：航点索引 -> 云台俯仰绝对角度的映射；命中的航点
    挂载到达触发的 gimbalRotate 动作组（参考文件仅首航点设置）。
    """
    actions_map = gimbal_actions or {}
    placemarks: list[str] = []
    for index, wp in enumerate(waypoints):
        actions = (
            build_action_group_reach_point(index, actions_map[index])
            if index in actions_map
            else ""
        )
        placemarks.append(
            build_waypoint_placemark_template(index, wp, speed, height, actions)
        )

    return f"""{XML_DECL}
<kml xmlns:wpml="{WPML_NS}" xmlns="{KML_NS}">
  <Document>
    <wpml:author>Autel</wpml:author>
    <wpml:createTime>{create_ms}</wpml:createTime>
    <Folder>
      <wpml:autoFlightSpeed>{speed}</wpml:autoFlightSpeed>
      <wpml:gimbalPitch>0.0</wpml:gimbalPitch>
      <wpml:gimbalPitchMode>usePointSetting</wpml:gimbalPitchMode>
      <wpml:gimbalYaw>0</wpml:gimbalYaw>
      <wpml:globalHeight>{height}</wpml:globalHeight>
      <wpml:globalWaypointHeadingParam>
        <wpml:waypointHeadingAngle>0.0</wpml:waypointHeadingAngle>
        <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>
      </wpml:globalWaypointHeadingParam>
      <wpml:height>{height}</wpml:height>
      <wpml:obstacleMode>2</wpml:obstacleMode>
      <wpml:payloadParam>
        <wpml:imageFormat>zoom</wpml:imageFormat>
      </wpml:payloadParam>
{chr(10).join(placemarks)}
      <wpml:templateId>0</wpml:templateId>
      <wpml:templateType>waypoint</wpml:templateType>
      <wpml:waylineCoordinateSysParam>
        <wpml:coordinateMode>WGS84</wpml:coordinateMode>
        <wpml:heightMode>relativeToStartPoint</wpml:heightMode>
      </wpml:waylineCoordinateSysParam>
    </Folder>
    <wpml:missionConfig>
      <wpml:altitudeType>0</wpml:altitudeType>
      <wpml:droneInfo>
        <wpml:droneEnumValue>{DRONE_ENUM_VALUE}</wpml:droneEnumValue>
        <wpml:droneSubEnumValue>{DRONE_SUB_ENUM_VALUE}</wpml:droneSubEnumValue>
      </wpml:droneInfo>
      <wpml:estimateFlyLength>0.0</wpml:estimateFlyLength>
      <wpml:estimateFlyTime>0</wpml:estimateFlyTime>
      <wpml:executeRCLostAction>goContinue</wpml:executeRCLostAction>
      <wpml:exitOnRCLost>goContinue</wpml:exitOnRCLost>
      <wpml:finishAction>noAction</wpml:finishAction>
      <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>
      <wpml:globalTransitionalSpeed>{speed}</wpml:globalTransitionalSpeed>
      <wpml:homePoint>
        <altitude>0.0</altitude>
        <height>0.0</height>
        <latitude>0.0</latitude>
        <longitude>0.0</longitude>
      </wpml:homePoint>
      <wpml:name>{mission_name}</wpml:name>
      <wpml:payloadInfo>
        <wpml:payloadEnumValue>{PAYLOAD_ENUM_VALUE}</wpml:payloadEnumValue>
        <wpml:payloadPositionIndex>{PAYLOAD_POSITION_INDEX}</wpml:payloadPositionIndex>
        <wpml:payloadSubEnumValue>{PAYLOAD_SUB_ENUM_VALUE}</wpml:payloadSubEnumValue>
      </wpml:payloadInfo>
      <wpml:takeOffSecurityHeight>{takeoff_security_height}</wpml:takeOffSecurityHeight>
    </wpml:missionConfig>
    <wpml:updateTime>{update_ms}</wpml:updateTime>
    <wpml:version>2.0</wpml:version>
  </Document>
</kml>"""


def build_waylines_wpml(
    waypoints: tuple[Waypoint, ...],
    *,
    height: float,
    speed: float,
    gimbal_actions: dict[int, float] | None = None,
    evenly_rotate_groups: tuple[tuple[int, int, float], ...] = (),
    takeoff_security_height: float,
) -> str:
    """构建 wpmz/waylines.wpml（执行航线文件）。

    ``gimbal_actions``：航点索引 -> 云台俯仰绝对角度的映射，命中航点挂载
    到达触发的 gimbalRotate 动作组（组 ID 固定 0）。
    ``evenly_rotate_groups``：显式的均匀旋转过渡组 ``(起点, 终点, 旋转量)``；
    若挂载点已存在 reachPoint 动作组，过渡组 ID 取 1（参考文件约定），否则取 0。
    """
    actions_map = gimbal_actions or {}
    groups_by_start: dict[int, list[tuple[int, int, float]]] = {}
    for start, end, angle in evenly_rotate_groups:
        groups_by_start.setdefault(start, []).append((start, end, angle))

    placemarks: list[str] = []
    for index, wp in enumerate(waypoints):
        action_parts: list[str] = []
        has_reach_group = index in actions_map
        if has_reach_group:
            action_parts.append(build_action_group_reach_point(index, actions_map[index]))
        for start, end, angle in groups_by_start.get(index, ()):  # noqa: B007
            group_id = 1 if has_reach_group else 0
            action_parts.append(
                build_action_group_evenly_rotate(group_id, start, end, angle)
            )
        actions = "\n".join(action_parts)
        placemarks.append(
            build_waypoint_placemark_wpml(index, wp, speed, height, actions)
        )

    return f"""{XML_DECL}
<kml xmlns:wpml="{WPML_NS}" xmlns="{KML_NS}">
  <Document>
    <wpml:author>Autel</wpml:author>
    <Folder>
      <wpml:autoFlightSpeed>{speed}</wpml:autoFlightSpeed>
      <wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>
      <wpml:gimbalPitch>0.0</wpml:gimbalPitch>
      <wpml:gimbalYaw>0</wpml:gimbalYaw>
{chr(10).join(placemarks)}
      <wpml:templateId>0</wpml:templateId>
      <wpml:waylineId>0</wpml:waylineId>
    </Folder>
    <wpml:missionConfig>
      <wpml:droneInfo>
        <wpml:droneEnumValue>{DRONE_ENUM_VALUE}</wpml:droneEnumValue>
        <wpml:droneSubEnumValue>{DRONE_SUB_ENUM_VALUE}</wpml:droneSubEnumValue>
      </wpml:droneInfo>
      <wpml:exitOnRCLost>goContinue</wpml:exitOnRCLost>
      <wpml:finishAction>noAction</wpml:finishAction>
      <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>
      <wpml:globalTransitionalSpeed>{speed}</wpml:globalTransitionalSpeed>
      <wpml:obstacleMode>avoid</wpml:obstacleMode>
      <wpml:payloadInfo>
        <wpml:payloadEnumValue>{PAYLOAD_ENUM_VALUE}</wpml:payloadEnumValue>
        <wpml:payloadPositionIndex>{PAYLOAD_POSITION_INDEX}</wpml:payloadPositionIndex>
        <wpml:payloadSubEnumValue>{PAYLOAD_SUB_ENUM_VALUE}</wpml:payloadSubEnumValue>
      </wpml:payloadInfo>
      <wpml:takeOffSecurityHeight>{takeoff_security_height}</wpml:takeOffSecurityHeight>
    </wpml:missionConfig>
    <wpml:version>2.0</wpml:version>
  </Document>
</kml>"""


def default_evenly_rotate_groups(
    waypoints: tuple[Waypoint, ...]
) -> tuple[tuple[int, int, float], ...]:
    """参考文件约定：每个相邻线段都生成旋转量 0.0 的默认过渡组。"""
    return tuple((i, i + 1, 0.0) for i in range(len(waypoints) - 1))


def write_kmz(output_path: Path, template_kml: str, waylines_wpml: str) -> None:
    """按 KMZ 约定打包：ZIP 内部固定为 wpmz/template.kml 与 wpmz/waylines.wpml。"""
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("wpmz/template.kml", template_kml.encode("utf-8"))
        zf.writestr("wpmz/waylines.wpml", waylines_wpml.encode("utf-8"))


def default_mission_name() -> str:
    """生成与 Web 控制台一致的默认任务名：新建任务-年.月.日-时:分:秒。"""
    return f"新建任务-{time.strftime('%Y.%m.%d-%H:%M:%S')}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="离线生成与参考文件结构相同的 Autel wpmz 航线 KMZ",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--output-dir",
        default=str(Path(__file__).resolve().parent / "kmz"),
        help="KMZ 输出目录",
    )
    parser.add_argument(
        "--name",
        default=None,
        help="任务名称（默认自动生成：新建任务-年.月.日-时:分:秒）",
    )
    parser.add_argument("--height", type=float, default=18.0, help="航线执行高度（米）")
    parser.add_argument("--speed", type=float, default=5.0, help="自动飞行速度（米/秒）")
    parser.add_argument(
        "--gimbal-pitch",
        type=float,
        default=-44.0,
        help="首航点云台俯仰绝对角度（度）",
    )
    parser.add_argument(
        "--takeoff-security-height",
        type=float,
        default=15.0,
        help="起飞安全高度（米）",
    )
    parser.add_argument(
        "--filename",
        default=None,
        help="输出文件名；默认按任务名生成并追加 .kmz 后缀",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="只打印航点与参数，不写文件",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    mission_name = args.name or default_mission_name()
    waypoints = tuple(
        Waypoint(longitude=lng, latitude=lat, ellipsoid_height=ell)
        for lng, lat, ell in DEFAULT_WAYPOINTS
    )

    print(f"[demo_20] 任务名称: {mission_name}")
    print(f"[demo_20] 航点数量: {len(waypoints)}")
    for index, wp in enumerate(waypoints):
        print(
            f"  #{index} 经度={wp.longitude:.14f} 纬度={wp.latitude:.14f} "
            f"椭球高={wp.ellipsoid_height}"
        )
    print(
        f"[demo_20] 高度={args.height} 米，速度={args.speed} 米/秒，"
        f"首点云台俯仰={args.gimbal_pitch}°，起飞安全高度={args.takeoff_security_height} 米"
    )

    if args.dry_run:
        print("[demo_20] --dry-run：跳过文件写入")
        return 0

    now_ms = int(time.time() * 1000)
    # 与参考文件一致：只有首航点挂云台动作，每个线段挂 0.0 默认过渡组。
    gimbal_actions = {0: args.gimbal_pitch}
    template_kml = build_template_kml(
        waypoints,
        mission_name=mission_name,
        height=args.height,
        speed=args.speed,
        gimbal_actions=gimbal_actions,
        takeoff_security_height=args.takeoff_security_height,
        create_ms=now_ms,
        update_ms=now_ms,
    )
    waylines_wpml = build_waylines_wpml(
        waypoints,
        height=args.height,
        speed=args.speed,
        gimbal_actions=gimbal_actions,
        evenly_rotate_groups=default_evenly_rotate_groups(waypoints),
        takeoff_security_height=args.takeoff_security_height,
    )

    filename = args.filename or f"{mission_name}.kmz"
    if not filename.endswith(".kmz"):
        filename += ".kmz"
    output_path = Path(args.output_dir) / filename
    write_kmz(output_path, template_kml, waylines_wpml)

    print(f"[demo_20] 已生成: {output_path}")
    print("[demo_20] 可配合 demo_17_wayline.py 上传该文件执行航线任务")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
