package com.ledgame.platform;

public enum BackupErrorCode {
    BACKUP_DISABLED("数据库异盘备份未启用"),
    UNSUPPORTED_PLATFORM("当前系统不支持自动识别物理硬盘"),
    DISK_TOPOLOGY_FAILED("无法识别物理硬盘，请检查 Windows 磁盘管理和系统权限"),
    NO_CROSS_DISK_TARGET("未找到另一块可用物理硬盘，当前数据尚未受到异盘保护"),
    TARGET_NOT_WRITABLE("备份硬盘不可写，请检查硬盘连接、状态和目录权限"),
    TARGET_SPACE_LOW("备份硬盘空间不足，请清理磁盘空间"),
    ONLINE_BACKUP_FAILED("SQLite 一致性备份失败，请查看本机服务日志"),
    BACKUP_INTEGRITY_FAILED("备份数据库完整性检查失败，已保留上一份有效备份"),
    BACKUP_PUBLISH_FAILED("备份发布失败，已保留上一份有效备份"),
    DATABASE_INTEGRITY_FAILED("主数据库完整性检查失败，禁止覆盖现有备份，请联系厂家恢复"),
    DATA_PROTECTION_KEY_MISSING("数据库加密密钥缺失，已禁止业务写入，请使用同一 Windows 用户的有效备份恢复或联系厂家"),
    DATA_PROTECTION_KEY_UNAVAILABLE("Windows 无法解封数据库加密密钥，已禁止业务写入，请确认使用原 Windows 用户或联系厂家"),
    DATA_PROTECTION_KEY_MISMATCH("数据库与本机加密密钥不匹配，已禁止业务写入，请勿覆盖当前数据"),
    DATA_PROTECTION_INTEGRITY_FAILED("数据库敏感数据完整性校验失败，已禁止业务写入，请联系厂家恢复"),
    DATA_PROTECTION_MIGRATION_FAILED("数据库加密迁移未完成，已安全回滚并禁止业务写入，请查看本机服务日志"),
    DATABASE_VERSION_CONFLICT("备份版本高于主数据库，已停止自动覆盖"),
    DATABASE_IDENTITY_CONFLICT("发现另一份门店数据库备份，必须由出厂账号处理"),
    DATABASE_RECOVERY_AVAILABLE("检测到可跨 Windows 恢复的备份，请先用出厂账号生成恢复请求"),
    IMPORT_FORBIDDEN("只有出厂账号可以导入数据库"),
    IMPORT_FACTORY_ACCOUNT_INVALID("候选数据库必须包含唯一且已启用的出厂账号"),
    IMPORT_CANDIDATE_INVALID("所选备份数据库无效或不兼容"),
    IMPORT_BUSINESS_ACTIVE("仍有游戏或排队，请结束营业流程后再导入"),
    IMPORT_FAILED("数据库导入失败，已恢复导入前数据库");

    private final String defaultMessage;
    BackupErrorCode(String defaultMessage) { this.defaultMessage = defaultMessage; }
    public String defaultMessage() { return defaultMessage; }
}
