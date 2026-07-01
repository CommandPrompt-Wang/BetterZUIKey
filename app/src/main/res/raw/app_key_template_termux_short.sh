#!/system/bin/sh

# ------------------- Vars -------------------

COMMAND_PATH='/data/data/com.termux/files/usr/bin/bash'
# --esa 以逗号分隔 argv，勿写 "-c,\"...\""；第二段为 bash -c 的脚本体，可含空格
ARGUMENTS='-c,echo Hello World'

# ------------------- Exec -------------------

am startservice --user 0 \
-n com.termux/com.termux.app.RunCommandService \
-a com.termux.RUN_COMMAND \
--es com.termux.RUN_COMMAND_PATH "${COMMAND_PATH}" \
--esa com.termux.RUN_COMMAND_ARGUMENTS "${ARGUMENTS}"
