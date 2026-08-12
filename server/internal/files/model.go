package files

import (
	"errors"
	"io"
	"time"

	"transdot.local/transfer-assistant/server/internal/messages"
)

const (
	KindImage                = "image"
	KindFile                 = "file"
	MaximumThumbnailBytes    = 5 * 1024 * 1024
	maximumFilenameBytes     = 255
	maximumMIMETypeBytes     = 255
	cleanupPartFileExtraTime = 5 * time.Minute
)

var (
	ErrEmptyBatch          = errors.New("upload batch is empty")
	ErrTooManyFiles        = errors.New("upload batch contains too many files")
	ErrFileTooLarge        = errors.New("file is too large")
	ErrBatchTooLarge       = errors.New("upload batch is too large")
	ErrInvalidItem         = errors.New("upload item is invalid")
	ErrInsufficientStorage = errors.New("insufficient storage")
	ErrUploadNotFound      = errors.New("upload was not found")
	ErrUploadExpired       = errors.New("upload session expired")
	ErrUploadIncomplete    = errors.New("upload is incomplete")
	ErrUploadConflict      = errors.New("upload is already complete")
	ErrThumbnailInvalid    = errors.New("thumbnail is invalid")
	ErrFileNotFound        = errors.New("file was not found")
	ErrFileExpired         = errors.New("file has expired")
	ErrMessageNotFound     = errors.New("message was not found")
)

type Config struct {
	DataDir          string
	MaxFileBytes     int64
	MaxBatchBytes    int64
	MaxBatchItems    int
	FilePoolMaxBytes int64
	FileTTL          time.Duration
	FileMessageTTL   time.Duration
	UploadSessionTTL time.Duration
}

type UploadItem struct {
	Filename  string `json:"filename"`
	MIMEType  string `json:"mime_type"`
	SizeBytes int64  `json:"size_bytes"`
	Kind      string `json:"kind"`
}

type UploadTicket struct {
	FileID             string `json:"file_id"`
	UploadID           string `json:"upload_id"`
	Filename           string `json:"filename"`
	MIMEType           string `json:"mime_type"`
	SizeBytes          int64  `json:"size_bytes"`
	Kind               string `json:"kind"`
	UploadURL          string `json:"upload_url"`
	ThumbnailUploadURL string `json:"thumbnail_upload_url,omitempty"`
}

type UploadBatch struct {
	ID            string         `json:"id"`
	Status        string         `json:"status"`
	TotalBytes    int64          `json:"total_bytes"`
	ReservedBytes int64          `json:"reserved_bytes"`
	ExpiresAt     time.Time      `json:"expires_at"`
	Uploads       []UploadTicket `json:"uploads"`
}

type Download struct {
	FileID      string
	Filename    string
	MIMEType    string
	SizeBytes   int64
	ModTime     time.Time
	Reader      io.ReadSeekCloser
	ReleaseFunc func()
}

func (d *Download) Release() {
	if d == nil {
		return
	}
	if d.Reader != nil {
		_ = d.Reader.Close()
	}
	if d.ReleaseFunc != nil {
		d.ReleaseFunc()
		d.ReleaseFunc = nil
	}
}

type Thumbnail struct {
	FileID    string
	MIMEType  string
	SizeBytes int64
	ModTime   time.Time
	Reader    io.ReadSeekCloser
}

func (t *Thumbnail) Close() {
	if t != nil && t.Reader != nil {
		_ = t.Reader.Close()
	}
}

type ExpiredEvent struct {
	FileID    string `json:"file_id"`
	MessageID string `json:"message_id"`
	Reason    string `json:"reason"`
}

type UploadResult struct {
	Message messages.Message
}

type NotifyFunc func(eventType string, data any)
