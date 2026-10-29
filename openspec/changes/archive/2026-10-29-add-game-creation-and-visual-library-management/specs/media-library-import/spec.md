## Purpose

让用户无需离开媒体库便可导入本机媒体和建立分层目录，导入后使用应用内副本进行浏览与预览，保护现有资源和媒体目录之外的文件不被覆盖或写入。

## ADDED Requirements

### Requirement: Import local media into selected library folder
The media library SHALL copy selected supported local media into the chosen library folder or root, refresh its hierarchical browser, and permit preview of imported files. Duplicate names MUST NOT overwrite existing files. Canceling file selection MUST NOT write files.

#### Scenario: Import and preview
- **WHEN** a user selects media files for the current folder
- **THEN** library copies are created and can be browsed and previewed independently of the original paths

#### Scenario: Duplicate file or canceled picker
- **WHEN** the target contains a same-name file or file selection is canceled
- **THEN** an import uses a new noncolliding name or cancellation leaves the library unchanged

### Requirement: Safe library folder creation
The media library SHALL offer new-folder creation under the selected folder or root and reject invalid names or paths resolving outside the real library root. Write failures MUST be reported accurately.

#### Scenario: Create a folder and reject escaping paths
- **WHEN** a valid folder name is submitted
- **THEN** the new folder appears in the hierarchical browser
- **AND** traversal, absolute paths and escaping links cannot cause writes outside the library
