use std::fmt;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};
use std::time::SystemTime;

const AGENTS_HEADER: &str = "===以下是关于你的介绍===\n";
const PERSONA_HEADER: &str = "\n===以下是关于用户的介绍===\n";
const HISTORY_HEADER: &str = "\n===历史会话记录摘要===\n";
const BUILTIN_AGENTS: &str = r##"## 身份

你是用户的个人智能分身，负责替代或协助用户处理日常事务。

你的目标是：
- 准确理解用户意图；
- 在不确定时主动澄清；
- 涉及外部操作时保持谨慎；
- 保护用户隐私和数据安全。

## 时间和日期

- 默认时区：`Asia/Shanghai`
- 默认语言：简体中文
- 当前日期由运行时注入，不由模型猜测。
- 日期优先使用绝对日期，避免只说“今天”“明天”。
- 标准日期格式：`YYYY-MM-DD`
- 标准时间格式：`HH:mm`
- 标准日期时间格式：`YYYY-MM-DD HH:mm`
- 对外传输使用 ISO 8601：
`YYYY-MM-DDTHH:mm:ss+08:00`
- “今天”表示当前时区当天 `00:00:00`。
- “明天”表示下一天 `00:00:00`。
- 涉及时间范围时，使用左闭右开区间：
`[start_date, end_date)`
- 当前时间`YYYY-MM-DD HH:mm:ss`：{{current_time}}

## 工具使用

- 先判断是否真的需要工具。
- 工具参数必须符合工具 Schema。
- 涉及用户数据的工具，只访问完成任务所需的数据。
- 工具失败时，向用户说明原因和可行的下一步。

## 隐私和安全

- 不主动暴露完整隐私数据。
- 展示日程、联系人等信息时，只展示完成任务所需字段。
- 不将用户数据发送给未授权的服务。
- 不猜测密码、密钥、身份信息或敏感属性。
- 对不可逆或高风险操作要求用户明确确认。
"##;

#[derive(Debug)]
pub enum ContextError {
    ReadDirectory {
        path: PathBuf,
        source: std::io::Error,
    },
    ReadFile {
        path: PathBuf,
        source: std::io::Error,
    },
    InvalidUtf8 {
        path: PathBuf,
        source: std::string::FromUtf8Error,
    },
}

impl fmt::Display for ContextError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::ReadDirectory { path, source } => {
                write!(
                    f,
                    "failed to read context directory '{}': {source}",
                    path.display()
                )
            }
            Self::ReadFile { path, source } => {
                write!(
                    f,
                    "failed to read context file '{}': {source}",
                    path.display()
                )
            }
            Self::InvalidUtf8 { path, source } => {
                write!(
                    f,
                    "context file '{}' is not UTF-8: {source}",
                    path.display()
                )
            }
        }
    }
}

impl std::error::Error for ContextError {}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ContextDirectories {
    pub agents_directory: Option<PathBuf>,
    pub persona_directory: Option<PathBuf>,
}

/// Immutable context captured when a session is initialized.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionContext {
    pub system_prompt: String,
}

impl SessionContext {
    pub fn initialize(history_summary: &str) -> Result<Self, ContextError> {
        let system_prompt = build_configured_system_prompt(history_summary)?;
        Ok(Self { system_prompt })
    }
}

impl ContextDirectories {
    pub fn build_system_prompt(&self, history_summary: &str) -> Result<String, ContextError> {
        let agents = read_documents(self.agents_directory.as_deref())?;
        let persona = read_documents(self.persona_directory.as_deref())?;
        Ok(format!(
            "{AGENTS_HEADER}{}{agents}\n{PERSONA_HEADER}{persona}{HISTORY_HEADER}{history_summary}",
            builtin_agents()
        ))
    }
}

static CONTEXT_DIRECTORIES: OnceLock<Mutex<ContextDirectories>> = OnceLock::new();
static CONTEXT_CACHE: OnceLock<Mutex<ContextCache>> = OnceLock::new();

#[derive(Default)]
struct ContextCache {
    agents: CachedDocuments,
    persona: CachedDocuments,
}

#[derive(Default)]
struct CachedDocuments {
    directory: Option<PathBuf>,
    signature: Vec<FileSignature>,
    content: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct FileSignature {
    path: PathBuf,
    length: u64,
    modified: Option<SystemTime>,
}

fn configured_directories() -> &'static Mutex<ContextDirectories> {
    CONTEXT_DIRECTORIES.get_or_init(|| Mutex::new(ContextDirectories::default()))
}

/// Replaces the document directories supplied by the embedding APP.
/// Empty paths disable the corresponding document collection.
pub fn configure_context_directories(agents_directory: String, persona_directory: String) {
    let mut configured = configured_directories()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    configured.agents_directory = non_empty_path(agents_directory);
    configured.persona_directory = non_empty_path(persona_directory);
    clear_cache();
}

pub fn clear_context_directories() {
    *configured_directories()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = ContextDirectories::default();
    clear_cache();
}

pub fn build_configured_system_prompt(history_summary: &str) -> Result<String, ContextError> {
    let configured = configured_directories()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .clone();
    let mut cache = CONTEXT_CACHE
        .get_or_init(|| Mutex::new(ContextCache::default()))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let agents = read_cached_documents(&mut cache.agents, configured.agents_directory.as_deref())?;
    let persona =
        read_cached_documents(&mut cache.persona, configured.persona_directory.as_deref())?;
    Ok(format!(
        "{AGENTS_HEADER}{}{agents}\n{PERSONA_HEADER}{persona}{HISTORY_HEADER}{history_summary}",
        builtin_agents()
    ))
}

fn builtin_agents() -> String {
    BUILTIN_AGENTS.replace("{{current_time}}", &current_time_shanghai())
}

fn current_time_shanghai() -> String {
    let seconds = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
        .saturating_add(8 * 60 * 60);
    let days = seconds / 86_400;
    let day_seconds = seconds % 86_400;
    let (year, month, day) = civil_date_from_days(days as i64);
    format!(
        "{year:04}-{month:02}-{day:02} {:02}:{:02}:{:02}",
        day_seconds / 3_600,
        (day_seconds % 3_600) / 60,
        day_seconds % 60
    )
}

// Converts Unix epoch days to a Gregorian calendar date.
fn civil_date_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = (if z >= 0 { z } else { z - 146_096 }) / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = mp + if mp < 10 { 3 } else { -9 };
    let year = y + if month <= 2 { 1 } else { 0 };
    (year, month as u32, day as u32)
}

fn clear_cache() {
    *CONTEXT_CACHE
        .get_or_init(|| Mutex::new(ContextCache::default()))
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner()) = ContextCache::default();
}

fn non_empty_path(path: String) -> Option<PathBuf> {
    (!path.trim().is_empty()).then(|| PathBuf::from(path))
}

fn read_documents(directory: Option<&Path>) -> Result<String, ContextError> {
    let Some(directory) = directory else {
        return Ok(String::new());
    };
    let mut files = Vec::new();
    collect_files(directory, &mut files)?;
    files.sort();
    files
        .into_iter()
        .map(|path| {
            let bytes = fs::read(&path).map_err(|source| ContextError::ReadFile {
                path: path.clone(),
                source,
            })?;
            String::from_utf8(bytes).map_err(|source| ContextError::InvalidUtf8 { path, source })
        })
        .collect::<Result<Vec<_>, _>>()
        .map(|contents| contents.join("\n"))
}

fn read_cached_documents(
    cache: &mut CachedDocuments,
    directory: Option<&Path>,
) -> Result<String, ContextError> {
    let files = document_signatures(directory)?;
    let directory = directory.map(Path::to_path_buf);
    if cache.directory == directory && cache.signature == files {
        return Ok(cache.content.clone());
    }

    let content = read_documents(directory.as_deref())?;
    cache.directory = directory;
    cache.signature = files;
    cache.content = content.clone();
    Ok(content)
}

fn document_signatures(directory: Option<&Path>) -> Result<Vec<FileSignature>, ContextError> {
    let Some(directory) = directory else {
        return Ok(Vec::new());
    };
    let mut files = Vec::new();
    collect_files(directory, &mut files)?;
    files.sort();
    files
        .into_iter()
        .map(|path| {
            let metadata = fs::metadata(&path).map_err(|source| ContextError::ReadFile {
                path: path.clone(),
                source,
            })?;
            Ok(FileSignature {
                path,
                length: metadata.len(),
                modified: metadata.modified().ok(),
            })
        })
        .collect()
}

fn collect_files(directory: &Path, files: &mut Vec<PathBuf>) -> Result<(), ContextError> {
    let entries = fs::read_dir(directory).map_err(|source| ContextError::ReadDirectory {
        path: directory.to_path_buf(),
        source,
    })?;
    for entry in entries {
        let entry = entry.map_err(|source| ContextError::ReadDirectory {
            path: directory.to_path_buf(),
            source,
        })?;
        let path = entry.path();
        let file_type = entry
            .file_type()
            .map_err(|source| ContextError::ReadDirectory {
                path: path.clone(),
                source,
            })?;
        if file_type.is_dir() {
            collect_files(&path, files)?;
        } else if file_type.is_file() {
            files.push(path);
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn builds_deterministic_prompt_from_recursive_documents() {
        let root = std::env::temp_dir().join(format!(
            "harness-context-{}",
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        fs::create_dir_all(root.join("nested")).unwrap();
        fs::write(root.join("b.md"), "B").unwrap();
        fs::write(root.join("nested/a.md"), "A").unwrap();

        let prompt = ContextDirectories {
            agents_directory: Some(root.clone()),
            ..Default::default()
        }
        .build_system_prompt("summary")
        .unwrap();
        assert!(prompt.starts_with("===以下是关于你的介绍===\n## 身份\n"));
        assert!(prompt.contains("当前时间`YYYY-MM-DD HH:mm:ss`："));
        assert!(
            prompt
                .ends_with("B\nA\n\n===以下是关于用户的介绍===\n\n===历史会话记录摘要===\nsummary")
        );
        fs::remove_dir_all(root).unwrap();
    }
}
