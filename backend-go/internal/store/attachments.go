package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/pam/recap/backend-go/internal/crypto"
)

// Attachment metadata is deliberately metadata-only: the bytes stay in private storage on the
// device. The server keeps the descriptive fields needed to find the file again, sealed where the
// content is user-visible (file name, attributes).
const (
	AttachmentMaxSizeBytes = 10 * 1024 * 1024
	attachmentMaxFileName  = 255
	attachmentMaxStorage   = 1024
)

var attachmentMIMEs = map[string]bool{
	"application/pdf": true,
	"image/jpeg":      true,
	"image/png":       true,
	"image/webp":      true,
	"image/heic":      true,
}

var sha256Pattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

type Attachment struct {
	ID            string
	TransactionID string
	FileName      string
	Mime          string
	SizeBytes     int64
	Sha256        string
	StorageRef    string
	DeviceRef     string
	LocalFileID   string
	Attributes    map[string]any
	Revision      int
	CreatedAt     time.Time
	UpdatedAt     time.Time
	DeletedAt     *time.Time
}

type AttachmentSensitive struct {
	FileName   string         `json:"fileName"`
	Attributes map[string]any `json:"attributes,omitempty"`
}

type AttachmentInput struct {
	TransactionID string
	FileName      string
	Mime          string
	SizeBytes     int64
	Sha256        string
	StorageRef    string
	DeviceRef     string
	LocalFileID   string
	Attributes    map[string]any
}

type AttachmentPatch struct {
	FileName   *string
	StorageRef *string
	Attributes *map[string]any
	Revision   int
}

type AttachmentPage struct {
	Items      []Attachment
	NextCursor string
}

func (s *Store) ensureAttachmentTransaction(ctx context.Context, ownerID, transactionID string) error {
	var count int
	if err := s.DB.QueryRowContext(ctx, `SELECT COUNT(*) FROM transactions
		WHERE owner_id = ? AND id = ? AND deleted_at IS NULL`, ownerID, transactionID).Scan(&count); err != nil {
		return err
	}
	if count != 1 {
		return ErrNotFound
	}
	return nil
}

func normalizeAttachmentInput(input AttachmentInput) (AttachmentInput, error) {
	input.FileName = strings.TrimSpace(input.FileName)
	if input.FileName == "" || len(input.FileName) > attachmentMaxFileName {
		return AttachmentInput{}, fmt.Errorf("fileName must be 1..255 characters")
	}
	if strings.ContainsAny(input.FileName, "/\\\x00\r\n") {
		return AttachmentInput{}, fmt.Errorf("fileName must not contain path separators or control characters")
	}
	input.Mime = strings.ToLower(strings.TrimSpace(input.Mime))
	if !attachmentMIMEs[input.Mime] {
		return AttachmentInput{}, fmt.Errorf("invalid mimeType; allowed: application/pdf, image/jpeg, image/png, image/webp, image/heic")
	}
	if input.SizeBytes <= 0 || input.SizeBytes > AttachmentMaxSizeBytes {
		return AttachmentInput{}, fmt.Errorf("sizeBytes must be between 1 and %d", AttachmentMaxSizeBytes)
	}
	input.Sha256 = strings.ToLower(strings.TrimSpace(input.Sha256))
	if !sha256Pattern.MatchString(input.Sha256) {
		return AttachmentInput{}, fmt.Errorf("sha256 must be 64 lowercase hex characters")
	}
	ref, err := normalizeStorageRef(input.StorageRef)
	if err != nil {
		return AttachmentInput{}, err
	}
	input.StorageRef = ref
	input.DeviceRef = strings.TrimSpace(input.DeviceRef)
	input.LocalFileID = strings.TrimSpace(input.LocalFileID)
	if len(input.DeviceRef) > 200 || len(input.LocalFileID) > 200 {
		return AttachmentInput{}, fmt.Errorf("deviceRef/localFileId must be at most 200 characters")
	}
	if err := validateAttributes(input.Attributes); err != nil {
		return AttachmentInput{}, err
	}
	return input, nil
}

// normalizeStorageRef accepts only a device-relative marker. Absolute paths, Windows paths, URI
// schemes and any traversal segment are rejected before the value can reach storage.
func normalizeStorageRef(raw string) (string, error) {
	ref := strings.TrimSpace(raw)
	if ref == "" || len(ref) > attachmentMaxStorage {
		return "", fmt.Errorf("storageRef must be 1..%d characters", attachmentMaxStorage)
	}
	if strings.HasPrefix(ref, "/") || strings.HasPrefix(ref, "~") {
		return "", fmt.Errorf("storageRef must be relative")
	}
	if strings.ContainsAny(ref, "\\\x00\r\n") {
		return "", fmt.Errorf("invalid storageRef")
	}
	if strings.Contains(ref, "://") || strings.Contains(strings.ToLower(ref), "%2e") {
		return "", fmt.Errorf("invalid storageRef")
	}
	for _, segment := range strings.Split(ref, "/") {
		if segment == "" || segment == "." || segment == ".." {
			return "", fmt.Errorf("storageRef must not contain traversal segments")
		}
	}
	return ref, nil
}

func (s *Store) CreateAttachment(ctx context.Context, ownerID string, input AttachmentInput, now time.Time) (Attachment, error) {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return Attachment{}, err
	}
	input, err := normalizeAttachmentInput(input)
	if err != nil {
		return Attachment{}, err
	}
	if err := s.ensureAttachmentTransaction(ctx, ownerID, input.TransactionID); err != nil {
		return Attachment{}, err
	}
	id := NewID("att")
	sealed, err := crypto.SealJSON(AttachmentSensitive{FileName: input.FileName,
		Attributes: input.Attributes}, s.DataKey, s.KeyVersion, ownerID, "attachment")
	if err != nil {
		return Attachment{}, err
	}
	_, err = s.DB.ExecContext(ctx, `INSERT INTO attachments
		(id, owner_id, transaction_id, local_file_id, device_ref, mime, size_bytes, sha256,
		 storage_ref, sensitive_ciphertext, sensitive_iv, sensitive_tag, key_version, revision,
		 created_at_ms, created_at, updated_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)`,
		id, ownerID, input.TransactionID, input.LocalFileID, input.DeviceRef, input.Mime,
		input.SizeBytes, input.Sha256, input.StorageRef, sealed.Ciphertext, sealed.IV, sealed.Tag,
		s.KeyVersion, now.UnixMilli(), nowString(now), nowString(now))
	if err != nil {
		if isUnique(err.Error()) {
			return Attachment{}, ErrConflict
		}
		return Attachment{}, err
	}
	return s.GetAttachment(ctx, ownerID, id)
}

const attachmentSelect = `SELECT a.id, a.transaction_id, a.mime, a.size_bytes, a.sha256,
	a.storage_ref, a.device_ref, a.local_file_id, a.sensitive_ciphertext, a.sensitive_iv,
	a.sensitive_tag, a.key_version, a.revision, a.created_at, a.updated_at, a.deleted_at
	FROM attachments a`

func (s *Store) scanAttachment(ctx context.Context, scanner rowScanner, ownerID string) (Attachment, error) {
	var attachment Attachment
	var ciphertext, iv, tag []byte
	var keyVersion int
	var createdRaw, updatedRaw string
	var deletedRaw sql.NullString
	if err := scanner.Scan(&attachment.ID, &attachment.TransactionID, &attachment.Mime,
		&attachment.SizeBytes, &attachment.Sha256, &attachment.StorageRef, &attachment.DeviceRef,
		&attachment.LocalFileID, &ciphertext, &iv, &tag, &keyVersion, &attachment.Revision,
		&createdRaw, &updatedRaw, &deletedRaw); err != nil {
		return Attachment{}, err
	}
	var sensitive AttachmentSensitive
	if err := crypto.OpenJSON(crypto.Sealed{Ciphertext: ciphertext, IV: iv, Tag: tag,
		KeyVersion: keyVersion}, s.DataKey, ownerID, "attachment", &sensitive); err != nil {
		return Attachment{}, err
	}
	attachment.FileName = sensitive.FileName
	attachment.Attributes = sensitive.Attributes
	attachment.CreatedAt = parseTime(createdRaw)
	attachment.UpdatedAt = parseTime(updatedRaw)
	if deletedRaw.Valid {
		deleted := parseTime(deletedRaw.String)
		attachment.DeletedAt = &deleted
	}
	return attachment, nil
}

func (s *Store) GetAttachment(ctx context.Context, ownerID, id string) (Attachment, error) {
	row := s.DB.QueryRowContext(ctx, attachmentSelect+
		` WHERE a.owner_id = ? AND a.id = ? AND a.deleted_at IS NULL`, ownerID, id)
	attachment, err := s.scanAttachment(ctx, row, ownerID)
	if errors.Is(err, sql.ErrNoRows) {
		return Attachment{}, ErrNotFound
	}
	return attachment, err
}

func attachmentCursorScope(ownerID, transactionID, mime, q string) string {
	sum := sha256.Sum256([]byte(strings.Join([]string{ownerID, transactionID,
		strings.ToLower(strings.TrimSpace(mime)), strings.ToLower(strings.TrimSpace(q))}, "\x00")))
	return hex.EncodeToString(sum[:8])
}

func (s *Store) ListAttachments(ctx context.Context, ownerID, transactionID, mime, q string,
	limit int, cursor string) (AttachmentPage, error) {
	if limit <= 0 {
		limit = 50
	}
	if limit > 100 {
		limit = 100
	}
	mime = strings.ToLower(strings.TrimSpace(mime))
	if mime != "" && !attachmentMIMEs[mime] {
		return AttachmentPage{}, fmt.Errorf("invalid mimeType filter")
	}
	q = NormalizeSearchTerm(q)
	scope := attachmentCursorScope(ownerID, transactionID, mime, q)
	sqlText := attachmentSelect + ` WHERE a.owner_id = ? AND a.deleted_at IS NULL`
	args := []any{ownerID}
	if transactionID != "" {
		sqlText += ` AND a.transaction_id = ?`
		args = append(args, transactionID)
	}
	if mime != "" {
		sqlText += ` AND a.mime = ?`
		args = append(args, mime)
	}
	if cursor != "" {
		ms, id, cursorScope, err := decodeCursor(cursor)
		if err != nil || cursorScope != scope {
			return AttachmentPage{}, ErrInvalidCursor
		}
		sqlText += ` AND (a.created_at_ms < ? OR (a.created_at_ms = ? AND a.id < ?))`
		args = append(args, ms, ms, id)
	}
	sqlText += ` ORDER BY a.created_at_ms DESC, a.id DESC LIMIT ?`
	// q filters the sealed file name, so only then does the window have to be wider than one page.
	// 1000 extra rows is plenty for a month's attachment metadata, and the cursor still advances
	// by the last scanned row when the window is exhausted.
	fetchLimit := limit + 1
	if q != "" {
		fetchLimit = limit + 1000
	}
	args = append(args, fetchLimit)
	rows, err := s.DB.QueryContext(ctx, sqlText, args...)
	if err != nil {
		return AttachmentPage{}, err
	}
	defer rows.Close()
	page := AttachmentPage{}
	scanned := 0
	var lastScanned Attachment
	for rows.Next() {
		attachment, err := s.scanAttachment(ctx, rows, ownerID)
		if err != nil {
			return AttachmentPage{}, err
		}
		scanned++
		lastScanned = attachment
		if q != "" && !containsFold(attachment.FileName, q) && !containsFold(attachment.Sha256, q) {
			continue
		}
		page.Items = append(page.Items, attachment)
		if len(page.Items) > limit {
			page.Items = page.Items[:limit]
			last := page.Items[len(page.Items)-1]
			page.NextCursor = encodeCursor(last.CreatedAt.UnixMilli(), last.ID, scope)
			return page, nil
		}
	}
	if err := rows.Err(); err != nil {
		return AttachmentPage{}, err
	}
	if q != "" && scanned >= fetchLimit {
		page.NextCursor = encodeCursor(lastScanned.CreatedAt.UnixMilli(), lastScanned.ID, scope)
	}
	return page, nil
}

func (s *Store) UpdateAttachment(ctx context.Context, ownerID, id string, patch AttachmentPatch, now time.Time) (Attachment, error) {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return Attachment{}, err
	}
	current, err := s.GetAttachment(ctx, ownerID, id)
	if err != nil {
		return Attachment{}, err
	}
	if current.Revision != patch.Revision {
		return Attachment{}, ErrRevisionConflict
	}
	fileName := current.FileName
	if patch.FileName != nil {
		fileName = strings.TrimSpace(*patch.FileName)
	}
	storageRef := current.StorageRef
	if patch.StorageRef != nil {
		storageRef, err = normalizeStorageRef(*patch.StorageRef)
		if err != nil {
			return Attachment{}, err
		}
	}
	attributes := current.Attributes
	if patch.Attributes != nil {
		attributes = *patch.Attributes
	}
	normalized, err := normalizeAttachmentInput(AttachmentInput{
		TransactionID: current.TransactionID, FileName: fileName, Mime: current.Mime,
		SizeBytes: current.SizeBytes, Sha256: current.Sha256, StorageRef: storageRef,
		DeviceRef: current.DeviceRef, LocalFileID: current.LocalFileID, Attributes: attributes,
	})
	if err != nil {
		return Attachment{}, err
	}
	sealed, err := crypto.SealJSON(AttachmentSensitive{FileName: normalized.FileName,
		Attributes: normalized.Attributes}, s.DataKey, s.KeyVersion, ownerID, "attachment")
	if err != nil {
		return Attachment{}, err
	}
	result, err := s.DB.ExecContext(ctx, `UPDATE attachments SET storage_ref = ?,
		sensitive_ciphertext = ?, sensitive_iv = ?, sensitive_tag = ?, key_version = ?,
		revision = revision + 1, updated_at = ?
		WHERE owner_id = ? AND id = ? AND revision = ? AND deleted_at IS NULL`,
		normalized.StorageRef, sealed.Ciphertext, sealed.IV, sealed.Tag, s.KeyVersion,
		nowString(now), ownerID, id, patch.Revision)
	if err != nil {
		if isUnique(err.Error()) {
			return Attachment{}, ErrConflict
		}
		return Attachment{}, err
	}
	if changed, _ := result.RowsAffected(); changed != 1 {
		return Attachment{}, ErrRevisionConflict
	}
	return s.GetAttachment(ctx, ownerID, id)
}

// DeleteAttachment soft-deletes metadata. expectedRevision < 0 means unconditional; 0 or more is
// an optional compare-and-swap for clients that already know the row revision.
func (s *Store) DeleteAttachment(ctx context.Context, ownerID, id string, expectedRevision int, now time.Time) error {
	if err := s.ensureNoDeletion(ctx, ownerID); err != nil {
		return err
	}
	sqlText := `UPDATE attachments SET deleted_at = ?, revision = revision + 1, updated_at = ?
		WHERE owner_id = ? AND id = ? AND deleted_at IS NULL`
	args := []any{nowString(now), nowString(now), ownerID, id}
	if expectedRevision >= 0 {
		sqlText += ` AND revision = ?`
		args = append(args, expectedRevision)
	}
	result, err := s.DB.ExecContext(ctx, sqlText, args...)
	if err != nil {
		return err
	}
	changed, _ := result.RowsAffected()
	if changed == 1 {
		return nil
	}
	// Distinguish a missing row from a stale revision.
	if _, err := s.GetAttachment(ctx, ownerID, id); errors.Is(err, ErrNotFound) {
		return ErrNotFound
	}
	return ErrRevisionConflict
}
