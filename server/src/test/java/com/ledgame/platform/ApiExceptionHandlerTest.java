package com.ledgame.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

class ApiExceptionHandlerTest {
    @Test
    void recognizesBusyLockedExtendedCodesAndNestedCauses() {
        assertThat(ApiExceptionHandler.isSqliteLock(sqlite(SQLiteErrorCode.SQLITE_BUSY))).isTrue();
        assertThat(ApiExceptionHandler.isSqliteLock(sqlite(SQLiteErrorCode.SQLITE_LOCKED))).isTrue();
        assertThat(ApiExceptionHandler.isSqliteLock(sqlite(SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT))).isTrue();
        assertThat(ApiExceptionHandler.isSqliteLock(
                new IllegalStateException("outer", sqlite(SQLiteErrorCode.SQLITE_BUSY_TIMEOUT)))).isTrue();
    }

    @Test
    void doesNotGuessFromMessagesOrMisclassifyOtherDatabaseErrors() {
        assertThat(ApiExceptionHandler.isSqliteLock(sqlite(SQLiteErrorCode.SQLITE_CONSTRAINT))).isFalse();
        assertThat(ApiExceptionHandler.isSqliteLock(sqlite(SQLiteErrorCode.SQLITE_IOERR))).isFalse();
        assertThat(ApiExceptionHandler.isSqliteLock(new IllegalStateException("database is locked"))).isFalse();
        assertThat(ApiExceptionHandler.isSqliteLock(null)).isFalse();
    }

    @Test
    void keepsNonLockDatabaseFailuresOutOfTheDatabaseBusyContract() {
        var response = new ApiExceptionHandler(mock(DataSource.class)).handleDataAccess(
                new DataAccessResourceFailureException("disk unavailable"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).containsEntry("message", "本机数据库请求失败");
        assertThat(response.getBody()).doesNotContainKey("code");
    }

    private static SQLiteException sqlite(SQLiteErrorCode code) {
        return new SQLiteException(code.name(), code);
    }
}
