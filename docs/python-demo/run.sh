#!/usr/bin/env bash
# 快捷运行脚本，自动激活虚拟环境
# 用法：./run.sh demo_01_login.py
#       ./run.sh demo_07_camera.py photo

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENV="$SCRIPT_DIR/.venv"
ENV_FILE="$SCRIPT_DIR/.env"

# .env 只保存在本机且已被 Git 忽略。导出后 Python 的 config.py 统一读取。
if [ -f "$ENV_FILE" ]; then
    set -a
    # shellcheck disable=SC1090
    . "$ENV_FILE"
    set +a
fi

# 首次运行时自动建虚拟环境并装依赖
if [ ! -x "$VENV/bin/python3" ]; then
    echo "[*] 初始化虚拟环境..."
    python3 -m venv "$VENV"
fi

# 每次让 pip 快速核对声明；已有兼容版本时不会重复下载。
"$VENV/bin/python3" -m pip install -q -r "$SCRIPT_DIR/requirements.txt"
echo "[✓] Python 环境就绪"

if [ "$#" -eq 0 ]; then
    echo "用法: ./run.sh <demo文件名> [参数]"
    echo ""
    echo "可用 demo："
    ls "$SCRIPT_DIR"/demo_*.py | xargs -n1 basename
    exit 0
fi

DEMO_NAME="$1"
shift
case "$DEMO_NAME" in
    demo_*.py) ;;
    *)
        echo "[✗] 只允许运行本目录中的 demo_*.py"
        exit 2
        ;;
esac
if [ ! -f "$SCRIPT_DIR/$DEMO_NAME" ]; then
    echo "[✗] Demo 不存在: $DEMO_NAME"
    exit 2
fi

exec "$VENV/bin/python3" "$SCRIPT_DIR/$DEMO_NAME" "$@"
