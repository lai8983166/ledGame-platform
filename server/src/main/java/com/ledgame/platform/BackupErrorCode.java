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
    DATABASE_VERSION_CONFLICT("备份版本高于主数据库，已停止自动覆盖"),
    DATABASE_IDENTITY_CONFLICT("发现另一份门店数据库备份，必须由出厂账号处理"),
    IMPORT_FORBIDDEN("只有出厂账号可以导入数据库"),
    IMPORT_FACTORY_ACCOUNT_INVALID("候选数据库必须包含唯一且已启用的出厂账号"),
    IMPORT_CANDIDATE_INVALID("所选备份数据库无效或不兼容"),
    IMPORT_BUSINESS_ACTIVE("仍有游戏或排队，请结束营业流程后再导入"),
    IMPORT_FAILED("数据库导入失败，已恢复导入前数据库");

    private final String defaultMessage;
    BackupErrorCode(String defaultMessage) { this.defaultMessage = defaultMessage; }
    public String defaultMessage() { return defaultMessage; }
}
