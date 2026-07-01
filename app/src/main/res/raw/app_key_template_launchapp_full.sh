#!/system/bin/sh

# ------------------- Vars -------------------

# 【必需】包名
PACKAGE_NAME="com.example.app"

# 【必需】Activity 完整路径（包名/Activity名）
ACTIVITY_NAME="com.example.app.MainActivity"

# 【可选】Action
ACTION="android.intent.action.VIEW"

# 【可选】Data URI（要打开的链接或内容URI）
DATA_URI="https://www.example.com"

# 【可选】MIME Type（数据类型）
MIME_TYPE="text/plain"

# 【可选】Category
CATEGORY="android.intent.category.DEFAULT"

# 【可选】Extra 参数（键值对，多个用空格分隔）
# 字符串: --es KEY VALUE
# 布尔: --ez KEY true/false
# 整数: --ei KEY 123
# 长整: --el KEY 123456789
EXTRAS="--es extra_key 'extra_value' --ez extra_boolean true"

# 【可选】启动标志
# FLAG_ACTIVITY_NEW_TASK: -f 0x10000000
# FLAG_ACTIVITY_CLEAR_TOP: -f 0x04000000
FLAGS="-f 0x10000000"

# 【可选】用户ID（多用户设备，默认0为主用户）
USER_ID="0"

# ------------------- Exec -------------------

am start \
--user ${USER_ID} \
-n ${PACKAGE_NAME}/${ACTIVITY_NAME} \
-a ${ACTION} \
-d "${DATA_URI}" \
-t ${MIME_TYPE} \
-c ${CATEGORY} \
${EXTRAS} \
${FLAGS}
