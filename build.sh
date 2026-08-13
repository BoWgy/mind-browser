#!/usr/bin/env bash
# 构建脚本：自动加载便携工具链并生成 local.properties
# 用法：./build.sh assembleDebug   （或任何 gradle 任务）
set -e
cd "$(dirname "$0")"
source ../tools/env.sh
echo "sdk.dir=D:\\\\Mind\\\\code\\\\tools\\\\android-sdk" > local.properties
exec ./gradlew "$@"
