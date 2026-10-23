#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::{
    image::Image,
    menu::{CheckMenuItem, Menu, MenuItem},
    tray::TrayIconBuilder,
    AppHandle, Manager, State, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent,
};
use tauri_plugin_autostart::ManagerExt;
use tauri_plugin_clipboard_manager::ClipboardExt;
use tauri_plugin_global_shortcut::{GlobalShortcutExt, ShortcutState};

const DEFAULT_SHORTCUT: &str = "Ctrl+Shift+T";

#[derive(Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct Settings {
    server_url: String,
    copy_enabled: bool,
    shortcut: String,
}

impl Default for Settings {
    fn default() -> Self {
        Self {
            server_url: String::new(),
            copy_enabled: true,
            shortcut: DEFAULT_SHORTCUT.to_string(),
        }
    }
}

struct Shared(Mutex<Settings>);

// No message contents, tokens or device identifiers are recorded.
struct ReceiverStatus(Mutex<String>);
fn record_status(app: &AppHandle, message: &str) {
    let state = app.state::<ReceiverStatus>();
    if let Ok(mut status) = state.0.lock() {
        *status = message.chars().take(240).collect();
    };
}

fn settings_file(app: &AppHandle) -> Result<PathBuf, String> {
    let folder = app.path().app_config_dir().map_err(|e| e.to_string())?;
    fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
    Ok(folder.join("desktop-v2.json"))
}
fn save_settings(app: &AppHandle, settings: &Settings) -> Result<(), String> {
    let path = settings_file(app)?;
    let contents = serde_json::to_vec_pretty(settings).map_err(|e| e.to_string())?;
    fs::write(path, contents).map_err(|e| e.to_string())
}
fn read_settings(app: &AppHandle) -> Settings {
    settings_file(app)
        .ok()
        .and_then(|p| fs::read(p).ok())
        .and_then(|bytes| serde_json::from_slice(&bytes).ok())
        .unwrap_or_default()
}

fn normalize_server(input: &str) -> Result<String, String> {
    let parsed = url::Url::parse(input.trim()).map_err(|_| "服务器 URL 无效".to_string())?;
    let host = parsed.host_str().unwrap_or_default();
    let localhost = host == "localhost" || host == "127.0.0.1";
    if parsed.scheme() != "https" && !(localhost && parsed.scheme() == "http") {
        return Err("服务器必须使用 HTTPS，本地测试除外".into());
    }
    if parsed.username() != "" || parsed.password().is_some() ||
        parsed.query().is_some() || parsed.fragment().is_some() ||
        parsed.path() != "/" {
        return Err("仅填写服务器地址和端口，不能包含路径/账号/查询参数".into());
    }
    Ok(parsed.origin().ascii_serialization())
}

fn normalize_shortcut(input: &str) -> Result<String, String> {
    let parts: Vec<&str> = input.split('+').map(str::trim).collect();
    if parts.len() < 2 || parts.iter().any(|part| part.is_empty()) {
        return Err("请录入包含修饰键的快捷键组合".into());
    }
    let mut control = false;
    let mut alt = false;
    let mut shift = false;
    let mut super_key = false;
    for modifier in &parts[..parts.len() - 1] {
        let slot = match modifier.to_ascii_uppercase().as_str() {
            "CTRL" | "CONTROL" | "COMMANDORCONTROL" | "CMDORCTRL" => &mut control,
            "ALT" => &mut alt,
            "SHIFT" => &mut shift,
            "SUPER" | "WIN" | "WINDOWS" | "META" => &mut super_key,
            _ => return Err("快捷键格式无效，请使用 Ctrl、Alt、Shift 或 Win 修饰键".into()),
        };
        if *slot { return Err("快捷键中不能重复同一个修饰键".into()); }
        *slot = true;
    }
    if !control && !alt && !super_key {
        return Err("快捷键至少需要 Ctrl、Alt 或 Win 修饰键".into());
    }
    let raw_key = parts[parts.len() - 1];
    let upper_key = raw_key.to_ascii_uppercase();
    let key = if upper_key.len() == 1 && upper_key.as_bytes()[0].is_ascii_alphanumeric() {
        upper_key
    } else if upper_key == "SPACE" {
        "Space".to_string()
    } else if let Some(number) = upper_key.strip_prefix('F') {
        match number.parse::<u8>() {
            Ok(n) if (1..=12).contains(&n) => format!("F{n}"),
            _ => return Err("功能键仅支持 F1 到 F12".into()),
        }
    } else {
        return Err("按键仅支持字母、数字、F1–F12 或空格键".into());
    };
    let mut normalized = Vec::new();
    if control { normalized.push("Ctrl"); }
    if alt { normalized.push("Alt"); }
    if shift { normalized.push("Shift"); }
    if super_key { normalized.push("Super"); }
    normalized.push(key.as_str());
    Ok(normalized.join("+"))
}

fn origin_matches(actual: &str, expected: &str) -> bool {
    url::Url::parse(actual).map(|u| u.origin().ascii_serialization() == expected).unwrap_or(false)
}

fn verify_remote(window: &WebviewWindow, state: &Shared) -> Result<(), String> {
    if window.label() != "remote" { return Err("无权调用剪贴板".into()); }
    let current = state.0.lock().map_err(|_| "配置状态不可用")?;
    if current.server_url.is_empty() ||
       !origin_matches(&window.url().map_err(|e| e.to_string())?.to_string(), &current.server_url) {
        return Err("未经授权的服务器来源".into());
    }
    if !current.copy_enabled { return Err("自动复制已关闭".into()); }
    Ok(())
}

#[tauri::command]
fn get_settings(window: WebviewWindow, state: State<'_, Shared>) -> Result<Settings, String> {
    if window.label() != "settings" { return Err("只允许在本地设置窗口操作".into()); }
    state.0.lock().map(|s| s.clone()).map_err(|e| e.to_string())
}

#[tauri::command]
fn configure_shortcut(
    app: AppHandle, window: WebviewWindow, state: State<'_, Shared>, value: String,
) -> Result<String, String> {
    if window.label() != "settings" {
        return Err("只允许在本地设置窗口修改快捷键".into());
    }
    let normalized = normalize_shortcut(&value)?;
    let previous = state.0.lock().map_err(|e| e.to_string())?.shortcut.clone();
    if normalized == previous { return Ok(normalized); }
    app.global_shortcut().register(normalized.as_str())
        .map_err(|_| "快捷键注册失败，可能已被其他程序占用，请更换组合键".to_string())?;
    {
        let mut settings = match state.0.lock() {
            Ok(settings) => settings,
            Err(error) => {
                let _ = app.global_shortcut().unregister(normalized.as_str());
                return Err(error.to_string());
            }
        };
        if settings.shortcut != previous {
            let _ = app.global_shortcut().unregister(normalized.as_str());
            return Err("快捷键设置已变化，请重新保存".into());
        }
        settings.shortcut = normalized.clone();
        if let Err(error) = save_settings(&app, &settings) {
            settings.shortcut = previous.clone();
            let _ = app.global_shortcut().unregister(normalized.as_str());
            return Err(error);
        }
    }
    let _ = app.global_shortcut().unregister(previous.as_str());
    record_status(&app, &format!("全局快捷键已更新为 {normalized}"));
    Ok(normalized)
}

#[tauri::command]
async fn configure_server(
    app: AppHandle, window: WebviewWindow, state: State<'_, Shared>, value: String,
) -> Result<(), String> {
    if window.label() != "settings" { return Err("无权修改服务器".into()); }
    let origin = normalize_server(&value)?;
    let previous = {
        let mut settings = state.0.lock().map_err(|e| e.to_string())?;
        let previous = settings.server_url.clone();
        settings.server_url = origin.clone();
        save_settings(&app, &settings)?;
        previous
    };
    // Changing a running server requires re-creating its WebView so that it gets
    // an isolated origin guard and fresh initialization script.
    if let Some(remote) = app.get_webview_window("remote") {
        if previous != origin {
            remote.destroy().map_err(|e| e.to_string())?;
        } else {
            remote.show().map_err(|e| e.to_string())?;
            window.hide().ok();
            return Ok(());
        }
    }
    open_remote(&app, &origin, true)?;
    window.hide().ok();
    Ok(())
}

#[tauri::command]
fn copy_text(
    window: WebviewWindow, app: AppHandle, state: State<'_, Shared>, text: String,
) -> Result<(), String> {
    verify_remote(&window, &state)?;
    if text.is_empty() || text.len() > 150 * 1024 { return Err("文本长度无效".into()); }
    app.clipboard().write_text(text).map_err(|e| e.to_string())
}

#[tauri::command]
fn copy_image_png(
    window: WebviewWindow, app: AppHandle, state: State<'_, Shared>, data: String,
) -> Result<(), String> {
    verify_remote(&window, &state)?;
    if data.len() > 16 * 1024 * 1024 { return Err("图片大小超过限制".into()); }
    let bytes = {
        use base64::Engine;
        base64::engine::general_purpose::STANDARD.decode(data).map_err(|e| e.to_string())?
    };
    if bytes.len() > 12 * 1024 * 1024 { return Err("图片大小超过限制".into()); }
    let image = image::load_from_memory_with_format(&bytes, image::ImageFormat::Png)
        .map_err(|e| format!("图片解析失败: {e}"))?;
    let rgba = image.into_rgba8();
    let (width, height) = rgba.dimensions();
    if width == 0 || height == 0 || (width as u64)*(height as u64) > 12_000_000 {
        return Err("图片像素尺寸超限".into());
    }
    app.clipboard().write_image(&Image::new_owned(rgba.into_raw(), width, height))
        .map_err(|e| e.to_string())
}

// Diagnostics do not depend on auto-copy being enabled.
#[tauri::command]
fn receiver_diagnostic(
    window: WebviewWindow, app: AppHandle, state: State<'_, Shared>, status: String,
) -> Result<(), String> {
    if window.label() != "remote" { return Err("Invalid window".into()); }
    let expected = state.0.lock().map_err(|e| e.to_string())?.server_url.clone();
    if expected.is_empty() || !origin_matches(
        &window.url().map_err(|e| e.to_string())?.to_string(), &expected
    ) { return Err("Invalid server origin".into()); }
    record_status(&app, &status);
    Ok(())
}

#[tauri::command]
fn clipboard_enabled(window: WebviewWindow, state: State<'_, Shared>) -> bool {
    verify_remote(&window, &state).is_ok()
}

fn open_panel(app: &AppHandle) -> Result<(), String> {
    let server_url = app.state::<Shared>().0.lock()
        .map_err(|e| e.to_string())?.server_url.clone();
    if server_url.is_empty() { return open_settings(app); }
    if let Some(settings) = app.get_webview_window("settings") { settings.hide().ok(); }
    open_remote(app, &server_url, true)
}

fn open_settings(app: &AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_webview_window("settings") {
        window.show().map_err(|e| e.to_string())?;
        window.set_focus().ok();
        return Ok(());
    }
    WebviewWindowBuilder::new(app, "settings", WebviewUrl::App("index.html".into()))
        .title("TransDot · 设置")
        .inner_size(560.0, 760.0)
        .min_inner_size(480.0, 600.0)
        .resizable(true)
        .build()
        .map_err(|e| e.to_string())?;
    Ok(())
}

fn open_remote(app: &AppHandle, origin: &str, visible: bool) -> Result<(), String> {
    if let Some(window) = app.get_webview_window("remote") {
        if visible { window.show().ok(); window.set_focus().ok(); }
        return Ok(());
    }
    let url = url::Url::parse(origin).map_err(|e| e.to_string())?;
    let allowed_origin = origin.to_string();
    let navigation_origin = allowed_origin.clone();
    let script = format!(
        "window.__TRANSDOT_TRUSTED_ORIGIN__ = {};\n{}",
        serde_json::to_string(origin).map_err(|e| e.to_string())?,
        include_str!("inject.js"),
    );
    WebviewWindowBuilder::new(app, "remote", WebviewUrl::External(url))
        .title("TransDot")
        .inner_size(1160.0, 790.0)
        .min_inner_size(720.0, 500.0)
        .visible(visible)
        .enable_clipboard_access()
        .initialization_script(script.clone())
        // Some remote pages complete the document lifecycle before their injected
        // initialization script has a usable Tauri bridge. Re-inject after Finish.
        .on_page_load(move |window, payload| {
            if payload.event() != tauri::webview::PageLoadEvent::Finished { return; }
            let actual = match window.url() {
                Ok(url) => url,
                Err(_) => return,
            };
            if !origin_matches(actual.as_str(), &allowed_origin) { return; }
            record_status(&window.app_handle(), "服务端网页已加载，正在启动实时接收器");
            let _ = window.eval(&script);
        })
        .on_navigation(move |url| url.origin().ascii_serialization() == navigation_origin)
        .build()
        .map_err(|e| e.to_string())?;
    Ok(())
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, _shortcut, event| {
                    if event.state == ShortcutState::Pressed { let _ = open_panel(app); }
                })
                .build(),
        )
        .invoke_handler(tauri::generate_handler![
            get_settings, configure_server, configure_shortcut, copy_text, copy_image_png,
            clipboard_enabled, receiver_diagnostic
        ])
        .setup(|app| {
            let handle = app.handle();
            let mut initial = read_settings(handle);
            let saved_shortcut = initial.shortcut.clone();
            initial.shortcut = normalize_shortcut(&initial.shortcut)
                .unwrap_or_else(|_| DEFAULT_SHORTCUT.to_string());
            app.manage(Shared(Mutex::new(initial.clone())));
            app.manage(ReceiverStatus(Mutex::new("等待网页与剪贴板接收器连接".into())));
            if initial.shortcut != saved_shortcut { let _ = save_settings(handle, &initial); }
            if handle.global_shortcut().register(initial.shortcut.as_str()).is_err() {
                if initial.shortcut != DEFAULT_SHORTCUT
                    && handle.global_shortcut().register(DEFAULT_SHORTCUT).is_ok()
                {
                    if let Ok(mut settings) = app.state::<Shared>().0.lock() {
                        settings.shortcut = DEFAULT_SHORTCUT.to_string();
                        let _ = save_settings(handle, &settings);
                    }
                    record_status(handle, "自定义快捷键不可用，已恢复默认 Ctrl+Shift+T");
                } else {
                    record_status(handle, "全局快捷键不可用，请在快捷键设置中更换组合键");
                }
            }
            let open = MenuItem::with_id(app, "open", "打开面板", true, None::<&str>)?;
            let toggle = CheckMenuItem::with_id(app, "toggle", "自动复制", initial.copy_enabled, true, None::<&str>)?;
            let auto_enabled = app.autolaunch().is_enabled().unwrap_or(false);
            let auto = CheckMenuItem::with_id(app, "auto", "开机启动", auto_enabled, true, None::<&str>)?;
            let setup = MenuItem::with_id(app, "setup", "更换服务器", true, None::<&str>)?;
            let shortcut_settings = MenuItem::with_id(app, "shortcut_settings", "快捷键设置…", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "完全退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &toggle, &auto, &setup, &shortcut_settings, &quit])?;
            let toggle_menu = toggle.clone();
            let auto_menu = auto.clone();
            TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("TransDot · 跨设备自动剪贴板")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "open" => { let _ = open_panel(app); }
                    "toggle" => {
                        let shared = app.state::<Shared>();
                        let enabled = if let Ok(mut settings) = shared.0.lock() {
                            let previous = settings.copy_enabled;
                            let next = !previous;
                            settings.copy_enabled = next;
                            if save_settings(app, &settings).is_ok() {
                                Some(next)
                            } else {
                                settings.copy_enabled = previous;
                                None
                            }
                        } else { None };
                        if let Some(enabled) = enabled {
                            let _ = toggle_menu.set_checked(enabled);
                            record_status(app, if enabled { "用户已启用自动复制" } else { "用户已暂停自动复制" });
                        } else {
                            let _ = toggle_menu.set_checked(
                                app.state::<Shared>().0.lock().map(|s| s.copy_enabled).unwrap_or(false)
                            );
                        }
                    }
                    "auto" => {
                        let manager = app.autolaunch();
                        let current = manager.is_enabled().unwrap_or(false);
                        let result = if current { manager.disable() } else { manager.enable() };
                        let actual = if result.is_ok() { manager.is_enabled().unwrap_or(!current) } else { current };
                        let _ = auto_menu.set_checked(actual);
                    }
                    "setup" | "shortcut_settings" => {
                        if let Some(w) = app.get_webview_window("remote") { w.hide().ok(); }
                        let _ = open_settings(app);
                    }
                    "quit" => app.exit(0),
                    _ => (),
                })
                .build(app)?;
            let hidden = std::env::args().any(|arg| arg == "--hidden");
            if initial.server_url.is_empty() {
                open_settings(handle).map_err(std::io::Error::other)?;
            } else {
                open_remote(handle, &initial.server_url, !hidden).map_err(std::io::Error::other)?;
            }
            Ok(())
        })
        .on_window_event(|window,event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                window.hide().ok();
            }
        })
        .run(tauri::generate_context!())
        .expect("TransDot lightweight desktop startup failed");
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn http_remote_denied() {
        assert!(normalize_server("http://example.com").is_err());
        assert!(normalize_server("http://192.168.0.1").is_err());
    }
    #[test]
    fn only_safe_https_origin() {
        assert_eq!(normalize_server("https://example.com:9004/").unwrap(), "https://example.com:9004");
        assert!(normalize_server("https://example.com/path").is_err());
        assert!(normalize_server("https://a:b@example.com").is_err());
        assert!(normalize_server("https://example.com?q=1").is_err());
    }
    #[test]
    fn local_testing_is_possible() {
        assert_eq!(normalize_server("http://localhost:5757").unwrap(), "http://localhost:5757");
    }
    #[test]
    fn no_lookalike_host() {
        assert!(!origin_matches("https://example.com.evil.org/", "https://example.com"));
        assert!(!origin_matches("https://example.com:9005/", "https://example.com:9004"));
    }

    #[test]
    fn shortcut_normalization_requires_a_modifier_and_supported_key() {
        assert_eq!(normalize_shortcut("ctrl+shift+t").unwrap(), "Ctrl+Shift+T");
        assert_eq!(normalize_shortcut("Alt+F8").unwrap(), "Alt+F8");
        assert_eq!(normalize_shortcut("Win+1").unwrap(), "Super+1");
        assert!(normalize_shortcut("T").is_err());
        assert!(normalize_shortcut("Shift+T").is_err());
        assert!(normalize_shortcut("Ctrl+Ctrl+T").is_err());
        assert!(normalize_shortcut("Ctrl+Shift+F13").is_err());
        assert!(normalize_shortcut("Ctrl+Mouse1").is_err());
    }
}
