#!/bin/sh
# Generates YouTube-like test streams (no real site needed) for check_pipeline.py.
set -e
cd "$(dirname "$0")"
mkdir -p media && cd media
F="ffmpeg -hide_banner -loglevel error -y"
$F -f lavfi -i testsrc2=size=1920x1080:rate=30 -t 4 -c:v libx264 -pix_fmt yuv420p -an v1080_h264.mp4
$F -f lavfi -i testsrc2=size=1920x1080:rate=30 -t 4 -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -b:v 2M -an v1080_vp9.webm
$F -f lavfi -i testsrc2=size=3840x2160:rate=30 -t 2 -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -b:v 6M -an v2160_vp9.webm
$F -f lavfi -i testsrc2=size=1280x720:rate=30 -t 4 -c:v libx264 -pix_fmt yuv420p -an v720_h264.mp4
$F -f lavfi -i "sine=frequency=440:duration=4" -c:a aac -b:a 128k -vn a_aac.m4a
$F -f lavfi -i "sine=frequency=660:duration=4" -c:a libopus -b:a 160k -vn a_opus.webm
$F -f lavfi -i "testsrc=size=1280x720" -frames:v 1 thumb.jpg
$F -f lavfi -i testsrc2=size=640x360:rate=30 -f lavfi -i "sine=duration=4" -t 4 -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest muxed360.mp4
