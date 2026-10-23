fn main() {
    // Explicit app permissions are required for custom commands invoked by
    // remote WebView2 pages. A mere core:default remote capability cannot
    // authorize these commands on current Tauri 2.
    tauri_build::try_build(
        tauri_build::Attributes::new().app_manifest(
            tauri_build::AppManifest::new().commands(&[
                "get_settings",
                "configure_server",
                "configure_shortcut",
                "copy_text",
                "copy_image_png",
                "clipboard_enabled",
                "receiver_diagnostic",
            ]),
        ),
    )
    .expect("Tauri permissions and build configuration must be valid");
}
