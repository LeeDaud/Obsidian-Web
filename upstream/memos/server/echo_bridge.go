package server

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"mime"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"time"

	"github.com/labstack/echo/v5"
	"github.com/pkg/errors"

	"github.com/usememos/memos/internal/profile"
	"github.com/usememos/memos/server/auth"
	"github.com/usememos/memos/store"
)

const echoBridgeBodyLimit = 512 << 10

type echoBridgeService struct {
	profile        *profile.Profile
	store          *store.Store
	authenticator  *auth.Authenticator
	client         *http.Client
	attachmentBlob func(context.Context, *store.Attachment) ([]byte, error)
}

type echoBundleFile struct {
	Path   string `json:"path"`
	Base64 string `json:"base64"`
	SHA256 string `json:"sha256"`
}

type echoSubmission struct {
	Instance string `json:"instance"`
	Owner    string `json:"owner"`
	Memo     string `json:"memo"`
	Parent   *struct {
		Memo string `json:"memo"`
	} `json:"parent,omitempty"`
	Delivery struct {
		SubmissionID string           `json:"submissionId"`
		Revision     int64            `json:"revision"`
		Files        []echoBundleFile `json:"files"`
	} `json:"delivery"`
}

func newEchoBridgeService(profile *profile.Profile, store *store.Store, secret string) *echoBridgeService {
	return &echoBridgeService{profile: profile, store: store, authenticator: auth.NewAuthenticator(store, secret), client: &http.Client{Timeout: 20 * time.Second}}
}

func (s *echoBridgeService) registerRoutes(e *echo.Echo) {
	e.GET("/api/echo/v1/status", s.status)
	e.POST("/api/echo/v1/memos/:memoUID/submissions", s.submit)
}

func (s *echoBridgeService) status(c *echo.Context) error {
	return c.JSON(http.StatusOK, map[string]bool{"enabled": s.profile.EchoBridgeURL != ""})
}

func (s *echoBridgeService) submit(c *echo.Context) error {
	if s.profile.EchoBridgeURL == "" {
		return c.JSON(http.StatusNotFound, map[string]string{"error": "Echo bridge is disabled"})
	}
	if c.Request().Header.Get("Authorization") == "" && !sameOrigin(c.Request().Header.Get("Origin"), s.profile.InstanceURL) {
		return c.JSON(http.StatusForbidden, map[string]string{"error": "same-origin request required"})
	}
	user, err := s.authenticator.AuthenticateToUser(c.Request().Context(), c.Request().Header.Get("Authorization"), c.Request().Header.Get("Cookie"))
	if err != nil {
		return errors.Wrap(err, "failed to authenticate Echo submission")
	}
	if user == nil {
		return c.JSON(http.StatusUnauthorized, map[string]string{"error": "authentication required"})
	}
	memoUID := c.Param("memoUID")
	response, err := s.submitMemo(c.Request().Context(), user.ID, memoUID)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	body, err := io.ReadAll(io.LimitReader(response.Body, echoBridgeBodyLimit+1))
	if err != nil {
		return errors.Wrap(err, "failed to read Echo response")
	}
	if len(body) > echoBridgeBodyLimit {
		return errors.New("Echo response exceeds limit")
	}
	c.Response().Header().Set("Content-Type", "application/json")
	return c.Blob(response.StatusCode, "application/json", body)
}

func (s *echoBridgeService) submitSavedMemo(ctx context.Context, userID int32, memoUID string) error {
	if s.profile.EchoBridgeURL == "" {
		return nil
	}
	response, err := s.submitMemo(ctx, userID, memoUID)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	body, readErr := io.ReadAll(io.LimitReader(response.Body, echoBridgeBodyLimit+1))
	if readErr != nil {
		return errors.Wrap(readErr, "failed to read Echo enqueue response")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return errors.Errorf("Echo enqueue failed with status %d: %s", response.StatusCode, strings.TrimSpace(string(body)))
	}
	return nil
}

func (s *echoBridgeService) submitMemo(ctx context.Context, userID int32, memoUID string) (*http.Response, error) {
	memo, err := s.store.GetMemo(ctx, &store.FindMemo{UID: &memoUID})
	if err != nil {
		return nil, errors.Wrap(err, "failed to load memo for Echo submission")
	}
	if memo == nil {
		return nil, errors.New("memo not found")
	}
	if memo.CreatorID != userID {
		return nil, errors.New("memo owner required")
	}
	referenceType := store.MemoRelationReference
	relations, err := s.store.ListMemoRelations(ctx, &store.FindMemoRelation{MemoID: &memo.ID, Type: &referenceType})
	if err != nil {
		return nil, errors.Wrap(err, "failed to load memo relations")
	}
	if len(relations) > 1 {
		return nil, errors.New("Echo continuation supports exactly one parent memo")
	}
	parentUID := ""
	if len(relations) == 1 {
		parent, err := s.store.GetMemo(ctx, &store.FindMemo{ID: &relations[0].RelatedMemoID})
		if err != nil {
			return nil, errors.Wrap(err, "failed to load continuation parent")
		}
		if parent == nil || parent.CreatorID != userID {
			return nil, errors.New("continuation parent owner required")
		}
		parentUID = parent.UID
	}
	attachments, err := s.store.ListAttachments(ctx, &store.FindAttachment{MemoID: &memo.ID})
	if err != nil {
		return nil, errors.Wrap(err, "failed to load memo attachments")
	}
	files := make([]echoAttachment, 0, len(attachments))
	for _, attachment := range attachments {
		if s.attachmentBlob == nil {
			return nil, errors.New("attachment reader is unavailable")
		}
		blob, err := s.attachmentBlob(ctx, attachment)
		if err != nil {
			return nil, errors.Wrap(err, "failed to read memo attachment")
		}
		files = append(files, echoAttachment{UID: attachment.UID, Filename: attachment.Filename, Type: attachment.Type, Blob: blob})
	}
	submission, err := buildEchoSubmission(s.profile.InstanceURL, userID, memo, files, parentUID)
	if err != nil {
		return nil, err
	}
	response, err := s.post(ctx, submission)
	if err != nil {
		return nil, errors.Wrap(err, "failed to submit memo to Echo")
	}
	return response, nil
}

func sameOrigin(origin, instanceURL string) bool {
	left, leftErr := url.Parse(origin)
	right, rightErr := url.Parse(instanceURL)
	return leftErr == nil && rightErr == nil && left.Scheme != "" && left.Scheme == right.Scheme && left.Host == right.Host && left.Path == ""
}

func (s *echoBridgeService) post(ctx context.Context, submission *echoSubmission) (*http.Response, error) {
	for index := range submission.Delivery.Files {
		file := &submission.Delivery.Files[index]
		candidate := strings.TrimPrefix(file.Path, "Memos/")
		candidate = strings.TrimPrefix(candidate, "00_Inbox/")
		if len(candidate) == len("20060102-150405.md") && strings.HasSuffix(candidate, ".md") {
			file.Path = "00_Inbox/" + candidate
		}
	}
	body, err := json.Marshal(submission)
	if err != nil {
		return nil, errors.Wrap(err, "failed to encode Echo submission")
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodPost, s.profile.EchoBridgeURL+"/api/memos/internal/submissions", bytes.NewReader(body))
	if err != nil {
		return nil, errors.Wrap(err, "failed to create Echo request")
	}
	request.Header.Set("Authorization", "Bearer "+s.profile.EchoBridgeToken)
	request.Header.Set("Content-Type", "application/json")
	if s.profile.EchoBridgeHost != "" {
		request.Host = s.profile.EchoBridgeHost
	}
	return s.client.Do(request)
}

type echoAttachment struct {
	UID      string
	Filename string
	Type     string
	Blob     []byte
}

func safeAttachmentExtension(filename, mediaType string) (string, error) {
	extension := strings.TrimPrefix(strings.ToLower(filepath.Ext(filename)), ".")
	if extension != "" && len(extension) <= 10 {
		for _, character := range extension {
			if character < 'a' || character > 'z' {
				if character < '0' || character > '9' {
					extension = ""
					break
				}
			}
		}
	}
	if extension == "" {
		extensions, _ := mime.ExtensionsByType(mediaType)
		if len(extensions) > 0 {
			extension = strings.TrimPrefix(strings.ToLower(extensions[0]), ".")
		}
	}
	if extension == "" || len(extension) > 10 {
		return "", errors.New("attachment extension is unsupported")
	}
	return extension, nil
}

func buildEchoSubmission(instance string, userID int32, memo *store.Memo, attachments []echoAttachment, parentUID ...string) (*echoSubmission, error) {
	contentText := memo.Content
	if len(strings.TrimSpace(memo.Content)) == 0 {
		return nil, errors.New("empty memo cannot be submitted")
	}
	zone := time.FixedZone("Asia/Shanghai", 8*60*60)
	created := time.Unix(memo.CreatedTs, 0).In(zone)
	revision := memo.UpdatedTs
	if revision < 1 {
		revision = memo.CreatedTs
	}
	if revision < 1 {
		return nil, errors.New("memo version is invalid")
	}
	submission := &echoSubmission{Instance: instance, Owner: fmt.Sprintf("users/%d", userID), Memo: "memos/" + memo.UID}
	if len(parentUID) > 0 && parentUID[0] != "" {
		submission.Parent = &struct {
			Memo string `json:"memo"`
		}{Memo: "memos/" + parentUID[0]}
	}
	submission.Delivery.Revision = revision
	for _, attachment := range attachments {
		digest := sha256.Sum256(attachment.Blob)
		extension, err := safeAttachmentExtension(attachment.Filename, attachment.Type)
		if err != nil {
			return nil, err
		}
		target := fmt.Sprintf("attachments/memos/%s/%s.%s", memo.UID, hex.EncodeToString(digest[:]), extension)
		rawPath := "/file/attachments/" + attachment.UID + "/" + attachment.Filename
		encodedPath := "/file/attachments/" + attachment.UID + "/" + url.PathEscape(attachment.Filename)
		contentText = strings.ReplaceAll(contentText, instance+rawPath, target)
		contentText = strings.ReplaceAll(contentText, instance+encodedPath, target)
		contentText = strings.ReplaceAll(contentText, rawPath, target)
		contentText = strings.ReplaceAll(contentText, encodedPath, target)
		if !strings.Contains(contentText, target) {
			if strings.HasPrefix(attachment.Type, "image/") {
				contentText += "\n\n![](" + target + ")"
			} else {
				contentText += "\n\n[" + strings.ReplaceAll(attachment.Filename, "]", "\\]") + "](" + target + ")"
			}
		}
		submission.Delivery.Files = append(submission.Delivery.Files, echoBundleFile{
			Path: target, Base64: base64.StdEncoding.EncodeToString(attachment.Blob), SHA256: hex.EncodeToString(digest[:]),
		})
	}
	content := []byte(contentText)
	if len(content) > 256<<10 {
		return nil, errors.New("memo exceeds Echo text limit")
	}
	digest := sha256.Sum256(content)
	submission.Delivery.SubmissionID = fmt.Sprintf("%s-%d-%s", memo.UID, revision, hex.EncodeToString(digest[:6]))
	submission.Delivery.Files = append([]echoBundleFile{{
		Path:   "00_Inbox/" + created.Format("20060102-150405") + ".md",
		Base64: base64.StdEncoding.EncodeToString(content),
		SHA256: hex.EncodeToString(digest[:]),
	}}, submission.Delivery.Files...)
	return submission, nil
}
