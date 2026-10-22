#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::fs;
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::{
    image::Image,
    menu::{Menu, MenuItem},
    tray::TrayIconBuilder,
    AppHandle, Manager, State, WebviewUrl, WebviewWindow, WebviewWindowBuilder, WindowEvent,
};
use tauri_plugin_autostart::ManagerExt;
use tauri_plugin_clipboard_manager::ClipboardExt;

#[derive(Clone, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
struct Settings {
    server_url: String,
    copy_enabled: bool,
}

impl Default for Settings {
    fn default() -> Self {
        Self { server_url: String::new(), copy_enabled: true }
    }
}

struct Shared(Mutex<Settings>);

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

#[tauri::command]
fn clipboard_enabled(window: WebviewWindow, state: State<'_, Shared>) -> bool {
    verify_remote(&window, &state).is_ok()
}

fn open_settings(app: &AppHandle) -> Result<(), String> {
    if let Some(window) = app.get_webview_window("settings") {
        window.show().map_err(|e| e.to_string())?;
        window.set_focus().ok();
        return Ok(());
    }
    WebviewWindowBuilder::new(app, "settings", WebviewUrl::App("index.html".into()))
        .title("TransDot · 服务器设置")
        .inner_size(540.0, 590.0)
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
        .initialization_script(script)
        .on_navigation(move |url| url.origin().ascii_serialization() == allowed_origin)
        .build()
        .map_err(|e| e.to_string())?;
    Ok(())
}

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_autostart::init(tauri_plugin_autostart::MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .invoke_handler(tauri::generate_handler![
            get_settings, configure_server, copy_text, copy_image_png, clipboard_enabled
        ])
        .setup(|app| {
            let handle = app.handle();
            let initial = read_settings(handle);
            app.manage(Shared(Mutex::new(initial.clone())));
            let open = MenuItem::with_id(app, "open", "打开 TransDot", true, None::<&str>)?;
            let toggle = MenuItem::with_id(app, "toggle", "暂停/启用自动复制", true, None::<&str>)?;
            let auto = MenuItem::with_id(app, "auto", "切换开机启动", true, None::<&str>)?;
            let setup = MenuItem::with_id(app, "setup", "更换服务器", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "完全退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open,&toggle,&auto,&setup,&quit])?;
            TrayIconBuilder::new()
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("TransDot · 跨设备自动剪贴板")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app,event| match event.id.as_ref() {
                    "open" => {
                        if let Some(w) = app.get_webview_window("remote") {
                            w.show().ok(); w.set_focus().ok();
                        } else if let Some(w) = app.get_webview_window("settings") {
                            w.show().ok(); w.set_focus().ok();
                        }
                    }
                    "toggle" => {
                        let shared = app.state::<Shared>();
                        if let Ok(mut s) = shared.0.lock() {
                            s.copy_enabled = !s.copy_enabled;
                            let _ = save_settings(app, &s);
                        }
                    }
                    "auto" => {
                        let manager = app.autolaunch();
                        if manager.is_enabled().unwrap_or(false) {
                            let _ = manager.disable();
                        } else {
                            let _ = manager.enable();
                        }
                    }
                    "setup" => {
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
}
