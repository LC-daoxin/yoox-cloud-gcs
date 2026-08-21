#!/bin/bash
# 在 Jetson Nano 本机显示器上用 V4L2 硬解播放 mediamtx 直播流
export DISPLAY=:0
export XAUTHORITY=/home/jetson/.Xauthority
pkill -f gst-launch-1.0 2>/dev/null
sleep 1
# nvv4l2decoder = Nano V4L2 硬件解码；nveglglessink = EGL 硬显输出（零拷贝）
exec gst-launch-1.0 -v \
  rtspsrc location=rtsp://127.0.0.1:8554/1748FEV3HMP926231388-10052-0-0 latency=0 drop-on-latency=true \
  ! application/x-rtp,media=video ! rtph264depay ! h264parse \
  ! nvv4l2decoder ! nveglglessink sync=false
