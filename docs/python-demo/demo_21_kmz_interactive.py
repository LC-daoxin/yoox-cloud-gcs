"""交互式航线编辑：先设航线信息 → 逐点设置航点（可加云台动作）→ 生成 KMZ。

流程与 Web 控制台建任务一致，但完全离线：

1. **设置航线信息**：任务名、飞行高度（相对起飞点）、飞行速度、起飞安全
   高度、默认椭球高；
2. **逐点设置航点**：每个航点输入经度/纬度，并可选是否设置到达动作
   （云台俯仰绝对角度，如 -44° 表示俯仰向下 44°）；
3. **生成 ``.kmz``**：输出到 ``kmz/`` 目录，结构与参考文件逐节点一致，
   可直接交给 ``demo_17_wayline.py`` 上传。

底层复用 ``demo_20_kmz_generate.py`` 的 wpmz XML 构建函数，保证上传兼容。

用法：

    ./run.sh demo_21_kmz_interactive.py              # 全程交互
    ./run.sh demo_21_kmz_interactive.py --name 巡检-A
"""
from __future__ import annotations

import argparse
import time
from pathlib import Path

from demo_20_kmz_generate import (
    Waypoint,
    build_template_kml,
    build_waylines_wpml,
    default_evenly_rotate_groups,
    default_mission_name,
    write_kmz,
)

# 各字段边界（与 fly_to_point 校验口径一致）。
LAT_RANGE = (-90.0, 90.0)
LON_RANGE = (-180.0, 180.0)
HEIGHT_RANGE = (0.0, 1500.0)
SPEED_RANGE = (1.0, 15.0)
GIMBAL_PITCH_RANGE = (-90.0, 30.0)


class AbortEdit(Exception):
    """操作者主动放弃（q 退出或 Ctrl+C 后确认）。"""


def read_float(
    label: str,
    *,
    default: float | None = None,
    min_value: float | None = None,
    max_value: float | None = None,
) -> float:
    """循环读取一个浮点数；支持默认值与范围校验。"""
    while True:
        parts: list[str] = []
        if min_value is not None and max_value is not None:
            parts.append(f"[{min_value:g}~{max_value:g}]")
        if default is not None:
            parts.append(f"默认 {default:g}")
        hint = f" {', '.join(parts)}" if parts else ""
        raw = input(f"  {label}{hint}: ").strip()
        if not raw:
            if default is not None:
                return float(default)
            print("  [!] 必填，不能为空")
            continue
        try:
            value = float(raw)
        except ValueError:
            print("  [!] 无法解析为数字，请重新输入")
            continue
        if min_value is not None and value < min_value:
            print(f"  [!] 不能小于 {min_value:g}")
            continue
        if max_value is not None and value > max_value:
            print(f"  [!] 不能大于 {max_value:g}")
            continue
        return value


def read_coordinates() -> tuple[float, float]:
    """读取经纬度，支持一次粘贴 `经度,纬度`。"""
    while True:
        raw = input("  经度,纬度 (WGS84，如 117.7244,39.0418): ").strip()
        if not raw:
            print("  [!] 必填，不能为空")
            continue
        parts = [part.strip() for part in raw.replace("，", ",").split(",")]
        if len(parts) != 2:
            print("  [!] 格式应为 `经度,纬度`，请重新输入")
            continue
        try:
            lon, lat = float(parts[0]), float(parts[1])
        except ValueError:
            print("  [!] 经纬度无法解析为数字，请重新输入")
            continue
        if not LON_RANGE[0] <= lon <= LON_RANGE[1]:
            print(f"  [!] 经度必须在 {LON_RANGE[0]:g}~{LON_RANGE[1]:g}")
            continue
        if not LAT_RANGE[0] <= lat <= LAT_RANGE[1]:
            print(f"  [!] 纬度必须在 {LAT_RANGE[0]:g}~{LAT_RANGE[1]:g}")
            continue
        return lon, lat


def confirm(prompt: str, *, default: bool = False) -> bool:
    suffix = "[Y/n]" if default else "[y/N]"
    raw = input(f"  {prompt} {suffix}: ").strip().lower()
    if not raw:
        return default
    return raw in {"y", "yes", "是"}


def prompt_wayline_config(args: argparse.Namespace) -> dict:
    """步骤 1：设置航线全局信息。"""
    print("[demo_21] 第 1 步：设置航线信息（直接回车使用默认值）")
    mission_name = args.name
    if not mission_name:
        auto_name = default_mission_name()
        raw = input(f"  任务名称 (默认 {auto_name}): ").strip()
        mission_name = raw or auto_name
    height = read_float(
        "航线飞行高度（米，相对起飞点）",
        default=args.height,
        min_value=HEIGHT_RANGE[0],
        max_value=HEIGHT_RANGE[1],
    )
    speed = read_float(
        "飞行速度（米/秒）",
        default=args.speed,
        min_value=SPEED_RANGE[0],
        max_value=SPEED_RANGE[1],
    )
    takeoff_security_height = read_float(
        "起飞安全高度（米）",
        default=args.takeoff_security_height,
        min_value=HEIGHT_RANGE[0],
        max_value=HEIGHT_RANGE[1],
    )
    ellipsoid_height = read_float(
        "默认椭球高（米，写入 template.kml，无实测值可用 0）",
        default=args.ellipsoid_height,
    )
    return {
        "mission_name": mission_name,
        "height": height,
        "speed": speed,
        "takeoff_security_height": takeoff_security_height,
        "ellipsoid_height": ellipsoid_height,
    }


def prompt_waypoint(index: int, default_ellipsoid: float) -> tuple[Waypoint, float | None]:
    """步骤 2：逐个输入一个航点，可选配云台到达动作。"""
    print(f"[demo_21] 设置航点 #{index}")
    lon, lat = read_coordinates()
    ell_raw = input(f"  椭球高（米，回车用默认 {default_ellipsoid:g}）: ").strip()
    ellipsoid = float(ell_raw) if ell_raw else default_ellipsoid

    gimbal_pitch: float | None = None
    if confirm("为该航点设置云台动作（到达后转到指定俯仰角）？"):
        gimbal_pitch = read_float(
            "云台俯仰角（度，负值朝下，如 -44）",
            default=-44.0,
            min_value=GIMBAL_PITCH_RANGE[0],
            max_value=GIMBAL_PITCH_RANGE[1],
        )
    return (
        Waypoint(longitude=lon, latitude=lat, ellipsoid_height=ellipsoid),
        gimbal_pitch,
    )


def render_waypoint(index: int, wp: Waypoint, gimbal_pitch: float | None) -> str:
    action = f"云台俯仰 {gimbal_pitch:g}°" if gimbal_pitch is not None else "无动作"
    return (
        f"#{index} ({wp.longitude:.7f}, {wp.latitude:.7f}) "
        f"椭球高 {wp.ellipsoid_height:g}m | {action}"
    )


def print_waypoint_summary(
    waypoints: list[Waypoint], gimbal_actions: dict[int, float]
) -> None:
    if not waypoints:
        print("[demo_21] 当前还没有航点")
        return
    print(f"[demo_21] 当前 {len(waypoints)} 个航点：")
    for index, wp in enumerate(waypoints):
        print(f"  {render_waypoint(index, wp, gimbal_actions.get(index))}")


def edit_waypoint_loop(default_ellipsoid: float) -> tuple[list[Waypoint], dict[int, float]]:
    """步骤 2：逐个添加/编辑航点，直到操作者确认完成。"""
    waypoints: list[Waypoint] = []
    gimbal_actions: dict[int, float] = {}

    while True:
        index = len(waypoints)
        wp, gimbal_pitch = prompt_waypoint(index, default_ellipsoid)
        waypoints.append(wp)
        if gimbal_pitch is not None:
            gimbal_actions[index] = gimbal_pitch
        print_waypoint_summary(waypoints, gimbal_actions)

        print("[demo_21] 操作：回车/a=继续加点，d=删除最后一点，f=完成并生成，q=放弃退出")
        command = input("> ").strip().lower()
        if command in {"", "a"}:
            continue
        if command == "d":
            removed = waypoints.pop()
            gimbal_actions.pop(len(waypoints), None)
            print(f"[demo_21] 已删除航点 #{len(waypoints)}: ({removed.longitude}, {removed.latitude})")
            print_waypoint_summary(waypoints, gimbal_actions)
            if not confirm("继续编辑？", default=True):
                break
            continue
        if command == "f":
            break
        if command == "q":
            raise AbortEdit
        print("[demo_21] 无法识别的操作，按回车继续添加航点")

    if len(waypoints) < 2:
        print("[demo_21] 至少需要 2 个航点才能构成航线")
        raise AbortEdit
    return waypoints, gimbal_actions


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="交互式设置航线与航点，生成 Autel wpmz 航线 KMZ",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument(
        "--output-dir",
        default=str(Path(__file__).resolve().parent / "kmz"),
        help="KMZ 输出目录",
    )
    parser.add_argument("--name", default=None, help="任务名称（交互中也可修改）")
    parser.add_argument("--height", type=float, default=18.0, help="航线飞行高度默认值（米）")
    parser.add_argument("--speed", type=float, default=5.0, help="飞行速度默认值（米/秒）")
    parser.add_argument(
        "--takeoff-security-height",
        type=float,
        default=15.0,
        help="起飞安全高度默认值（米）",
    )
    parser.add_argument(
        "--ellipsoid-height",
        type=float,
        default=0.0,
        help="默认椭球高（米），仅写入 template.kml",
    )
    parser.add_argument(
        "--filename",
        default=None,
        help="输出文件名；默认按任务名生成并追加 .kmz 后缀",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    print("[demo_21] 交互式航线编辑（Ctrl+C 可随时中断）")

    config = prompt_wayline_config(args)
    print(f"[demo_21] 航线信息：{config['mission_name']}，高度 {config['height']:g} 米，"
          f"速度 {config['speed']:g} 米/秒，起飞安全高度 {config['takeoff_security_height']:g} 米")

    print("[demo_21] 第 2 步：逐个设置航点")
    waypoints, gimbal_actions = edit_waypoint_loop(config["ellipsoid_height"])

    print("[demo_21] 第 3 步：确认并生成 KMZ")
    print_waypoint_summary(waypoints, gimbal_actions)
    if not confirm("确认生成 KMZ？", default=True):
        print("[demo_21] 已取消，未写入文件")
        return 1

    now_ms = int(time.time() * 1000)
    waypoint_tuple = tuple(waypoints)
    template_kml = build_template_kml(
        waypoint_tuple,
        mission_name=config["mission_name"],
        height=config["height"],
        speed=config["speed"],
        gimbal_actions=gimbal_actions,
        takeoff_security_height=config["takeoff_security_height"],
        create_ms=now_ms,
        update_ms=now_ms,
    )
    waylines_wpml = build_waylines_wpml(
        waypoint_tuple,
        height=config["height"],
        speed=config["speed"],
        gimbal_actions=gimbal_actions,
        evenly_rotate_groups=default_evenly_rotate_groups(waypoint_tuple),
        takeoff_security_height=config["takeoff_security_height"],
    )

    filename = args.filename or f"{config['mission_name']}.kmz"
    if not filename.endswith(".kmz"):
        filename += ".kmz"
    output_path = Path(args.output_dir) / filename
    write_kmz(output_path, template_kml, waylines_wpml)

    print(f"[demo_21] 已生成: {output_path}")
    print("[demo_21] 可配合 demo_17_wayline.py 上传该文件执行航线任务")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AbortEdit:
        print("[demo_21] 已退出，未生成文件")
        raise SystemExit(1)
    except KeyboardInterrupt:
        print("\n[demo_21] 中断，未生成文件")
        raise SystemExit(130)
