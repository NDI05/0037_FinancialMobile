package httpapi

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/pam/recap/backend-go/internal/store"
)

// Attachment metadata only. The file bytes never reach this server; the client keeps them in
// private storage and sends a relative storageRef plus a content hash so another install can
// detect whether it already has the same file.

type attachmentCreate struct {
	TransactionID string         `json:"transactionId"`
	FileName      string         `json:"fileName"`
	MimeType      string         `json:"mimeType"`
	SizeBytes     int64          `json:"sizeBytes"`
	Sha256        string         `json:"sha256"`
	StorageRef    string         `json:"storageRef"`
	DeviceRef     string         `json:"deviceRef"`
	LocalFileID   string         `json:"localFileId"`
	Attributes    map[string]any `json:"attributes"`
}

type attachmentPatch struct {
	FileName   *string         `json:"fileName"`
	StorageRef *string         `json:"storageRef"`
	Attributes *map[string]any `json:"attributes"`
	Revision   int             `json:"revision"`
}

func attachmentJSON(attachment store.Attachment) map[string]any {
	attributes := attachment.Attributes
	if attributes == nil {
		attributes = map[string]any{}
	}
	return map[string]any{
		"id": attachment.ID, "transactionId": attachment.TransactionID,
		"fileName": attachment.FileName, "mimeType": attachment.Mime,
		"sizeBytes": attachment.SizeBytes, "sha256": attachment.Sha256,
		"storageRef": attachment.StorageRef, "deviceRef": nullString(attachment.DeviceRef),
		"localFileId": nullString(attachment.LocalFileID), "attributes": attributes,
		"revision": attachment.Revision, "createdAt": attachment.CreatedAt,
		"updatedAt": attachment.UpdatedAt,
	}
}

func (s *Server) createAttachment(w http.ResponseWriter, r *http.Request) {
	var body attachmentCreate
	if !decodeJSON(w, r, &body) {
		return
	}
	info, _ := authFrom(r)
	attachment, err := s.Store.CreateAttachment(r.Context(), info.OwnerID, store.AttachmentInput{
		TransactionID: body.TransactionID, FileName: body.FileName, Mime: body.MimeType,
		SizeBytes: body.SizeBytes, Sha256: body.Sha256, StorageRef: body.StorageRef,
		DeviceRef: body.DeviceRef, LocalFileID: body.LocalFileID, Attributes: body.Attributes,
	}, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusCreated, attachmentJSON(attachment))
}

func (s *Server) listAttachments(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	query := r.URL.Query()
	limit, _ := strconv.Atoi(query.Get("limit"))
	page, err := s.Store.ListAttachments(r.Context(), info.OwnerID, query.Get("transactionId"),
		query.Get("mime"), query.Get("q"), limit, query.Get("cursor"))
	if err != nil {
		if errors.Is(err, store.ErrInvalidCursor) {
			writeError(w, http.StatusBadRequest, "invalid_cursor",
				"cursor was issued for a different query; restart from the first page", correlationOf(r))
			return
		}
		s.mapWriteError(w, r, err)
		return
	}
	items := make([]map[string]any, 0, len(page.Items))
	for _, attachment := range page.Items {
		items = append(items, attachmentJSON(attachment))
	}
	response := map[string]any{"items": items}
	if page.NextCursor != "" {
		response["nextCursor"] = page.NextCursor
	}
	writeJSON(w, http.StatusOK, response)
}

func (s *Server) getAttachment(w http.ResponseWriter, r *http.Request) {
	info, _ := authFrom(r)
	attachment, err := s.Store.GetAttachment(r.Context(), info.OwnerID, r.PathValue("id"))
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, attachmentJSON(attachment))
}

func (s *Server) updateAttachment(w http.ResponseWriter, r *http.Request) {
	var body attachmentPatch
	if !decodeJSON(w, r, &body) {
		return
	}
	info, _ := authFrom(r)
	attachment, err := s.Store.UpdateAttachment(r.Context(), info.OwnerID, r.PathValue("id"),
		store.AttachmentPatch{FileName: body.FileName, StorageRef: body.StorageRef,
			Attributes: body.Attributes, Revision: body.Revision}, s.Now())
	if err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	writeJSON(w, http.StatusOK, attachmentJSON(attachment))
}

func (s *Server) deleteAttachment(w http.ResponseWriter, r *http.Request) {
	expected := -1
	if r.Header.Get("If-Match") != "" {
		expected = revisionHeader(r)
	}
	if raw := strings.TrimSpace(r.URL.Query().Get("revision")); raw != "" {
		expected, _ = strconv.Atoi(raw)
	}
	info, _ := authFrom(r)
	if err := s.Store.DeleteAttachment(r.Context(), info.OwnerID, r.PathValue("id"),
		expected, s.Now()); err != nil {
		s.mapWriteError(w, r, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
