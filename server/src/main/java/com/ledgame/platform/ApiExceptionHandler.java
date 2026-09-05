package com.ledgame.platform;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    static final String DATABASE_BUSY_MESSAGE =
            "数据库正在忙，请稍后重试；如果持续出现，请检查是否有其他程序正在占用数据库文件";
    private final DataSource dataSource;

    public ApiExceptionHandler(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException exception) {
        String message = exception.getReason() == null ? "本机服务请求失败" : exception.getReason();
        LinkedHashMap<String, String> body = new LinkedHashMap<>();
        if (exception instanceof PlatformApiException platformException) {
            body.put("code", platformException.getCode());
        }
        body.put("message", message);
        return ResponseEntity.status(exception.getStatusCode()).body(body);
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Map<String, String>> handleDataAccess(RuntimeException exception) {
        if (isSqliteLock(exception)) {
            evictBusyConnection();
            return ResponseEntity.status(503).body(Map.of(
                    "code", "DATABASE_BUSY",
                    "message", DATABASE_BUSY_MESSAGE));
        }
        log.error("本机数据库请求失败", exception);
        return ResponseEntity.internalServerError().body(Map.of("message", "本机数据库请求失败"));
    }

    private void evictBusyConnection() {
        if (dataSource instanceof HikariDataSource hikari
                && hikari.getHikariPoolMXBean() != null) {
            hikari.getHikariPoolMXBean().softEvictConnections();
        }
    }

    static boolean isSqliteLock(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof SQLiteException sqlite) {
                int primaryCode = sqlite.getResultCode().code & 0xff;
                if (primaryCode == SQLiteErrorCode.SQLITE_BUSY.code
                        || primaryCode == SQLiteErrorCode.SQLITE_LOCKED.code) {
                    return true;
                }
            }
            Throwable next = current.getCause();
            if (next == current) break;
            current = next;
        }
        return false;
    }
}
