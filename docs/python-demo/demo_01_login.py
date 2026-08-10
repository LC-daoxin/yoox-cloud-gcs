"""验证 Web/Pilot 登录与服务端返回的工作空间、MQTT 配置。"""
from __future__ import annotations

from config import (
    BASE_URL,
    PILOT_BASE_URL,
    PILOT_FLAG,
    PILOT_PASSWORD,
    PILOT_USERNAME,
    WEB_FLAG,
    WEB_PASSWORD,
    WEB_USERNAME,
    WS_URL,
)
from demo_common import (
    DemoError,
    is_placeholder,
    login_account,
    print_error_and_hint,
)


def verify_login(username: str, password: str, flag: int, label: str) -> bool:
    login_base_url = PILOT_BASE_URL if flag == PILOT_FLAG else BASE_URL
    print(f"\n[*] {label}: {login_base_url}/manage/api/v1/login 账号={username} flag={flag}")
    try:
        data = login_account(username, password, flag, f"{label}登录")
    except DemoError as exc:
        print_error_and_hint(exc)
        return False
    token = str(data.get("access_token") or "")
    if not token:
        print("[✗] 登录响应缺少 access_token")
        return False
    # 只显示短指纹，避免终端录屏/日志泄露完整凭证。
    print(f"[✓] 登录成功，token 指纹={token[:10]}…（完整 token 不打印）")
    print(f"    workspace_id: {data.get('workspace_id')}")
    print(f"    mqtt_addr   : {data.get('mqtt_addr') or '(未返回)'}")
    print(f"    websocket   : {WS_URL}?x-auth-token=<token>")
    if flag == 2:
        print("    Pilot 密码与 MQTT 密码请从本机 .env/登录响应安全读取，本脚本不回显。")
    return True


def main() -> int:
    web_ok = verify_login(WEB_USERNAME, WEB_PASSWORD, WEB_FLAG, "Web 端")
    if is_placeholder(PILOT_PASSWORD):
        print("\n[*] 未配置 YOOX_PILOT_PASSWORD，跳过 Pilot/App 登录验证")
        pilot_ok = True
    else:
        pilot_ok = verify_login(
            PILOT_USERNAME, PILOT_PASSWORD, PILOT_FLAG, "Pilot/App 端"
        )
    return 0 if web_ok and pilot_ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
