use std::path::PathBuf;
use tauri::{AppHandle, Manager};

pub fn directory(app: &AppHandle) -> Result<PathBuf, String> {
    let root = app.path().app_local_data_dir().map_err(|err| err.to_string())?;
    Ok(root.join("logon-profiles").join(logon_profile_name()?))
}

#[cfg(windows)]
fn logon_profile_name() -> Result<String, String> {
    use std::{io, mem, ptr};
    use windows_sys::Win32::{
        Foundation::CloseHandle,
        Security::{
            Authentication::Identity::{LsaFreeReturnBuffer, LsaGetLogonSessionData},
            GetTokenInformation, TokenStatistics, TOKEN_QUERY, TOKEN_STATISTICS,
        },
        System::Threading::{GetCurrentProcess, OpenProcessToken},
    };

    // WebView2 stores HttpOnly cookies in its native profile. Select the
    // profile before the first request to the configured server.
    unsafe {
        let mut token = ptr::null_mut();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return Err(io::Error::last_os_error().to_string());
        }
        let mut stats = mem::MaybeUninit::<TOKEN_STATISTICS>::uninit();
        let mut written = 0;
        let queried = GetTokenInformation(
            token,
            TokenStatistics,
            stats.as_mut_ptr().cast(),
            mem::size_of::<TOKEN_STATISTICS>() as u32,
            &mut written,
        );
        let query_error = (queried == 0).then(io::Error::last_os_error);
        CloseHandle(token);
        if let Some(err) = query_error {
            return Err(err.to_string());
        }

        let id = stats.assume_init().AuthenticationId;
        let mut session = ptr::null_mut();
        let status = LsaGetLogonSessionData(&id, &mut session);
        if status != 0 || session.is_null() {
            return Err(format!("无法读取 Windows 登录会话 ({status:#x})"));
        }
        let logon_time = (*session).LogonTime;
        LsaFreeReturnBuffer(session.cast());
        if logon_time <= 0 {
            return Err("Windows 登录时间无效".into());
        }
        // A LUID can be reused after reboot. Include the actual logon time.
        Ok(format!("{logon_time:016x}-{:08x}{:08x}", id.HighPart as u32, id.LowPart))
    }
}

#[cfg(not(windows))]
fn logon_profile_name() -> Result<String, String> {
    Err("TransDot 桌面端需要 Windows 登录会话".into())
}

#[cfg(all(test, windows))]
mod tests {
    use super::logon_profile_name;

    #[test]
    fn windows_returns_a_stable_profile_name_for_this_logon() {
        let profile = logon_profile_name().expect("read current Windows logon");
        assert_eq!(profile.len(), 33);
        assert_eq!(profile, logon_profile_name().expect("read the same logon again"));
    }
}
