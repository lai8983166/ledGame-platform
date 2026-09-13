## Purpose

为会员管理端提供可恢复的会员头像存储边界，使自助注册端提交的照片不会以明文长期散落在客户端、数据库或日志中。

## ADDED Requirements

### Requirement: Validate and store optional avatars

会员管理端 SHALL 接受可选头像，并在保存前校验媒体类型、文件大小、图像尺寸和解码结果；头像缺失或无效时 SHALL 保留现有头像标识并返回明确的业务错误，不得覆盖有效头像。

#### Scenario: Store a valid captured avatar

- **WHEN** 自助注册端提交符合限制且可解码的头像
- **THEN** 会员管理端为头像生成不可预测的标识，以加密文件保存，并在会员记录中保存该标识

#### Scenario: Reject an invalid avatar

- **WHEN** 上传内容超过大小限制、媒体类型不支持、图像无法解码或尺寸超出限制
- **THEN** 会员管理端拒绝本次头像写入并返回稳定错误码，会员注册不会留下半个头像文件或无效头像标识

### Requirement: Protect avatar data at rest and in transit

会员管理端 SHALL 通过现有受保护数据密钥边界加密头像文件，服务器日志和普通 API 错误 SHALL 不记录照片正文、完整上传内容或本地密钥；头像读取 SHALL 只通过现有授权的会员数据请求返回。

#### Scenario: Inspect application storage and logs

- **WHEN** 对保存头像后的数据库、头像目录、临时目录和服务器日志进行检查
- **THEN** 不得发现可直接读取的照片正文、上传临时文件或数据密钥，会员记录仅暴露头像标识而非文件路径

### Requirement: Preserve compatibility and recovery

会员注册接口 SHALL 继续兼容不携带头像文件的旧客户端；会员管理端的加密数据库备份 SHALL 同步包含已确认的加密头像文件及其校验元数据，导入或恢复 SHALL 原子地处理数据库标识和头像文件。

#### Scenario: Register without a captured avatar

- **WHEN** 旧版或未拍照的自助注册端按原请求格式注册会员
- **THEN** 会员管理端照常创建会员并使用默认或头像库标识，不要求上传头像

#### Scenario: Restore a database with avatars

- **WHEN** 出厂管理员导入包含头像记录的有效加密备份
- **THEN** 数据库和对应头像文件一起恢复，任一部分校验失败时当前主库和当前头像文件均保持不变

### Requirement: Do not perform biometric analysis

系统 SHALL 将拍摄内容仅作为会员头像文件保存和展示，不得进行人脸识别、身份推断、年龄推断或其他生物特征分析。

#### Scenario: Display a stored avatar

- **WHEN** 授权的会员查询或管理界面请求会员头像
- **THEN** 系统仅返回头像展示所需内容，不返回任何人脸分析结果或推断属性
