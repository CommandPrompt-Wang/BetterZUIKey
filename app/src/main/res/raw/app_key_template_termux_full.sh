#!/system/bin/sh

# ------------------- Vars -------------------

# 【必需】要执行的命令绝对路径
COMMAND_PATH='/data/data/com.termux/files/usr/bin/bash'

# 【可选】命令参数（--esa：逗号分隔，勿在段内加 shell 引号）
# bash -c 示例：-c,echo Hello World  →  argv: -c 与 "echo Hello World"
# 参数本身含逗号时需 Termux ≥0.115 的替代逗号机制
ARGUMENTS='-c,echo Hello World'

# 【可选】工作目录（默认为Termux家目录）
WORKDIR='/data/data/com.termux/files/home'

# 【可选】是否在后台运行（true/false，默认false）
BACKGROUND='false'

# 【可选】会话动作（0/1/2，默认0）
# 0: 切换到新会话并打开Activity
# 1: 在当前会话中运行
# 2: 创建新会话但不切换
SESSION_ACTION='0'

# 【可选】命令标签（用于在Termux通知中显示）
# COMMAND_LABEL='任务0721'

# 【可选】命令描述（用于在Termux通知中显示）
# COMMAND_DESCRIPTION='执行top命令'

# 【可选】标准输入内容
# STDIN="echo 'hello'"

# 【可选】用户ID（多用户设备，默认0为主用户）
USER_ID='0'

# ------------------- Exec -------------------

am startservice --user ${USER_ID} \
-n com.termux/com.termux.app.RunCommandService \
-a com.termux.RUN_COMMAND \
--es com.termux.RUN_COMMAND_PATH "${COMMAND_PATH}" \
--esa com.termux.RUN_COMMAND_ARGUMENTS "${ARGUMENTS}" \
--es com.termux.RUN_COMMAND_WORKDIR "${WORKDIR}" \
--ez com.termux.RUN_COMMAND_BACKGROUND "${BACKGROUND}" \
--es com.termux.RUN_COMMAND_SESSION_ACTION "${SESSION_ACTION}"
