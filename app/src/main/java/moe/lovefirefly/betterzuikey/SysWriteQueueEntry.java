package moe.lovefirefly.betterzuikey;

/**
 * sys_write_queue 队列项，用于 App 与 system_server 之间跨进程传递系统设置写入请求。
 * 字段名 k/v 与既有 JSON 格式保持一致，便于 Gson 无缝解析旧队列项。
 */
public class SysWriteQueueEntry {
    public String k;
    public int v;
}
