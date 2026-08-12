package messages

import (
	"context"
	"database/sql"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/google/uuid"
)

const (
	TypeText        = "text"
	TypeImage       = "image"
	TypeFile        = "file"
	DefaultLimit    = 50
	MaximumLimit    = 50
	MaximumTextLen  = 100 * 1024
	timestampFormat = "2006-01-02T15:04:05.000000000Z07:00"
)

var (
	ErrEmptyText     = errors.New("text message is empty")
	ErrTextTooLarge  = errors.New("text message exceeds 100 KB")
	ErrInvalidUTF8   = errors.New("text message is not valid UTF-8")
	ErrInvalidCursor = errors.New("timeline cursor is invalid")
	ErrNotFound      = errors.New("message not found")
	ErrInvalidSearch = errors.New("search query is invalid")
)

type Message struct {
	ID                string      `json:"id"`
	Type              string      `json:"type"`
	BatchID           *string     `json:"batch_id"`
	SourceDeviceID    string      `json:"source_device_id"`
	SourceDeviceType  string      `json:"source_device_type"`
	TextContent       *string     `json:"text_content"`
	CreatedAt         time.Time   `json:"created_at"`
	MetadataExpiresAt *time.Time  `json:"metadata_expires_at"`
	File              *Attachment `json:"file,omitempty"`
}

type Attachment struct {
	ID               string     `json:"id"`
	OriginalFilename string     `json:"original_filename"`
	MIMEType         string     `json:"mime_type"`
	SizeBytes        int64      `json:"size_bytes"`
	Status           string     `json:"status"`
	ExpiresAt        *time.Time `json:"expires_at"`
	ExpiredReason    *string    `json:"expired_reason,omitempty"`
	DownloadURL      string     `json:"download_url"`
	ThumbnailURL     string     `json:"thumbnail_url,omitempty"`
}

type Page struct {
	Messages   []Message `json:"messages"`
	NextBefore string    `json:"next_before,omitempty"`
}

type Context struct {
	TargetMessageID string    `json:"target_message_id"`
	Messages        []Message `json:"messages"`
}

type Service struct {
	db  *sql.DB
	now func() time.Time
}

func NewService(db *sql.DB) *Service {
	return &Service{db: db, now: time.Now}
}

func (s *Service) CreateText(ctx context.Context, sourceDeviceID, content string) (Message, error) {
	if !utf8.ValidString(content) {
		return Message{}, ErrInvalidUTF8
	}
	if strings.TrimSpace(content) == "" {
		return Message{}, ErrEmptyText
	}
	if len([]byte(content)) > MaximumTextLen {
		return Message{}, ErrTextTooLarge
	}

	now := s.now().UTC()
	messageID := uuid.NewString()
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return Message{}, fmt.Errorf("begin text message transaction: %w", err)
	}
	defer tx.Rollback()

	if _, err := tx.ExecContext(ctx, `
		INSERT INTO messages (id, type, source_device_id, text_content, created_at)
		VALUES (?, 'text', ?, ?, ?)
	`, messageID, sourceDeviceID, content, formatTime(now)); err != nil {
		return Message{}, fmt.Errorf("insert text message: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `
		INSERT INTO message_search_fts (message_id, text_content, original_filename)
		VALUES (?, ?, '')
	`, messageID, content); err != nil {
		return Message{}, fmt.Errorf("index text message: %w", err)
	}

	var sourceDeviceType string
	if err := tx.QueryRowContext(ctx, `SELECT device_type FROM devices WHERE id = ?`, sourceDeviceID).Scan(&sourceDeviceType); err != nil {
		return Message{}, fmt.Errorf("read source device type: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return Message{}, fmt.Errorf("commit text message: %w", err)
	}

	return Message{
		ID:               messageID,
		Type:             TypeText,
		SourceDeviceID:   sourceDeviceID,
		SourceDeviceType: sourceDeviceType,
		TextContent:      &content,
		CreatedAt:        now,
	}, nil
}

func (s *Service) List(ctx context.Context, before string, limit int) (Page, error) {
	if limit <= 0 {
		limit = DefaultLimit
	}
	if limit > MaximumLimit {
		limit = MaximumLimit
	}

	query := `
		SELECT m.id, m.type, m.batch_id, m.source_device_id, d.device_type,
		       m.text_content, m.created_at, m.metadata_expires_at,
		       f.id, f.original_filename, f.mime_type, f.size_bytes, f.status,
		       f.expires_at, f.expired_reason, f.thumbnail_key
		FROM messages m
		JOIN devices d ON d.id = m.source_device_id
		LEFT JOIN files f ON f.message_id = m.id
		WHERE m.deleted_at IS NULL`
	arguments := make([]any, 0, 3)
	if strings.TrimSpace(before) != "" {
		cursor, err := decodeCursor(before)
		if err != nil {
			return Page{}, err
		}
		query += ` AND (m.created_at < ? OR (m.created_at = ? AND m.id < ?))`
		arguments = append(arguments, cursor.CreatedAt, cursor.CreatedAt, cursor.ID)
	}
	query += ` ORDER BY m.created_at DESC, m.id DESC LIMIT ?`
	arguments = append(arguments, limit+1)

	rows, err := s.db.QueryContext(ctx, query, arguments...)
	if err != nil {
		return Page{}, fmt.Errorf("query message timeline: %w", err)
	}
	defer rows.Close()

	messages, err := scanMessages(rows)
	if err != nil {
		return Page{}, err
	}
	hasMore := len(messages) > limit
	if hasMore {
		messages = messages[:limit]
	}
	page := Page{Messages: reverseMessages(messages)}
	if hasMore && len(messages) > 0 {
		page.NextBefore, err = encodeCursor(messages[len(messages)-1])
		if err != nil {
			return Page{}, err
		}
	}
	return page, nil
}

func (s *Service) Delete(ctx context.Context, messageID string) error {
	if strings.TrimSpace(messageID) == "" {
		return ErrNotFound
	}
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin message deletion: %w", err)
	}
	defer tx.Rollback()

	var exists int
	if err := tx.QueryRowContext(ctx, `
		SELECT COUNT(*) FROM messages WHERE id = ? AND deleted_at IS NULL
	`, messageID).Scan(&exists); err != nil {
		return fmt.Errorf("check message deletion: %w", err)
	}
	if exists != 1 {
		return ErrNotFound
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM message_search_fts WHERE message_id = ?`, messageID); err != nil {
		return fmt.Errorf("delete message search index: %w", err)
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM messages WHERE id = ?`, messageID); err != nil {
		return fmt.Errorf("delete message: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return fmt.Errorf("commit message deletion: %w", err)
	}
	return nil
}

func (s *Service) Search(ctx context.Context, queryText string) ([]Message, error) {
	matchQuery, err := buildMatchQuery(queryText)
	if err != nil {
		return nil, err
	}
	rows, err := s.db.QueryContext(ctx, `
		SELECT m.id, m.type, m.batch_id, m.source_device_id, d.device_type,
		       m.text_content, m.created_at, m.metadata_expires_at,
		       f.id, f.original_filename, f.mime_type, f.size_bytes, f.status,
		       f.expires_at, f.expired_reason, f.thumbnail_key
		FROM message_search_fts
		JOIN messages m ON m.id = message_search_fts.message_id
		JOIN devices d ON d.id = m.source_device_id
		LEFT JOIN files f ON f.message_id = m.id
		WHERE message_search_fts MATCH ? AND m.deleted_at IS NULL
		ORDER BY bm25(message_search_fts), m.created_at DESC, m.id DESC
		LIMIT 50
	`, matchQuery)
	if err != nil {
		return nil, fmt.Errorf("search messages: %w", err)
	}
	defer rows.Close()
	return scanMessages(rows)
}

func (s *Service) Context(ctx context.Context, messageID string) (Context, error) {
	target, err := s.messageByID(ctx, messageID)
	if err != nil {
		return Context{}, err
	}

	beforeRows, err := s.db.QueryContext(ctx, `
		SELECT m.id, m.type, m.batch_id, m.source_device_id, d.device_type,
		       m.text_content, m.created_at, m.metadata_expires_at,
		       f.id, f.original_filename, f.mime_type, f.size_bytes, f.status,
		       f.expires_at, f.expired_reason, f.thumbnail_key
		FROM messages m JOIN devices d ON d.id = m.source_device_id
		LEFT JOIN files f ON f.message_id = m.id
		WHERE m.deleted_at IS NULL
		  AND (m.created_at < ? OR (m.created_at = ? AND m.id < ?))
		ORDER BY m.created_at DESC, m.id DESC LIMIT 20
	`, formatTime(target.CreatedAt), formatTime(target.CreatedAt), target.ID)
	if err != nil {
		return Context{}, fmt.Errorf("query messages before context: %w", err)
	}
	before, scanErr := scanMessages(beforeRows)
	beforeRows.Close()
	if scanErr != nil {
		return Context{}, scanErr
	}

	afterRows, err := s.db.QueryContext(ctx, `
		SELECT m.id, m.type, m.batch_id, m.source_device_id, d.device_type,
		       m.text_content, m.created_at, m.metadata_expires_at,
		       f.id, f.original_filename, f.mime_type, f.size_bytes, f.status,
		       f.expires_at, f.expired_reason, f.thumbnail_key
		FROM messages m JOIN devices d ON d.id = m.source_device_id
		LEFT JOIN files f ON f.message_id = m.id
		WHERE m.deleted_at IS NULL
		  AND (m.created_at > ? OR (m.created_at = ? AND m.id > ?))
		ORDER BY m.created_at ASC, m.id ASC LIMIT 20
	`, formatTime(target.CreatedAt), formatTime(target.CreatedAt), target.ID)
	if err != nil {
		return Context{}, fmt.Errorf("query messages after context: %w", err)
	}
	after, scanErr := scanMessages(afterRows)
	afterRows.Close()
	if scanErr != nil {
		return Context{}, scanErr
	}

	messages := append(reverseMessages(before), target)
	messages = append(messages, after...)
	return Context{TargetMessageID: target.ID, Messages: messages}, nil
}

func (s *Service) messageByID(ctx context.Context, messageID string) (Message, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT m.id, m.type, m.batch_id, m.source_device_id, d.device_type,
		       m.text_content, m.created_at, m.metadata_expires_at,
		       f.id, f.original_filename, f.mime_type, f.size_bytes, f.status,
		       f.expires_at, f.expired_reason, f.thumbnail_key
		FROM messages m JOIN devices d ON d.id = m.source_device_id
		LEFT JOIN files f ON f.message_id = m.id
		WHERE m.id = ? AND m.deleted_at IS NULL
	`, messageID)
	message, err := scanMessage(row.Scan)
	if errors.Is(err, sql.ErrNoRows) {
		return Message{}, ErrNotFound
	}
	if err != nil {
		return Message{}, fmt.Errorf("query message: %w", err)
	}
	return message, nil
}

func (s *Service) Get(ctx context.Context, messageID string) (Message, error) {
	return s.messageByID(ctx, messageID)
}

func scanMessage(scan func(...any) error) (Message, error) {
	var message Message
	var batchID, textContent, metadataExpiresAt sql.NullString
	var fileID, filename, mimeType, fileStatus, fileExpiresAt, expiredReason, thumbnailKey sql.NullString
	var fileSize sql.NullInt64
	var createdAtRaw string
	if err := scan(
		&message.ID, &message.Type, &batchID, &message.SourceDeviceID,
		&message.SourceDeviceType, &textContent, &createdAtRaw, &metadataExpiresAt,
		&fileID, &filename, &mimeType, &fileSize, &fileStatus, &fileExpiresAt, &expiredReason, &thumbnailKey,
	); err != nil {
		return Message{}, err
	}
	if batchID.Valid {
		message.BatchID = &batchID.String
	}
	if textContent.Valid {
		message.TextContent = &textContent.String
	}
	createdAt, err := time.Parse(time.RFC3339Nano, createdAtRaw)
	if err != nil {
		return Message{}, fmt.Errorf("parse message created_at: %w", err)
	}
	message.CreatedAt = createdAt
	if metadataExpiresAt.Valid {
		parsed, err := time.Parse(time.RFC3339Nano, metadataExpiresAt.String)
		if err != nil {
			return Message{}, fmt.Errorf("parse message metadata_expires_at: %w", err)
		}
		message.MetadataExpiresAt = &parsed
	}
	if fileID.Valid {
		attachment := &Attachment{
			ID: fileID.String, OriginalFilename: filename.String, MIMEType: mimeType.String,
			SizeBytes: fileSize.Int64, Status: fileStatus.String,
			DownloadURL: "/api/v1/files/" + fileID.String + "/download",
		}
		if fileExpiresAt.Valid {
			parsed, err := time.Parse(time.RFC3339Nano, fileExpiresAt.String)
			if err != nil {
				return Message{}, fmt.Errorf("parse file expires_at: %w", err)
			}
			attachment.ExpiresAt = &parsed
		}
		if expiredReason.Valid {
			attachment.ExpiredReason = &expiredReason.String
		}
		if thumbnailKey.Valid {
			attachment.ThumbnailURL = "/api/v1/files/" + fileID.String + "/thumbnail"
		}
		message.File = attachment
	}
	return message, nil
}

func scanMessages(rows *sql.Rows) ([]Message, error) {
	messages := make([]Message, 0)
	for rows.Next() {
		message, err := scanMessage(rows.Scan)
		if err != nil {
			return nil, fmt.Errorf("scan message: %w", err)
		}
		messages = append(messages, message)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate messages: %w", err)
	}
	return messages, nil
}

func reverseMessages(values []Message) []Message {
	result := make([]Message, len(values))
	for index := range values {
		result[len(values)-1-index] = values[index]
	}
	return result
}

type cursorPayload struct {
	CreatedAt string `json:"created_at"`
	ID        string `json:"id"`
}

func encodeCursor(message Message) (string, error) {
	payload, err := json.Marshal(cursorPayload{CreatedAt: formatTime(message.CreatedAt), ID: message.ID})
	if err != nil {
		return "", fmt.Errorf("encode timeline cursor: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(payload), nil
}

func decodeCursor(value string) (cursorPayload, error) {
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimSpace(value))
	if err != nil {
		return cursorPayload{}, ErrInvalidCursor
	}
	var cursor cursorPayload
	if err := json.Unmarshal(raw, &cursor); err != nil || cursor.ID == "" {
		return cursorPayload{}, ErrInvalidCursor
	}
	if _, err := time.Parse(time.RFC3339Nano, cursor.CreatedAt); err != nil {
		return cursorPayload{}, ErrInvalidCursor
	}
	return cursor, nil
}

func buildMatchQuery(value string) (string, error) {
	value = strings.TrimSpace(value)
	if value == "" || len([]byte(value)) > 1024 || !utf8.ValidString(value) {
		return "", ErrInvalidSearch
	}
	var normalized strings.Builder
	for _, character := range value {
		if unicode.IsLetter(character) || unicode.IsDigit(character) {
			normalized.WriteRune(character)
		} else {
			normalized.WriteByte(' ')
		}
	}
	terms := strings.Fields(normalized.String())
	if len(terms) == 0 {
		return "", ErrInvalidSearch
	}
	quoted := make([]string, 0, len(terms))
	for _, term := range terms {
		quoted = append(quoted, `"`+term+`"*`)
	}
	return strings.Join(quoted, " AND "), nil
}

func formatTime(value time.Time) string {
	return value.UTC().Format(timestampFormat)
}
