#!/bin/bash
# 用法: ./sign_apk.sh [-i input_apk] [-o output_apk] [-k keystore] [-a alias] [-p password]

# 默认值
INPUT_APK=""
OUTPUT_APK=""
KEYSTORE="$HOME/.android/debug.keystore"
KEY_ALIAS="androiddebugkey"
KEY_PASS="android"

# 解析命令行参数
while getopts "i:o:k:a:p:h" opt; do
    case $opt in
        i) INPUT_APK="$OPTARG" ;;
        o) OUTPUT_APK="$OPTARG" ;;
        k) KEYSTORE="$OPTARG" ;;
        a) KEY_ALIAS="$OPTARG" ;;
        p) KEY_PASS="$OPTARG" ;;
        h) 
            echo "用法: $0 [-i 输入apk] [-o 输出apk] [-k 密钥库] [-a 别名] [-p 密码]"
            echo "示例:"
            echo "  $0 -i app.apk                          # 签名 app.apk"
            echo "  $0 -i app.apk -o app-signed.apk        # 签名并重命名"
            echo "  $0 -i app.apk -k release.jks -a mykey  # 使用自定义密钥"
            exit 0
            ;;
        \?) echo "无效选项: -$OPTARG" >&2; exit 1 ;;
    esac
done

# 如果没有指定输入APK，尝试使用默认值
if [ -z "$INPUT_APK" ]; then
    echo "错误: 未指定APK文件"
    echo "用法: $0 [-i input_apk]"
    exit 1
fi

# 检查输入文件
if [ ! -f "$INPUT_APK" ]; then
    echo "错误: 输入文件不存在: $INPUT_APK"
    exit 1
fi

# 如果未指定输出文件，则覆盖原文件
if [ -z "$OUTPUT_APK" ]; then
    OUTPUT_APK="$INPUT_APK"
    echo "提示: 将覆盖原文件: $INPUT_APK"
fi

# 检查密钥库
if [ ! -f "$KEYSTORE" ]; then
    echo "错误: 密钥库文件不存在: $KEYSTORE"
    exit 1
fi

# 查找最新版本的 apksigner
APKSIGNER=""
if [ -n "$ANDROID_HOME" ]; then
    # 查找 build-tools 目录下最新的 apksigner
    BUILD_TOOLS_DIR="$ANDROID_HOME/build-tools"
    if [ -d "$BUILD_TOOLS_DIR" ]; then
        LATEST_VERSION=$(ls -1v "$BUILD_TOOLS_DIR" | grep -E '^[0-9]+\.[0-9]+\.[0-9]+' | tail -1)
        if [ -n "$LATEST_VERSION" ]; then
            APKSIGNER="$BUILD_TOOLS_DIR/$LATEST_VERSION/apksigner"
        fi
    fi
fi

# 如果没找到，尝试使用 34.0.0
if [ -z "$APKSIGNER" ] || [ ! -f "$APKSIGNER" ]; then
    APKSIGNER="$ANDROID_HOME/build-tools/34.0.0/apksigner"
fi

# 检查 apksigner
if [ ! -f "$APKSIGNER" ]; then
    echo "错误: 找不到 apksigner，请检查 ANDROID_HOME 环境变量"
    echo "尝试查找位置: $APKSIGNER"
    exit 1
fi

echo "========================================"
echo "APK 签名工具"
echo "========================================"
echo "输入文件: $INPUT_APK"
echo "输出文件: $OUTPUT_APK"
echo "密钥库: $KEYSTORE"
echo "别名: $KEY_ALIAS"
echo "apksigner: $APKSIGNER"
echo "========================================"

# 如果输出文件与输入文件不同，先复制文件
if [ "$INPUT_APK" != "$OUTPUT_APK" ]; then
    echo "复制文件到: $OUTPUT_APK"
    cp "$INPUT_APK" "$OUTPUT_APK"
fi

# 执行签名
echo "开始签名..."
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASS" \
    "$OUTPUT_APK"

# 检查结果
if [ $? -eq 0 ]; then
    echo "✅ APK 签名成功: $OUTPUT_APK"
    
    # 验证签名
    echo "验证签名..."
    "$APKSIGNER" verify --print-certs "$OUTPUT_APK"
else
    echo "❌ APK 签名失败"
    exit 1
fi