## Purpose

为自助注册端提供不依赖固定摄像头型号的可选头像拍摄能力，并在摄像头不可用时保持现有头像选择和会员注册流程可继续完成。

## ADDED Requirements

### Requirement: Optional local camera capture

自助注册端 SHALL 在用户主动点击拍摄头像后使用操作系统可识别的本机摄像头，并 SHALL 支持内置摄像头、标准 USB UVC 摄像头以及被 Windows 暴露为摄像头的采集设备；IP 摄像头不属于本能力范围。

#### Scenario: Select one available camera

- **WHEN** 用户打开拍摄头像功能且系统发现一个或多个可用摄像头
- **THEN** 自助注册端显示摄像头选择和实时预览，并允许用户继续拍摄

#### Scenario: No camera is available

- **WHEN** 用户打开拍摄头像功能且系统未发现可用摄像头
- **THEN** 界面明确提示请检查摄像头连接或驱动，并保留默认头像和头像库选择，不阻断注册

#### Scenario: Camera permission is denied

- **WHEN** 用户或 Windows 隐私设置拒绝摄像头权限
- **THEN** 界面明确提示摄像头权限被拒绝及处理方向，并允许取消拍摄后继续使用头像库

### Requirement: Reviewable photo session

拍摄头像流程 SHALL 提供拍照、重拍、确认使用和取消操作；确认前的画面 SHALL 不改变注册会话中的头像，取消 SHALL 关闭拍摄流程并保留此前选择。

#### Scenario: Confirm a captured photo

- **WHEN** 用户拍摄画面并点击确认使用
- **THEN** 自助注册端将经过裁剪和尺寸限制的头像标记为当前头像，并在后续注册请求中携带该头像

#### Scenario: Retake or cancel a photo

- **WHEN** 用户点击重拍或取消
- **THEN** 重拍重新打开预览，取消关闭拍摄流程且不会覆盖当前头像选择或清空其他注册字段

### Requirement: Camera failures are recoverable

自助注册端 SHALL 将未发现设备、权限拒绝、设备被占用、设备断开和浏览器不支持等摄像头错误分别转换为用户可理解的提示；拍摄功能失败 SHALL 不导致当前注册会话或会员管理端连接状态被清除。

#### Scenario: Camera is disconnected during preview

- **WHEN** 摄像头在预览或拍照期间断开
- **THEN** 界面提示摄像头已断开并提供重试和取消选项，取消后仍可使用头像库完成注册

### Requirement: Capture data is bounded and temporary

自助注册端 SHALL 在上传前将确认的照片裁剪为正方形并限制尺寸和文件大小， SHALL 只在注册请求期间保留临时数据，并 SHALL 在请求完成、取消或失败清理临时副本。

#### Scenario: Registration request completes

- **WHEN** 带拍摄头像的会员注册请求成功或失败返回
- **THEN** 自助注册端不再保留该照片的长期本地副本，注册会话按现有流程处理成功或显示可重试错误
