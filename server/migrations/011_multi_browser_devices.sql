ALTER TABLE devices
ADD COLUMN display_name TEXT NOT NULL DEFAULT '浏览器设备'
    CHECK (length(display_name) BETWEEN 1 AND 64);

ALTER TABLE pairing_sessions
ADD COLUMN requested_device_name TEXT NOT NULL DEFAULT '浏览器设备'
    CHECK (length(requested_device_name) BETWEEN 1 AND 64);

UPDATE devices
SET display_name = 'Android Master'
WHERE device_type = 'android_master';

DROP INDEX devices_one_active_per_type;

CREATE UNIQUE INDEX devices_one_active_android_master
    ON devices (device_type)
    WHERE device_type = 'android_master' AND revoked_at IS NULL;

CREATE INDEX devices_active_browsers_recent
    ON devices (last_seen_at DESC, created_at DESC)
    WHERE device_type = 'windows_browser' AND revoked_at IS NULL;
