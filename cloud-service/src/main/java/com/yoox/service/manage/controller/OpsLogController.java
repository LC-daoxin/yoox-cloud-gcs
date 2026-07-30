package com.yoox.service.manage.controller;

import com.yoox.great.context.response.HttpResultResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统日志查看：读取后端日志文件尾部，供运维面板展示。
 */
@Slf4j
@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/ops")
public class OpsLogController {

    @Value("${logging.file.name:/app/logs/yoox-cloud-gcs.log}")
    private String logFile;

    /**
     * 读取最近日志行。
     *
     * @param lines 返回行数，默认 200，上限 1000
     * @param keyword 可选过滤关键字（如 "拒绝上线"）
     */
    @GetMapping("/logs")
    public HttpResultResponse<List<String>> tailLogs(
            @RequestParam(defaultValue = "200") int lines,
            @RequestParam(required = false) String keyword) {
        if (lines <= 0) lines = 200;
        if (lines > 1000) lines = 1000;
        // 默认过滤出关键事件日志，避免框架噪音
        boolean filterMode = keyword == null || keyword.trim().isEmpty();
        String kw = filterMode ? "" : keyword.trim().toLowerCase();
        // 无关键字时默认只显示设备相关日志
        String[] defaultKeywords = {"MQTT", "设备", "拒绝", "online", "offline", "topo",
                "bind", "organization", "ERROR", "WARN", "status", "register", "config", "storage"};
        List<String> result = new ArrayList<>();
        try (RandomAccessFile file = new RandomAccessFile(logFile, "r")) {
            long fileLength = file.length();
            if (fileLength == 0) {
                result.add("（日志文件为空）");
                return HttpResultResponse.success(result);
            }
            long pos = fileLength - 1;
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            List<byte[]> rawLines = new ArrayList<>();
            while (pos >= 0 && rawLines.size() < lines * 5) {
                file.seek(pos);
                int ch = file.read();
                if (ch == '\n') {
                    if (buf.size() > 0) {
                        rawLines.add(buf.toByteArray());
                        buf.reset();
                    }
                } else if (ch != '\r') {
                    buf.write(ch);
                }
                pos--;
            }
            if (buf.size() > 0) {
                rawLines.add(buf.toByteArray());
            }
            // 反转为时间正序（原始字节顺序），用 UTF-8 解码
            for (int i = rawLines.size() - 1; i >= 0 && result.size() < lines; i--) {
                String line = new String(reverseBytes(rawLines.get(i)), StandardCharsets.UTF_8);
                String lower = line.toLowerCase();
                boolean match;
                if (!kw.isEmpty()) {
                    match = lower.contains(kw);
                } else {
                    // 默认模式：只显示包含关键事件的行
                    match = false;
                    for (String dk : defaultKeywords) {
                        if (lower.contains(dk.toLowerCase())) { match = true; break; }
                    }
                }
                if (match) result.add(line);
            }
            if (result.isEmpty()) {
                result.add("（没有匹配的日志行）");
            }
        } catch (IOException e) {
            log.warn("读取日志文件失败: {}", e.getMessage());
            result.add("读取日志失败: " + e.getMessage());
        }
        return HttpResultResponse.success(result);
    }

    private static byte[] reverseBytes(byte[] arr) {
        for (int i = 0, j = arr.length - 1; i < j; i++, j--) {
            byte tmp = arr[i]; arr[i] = arr[j]; arr[j] = tmp;
        }
        return arr;
    }
}
