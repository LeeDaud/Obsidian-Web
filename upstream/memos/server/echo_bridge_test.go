package server

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/labstack/echo/v5"
	"github.com/stretchr/testify/require"

	"github.com/usememos/memos/internal/profile"
	"github.com/usememos/memos/server/auth"
	"github.com/usememos/memos/store"
	teststore "github.com/usememos/memos/store/test"
)

func TestMemoStatusesAreOwnerScopedAndReadOnly(t *testing.T) {
	ctx := context.Background()
	ts := teststore.NewTestingStore(ctx, t)
	t.Cleanup(func() { _ = ts.Close() })
	user, err := ts.CreateUser(ctx, &store.User{Username: "status-owner", Role: store.RoleAdmin, RowStatus: store.Normal})
	require.NoError(t, err)
	other, err := ts.CreateUser(ctx, &store.User{Username: "status-other", Role: store.RoleUser, RowStatus: store.Normal})
	require.NoError(t, err)
	_, err = ts.CreateMemo(ctx, &store.Memo{UID: "owned", CreatorID: user.ID, Content: "saved", Visibility: store.Private})
	require.NoError(t, err)
	_, err = ts.CreateMemo(ctx, &store.Memo{UID: "foreign", CreatorID: other.ID, Content: "private", Visibility: store.Private})
	require.NoError(t, err)
	calls := 0
	remote := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls++
		require.Equal(t, "/api/memos/internal/statuses", r.URL.Path)
		require.Equal(t, "Bearer service-secret", r.Header.Get("Authorization"))
		var body map[string]any
		require.NoError(t, json.NewDecoder(r.Body).Decode(&body))
		require.NotContains(t, body, "delivery")
		_, _ = w.Write([]byte(`[{"memo":"memos/owned","state":"unknown"}]`))
	}))
	defer remote.Close()
	service := newEchoBridgeService(&profile.Profile{EchoBridgeURL: remote.URL, EchoBridgeToken: "service-secret", InstanceURL: "https://memos.example.com"}, ts, "test-secret")
	service.client = remote.Client()
	e := echo.New()
	service.registerRoutes(e)
	token, _, err := auth.GenerateAccessTokenV2(user.ID, user.Username, "ADMIN", "ACTIVE", []byte("test-secret"))
	require.NoError(t, err)
	request := func(body, bearer, origin string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(http.MethodPost, "/api/echo/v1/memo-statuses", strings.NewReader(body))
		r.Header.Set("Content-Type", "application/json")
		r.Header.Set("Origin", origin)
		if bearer != "" {
			r.Header.Set("Authorization", "Bearer "+bearer)
		}
		w := httptest.NewRecorder()
		e.ServeHTTP(w, r)
		return w
	}
	require.Equal(t, http.StatusForbidden, request(`{"memoUIDs":["owned"]}`, "", "https://evil.example").Code)
	require.Equal(t, http.StatusUnauthorized, request(`{"memoUIDs":["owned"]}`, "", "https://memos.example.com").Code)
	require.Equal(t, http.StatusNotFound, request(`{"memoUIDs":["owned","foreign"]}`, token, "").Code)
	require.Zero(t, calls)
	require.Equal(t, http.StatusBadRequest, request(`{"memoUIDs":[]}`, token, "").Code)
	result := request(`{"memoUIDs":["owned"]}`, token, "")
	require.Equal(t, http.StatusOK, result.Code)
	require.Equal(t, "no-store", result.Header().Get("Cache-Control"))
	require.NotContains(t, result.Body.String(), "service-secret")
	require.NotContains(t, result.Body.String(), "saved")
	require.Contains(t, result.Body.String(), "sourceHash")
	require.Equal(t, 1, calls)
}

func TestPostNormalizesLegacyMarkdownPrefix(t *testing.T) {
	var received echoSubmission
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		body, err := io.ReadAll(request.Body)
		require.NoError(t, err)
		require.NoError(t, json.Unmarshal(body, &received))
		response.Header().Set("Content-Type", "application/json")
		_, _ = response.Write([]byte(`{"state":"pending"}`))
	}))
	defer server.Close()

	service := &echoBridgeService{profile: &profile.Profile{EchoBridgeURL: server.URL}, client: server.Client()}
	submission := &echoSubmission{}
	submission.Delivery.Files = []echoBundleFile{{Path: "Memos/20260928-173655.md"}}
	result, err := service.post(context.Background(), submission)
	require.NoError(t, err)
	_ = result.Body.Close()
	require.Equal(t, "00_Inbox/20260928-173655.md", received.Delivery.Files[0].Path)
}

func TestBuildEchoSubmission(t *testing.T) {
	memo := &store.Memo{UID: "memo-uid", CreatedTs: 1789992000, UpdatedTs: 1789992061, Content: "中文记录"}
	submission, err := buildEchoSubmission("https://memos.example.com", 7, memo, nil)
	require.NoError(t, err)
	require.Equal(t, "https://memos.example.com", submission.Instance)
	require.Equal(t, "users/7", submission.Owner)
	require.Equal(t, "memos/memo-uid", submission.Memo)
	require.Equal(t, int64(1789992061), submission.Delivery.Revision)
	require.Equal(t, "00_Inbox/20260921-200000.md", submission.Delivery.Files[0].Path)
	decoded, err := base64.StdEncoding.DecodeString(submission.Delivery.Files[0].Base64)
	require.NoError(t, err)
	require.Equal(t, "中文记录", string(decoded))
}

func TestBuildEchoSubmissionAddsTodoMetadata(t *testing.T) {
	memo := &store.Memo{UID: "todo-uid", CreatedTs: 1789992000, UpdatedTs: 1789992061, Content: "- [ ] 联系设计师\n  补充说明"}
	submission, err := buildEchoSubmission("https://memos.example.com", 7, memo, nil)
	require.NoError(t, err)
	decoded, err := base64.StdEncoding.DecodeString(submission.Delivery.Files[0].Base64)
	require.NoError(t, err)
	require.Equal(t, "---\ntype: todo\nstatus: open\ncreated: 2026-09-21 20:00\nsource: memos\n---\n\n- [ ] 联系设计师\n  补充说明", string(decoded))
}

func TestBuildEchoSubmissionMarksCompletedTodo(t *testing.T) {
	memo := &store.Memo{UID: "todo-uid", CreatedTs: 1789992000, UpdatedTs: 1789992061, Content: "- [x] 已完成"}
	submission, err := buildEchoSubmission("https://memos.example.com", 7, memo, nil)
	require.NoError(t, err)
	decoded, err := base64.StdEncoding.DecodeString(submission.Delivery.Files[0].Base64)
	require.NoError(t, err)
	require.Contains(t, string(decoded), "status: done")
}

func TestBuildEchoSubmissionIncludesContinuationParent(t *testing.T) {
	memo := &store.Memo{UID: "child-uid", CreatedTs: 1789992000, UpdatedTs: 1789992061, Content: "续写内容"}
	submission, err := buildEchoSubmission("https://memos.example.com", 7, memo, nil, "parent-uid")
	require.NoError(t, err)
	require.NotNil(t, submission.Parent)
	require.Equal(t, "memos/parent-uid", submission.Parent.Memo)
	decoded, err := base64.StdEncoding.DecodeString(submission.Delivery.Files[0].Base64)
	require.NoError(t, err)
	require.Equal(t, "续写内容", string(decoded), "bridge must leave Obsidian path resolution to Echo")
}

func TestBuildEchoSubmissionRejectsEmptyMemo(t *testing.T) {
	_, err := buildEchoSubmission("https://memos.example.com", 7, &store.Memo{UID: "memo-uid", CreatedTs: 1, UpdatedTs: 1, Content: "  "}, nil)
	require.ErrorContains(t, err, "empty memo")
}

func TestBuildEchoSubmissionIncludesAndRewritesImage(t *testing.T) {
	memo := &store.Memo{UID: "memo-uid", CreatedTs: 1789992000, UpdatedTs: 1789992061,
		Content: "图片：![](/file/attachments/image-uid/photo.png)"}
	submission, err := buildEchoSubmission("https://memos.example.com", 7, memo, []echoAttachment{{
		UID: "image-uid", Filename: "photo.png", Type: "image/png", Blob: []byte{0x89, 0x50, 0x4e, 0x47}, CreatedTs: 1789992000,
	}})
	require.NoError(t, err)
	require.Len(t, submission.Delivery.Files, 2)
	require.Equal(t, "00_Inbox/20260921-200000.md", submission.Delivery.Files[0].Path)
	require.Equal(t, "attachments/memos/memo-uid/20260921-200000.png", submission.Delivery.Files[1].Path)
	decoded, err := base64.StdEncoding.DecodeString(submission.Delivery.Files[0].Base64)
	require.NoError(t, err)
	require.Contains(t, string(decoded), submission.Delivery.Files[1].Path)
	require.NotContains(t, string(decoded), "/file/attachments/")
}

func TestBuildEchoSubmissionRewritesManagedAttachmentURLVariants(t *testing.T) {
	variants := []string{
		"![](/file/attachments/image-uid)",
		"![](/file/attachments/image-uid/photo%20one.png?download=1)",
		"![](https://memos.example.com/file/attachments/image-uid/photo%20one.png)",
	}
	for _, content := range variants {
		t.Run(content, func(t *testing.T) {
			memo := &store.Memo{UID: "memo-uid", CreatedTs: 1789992000, UpdatedTs: 1789992061, Content: content}
			submission, err := buildEchoSubmission("https://memos.example.com", 7, memo, []echoAttachment{{
				UID: "image-uid", Filename: "photo one.png", Type: "image/png", Blob: []byte("image"), CreatedTs: 1789992000,
			}})
			require.NoError(t, err)
			decoded, err := base64.StdEncoding.DecodeString(submission.Delivery.Files[0].Base64)
			require.NoError(t, err)
			require.NotContains(t, string(decoded), "/file/attachments/")
			require.Equal(t, 1, strings.Count(string(decoded), submission.Delivery.Files[1].Path))
		})
	}
}

func TestRewriteManagedAttachmentPathLeavesAnotherOriginUntouched(t *testing.T) {
	content := "![](https://evil.example/file/attachments/image-uid/photo.png)"
	require.Equal(t, content, rewriteManagedAttachmentPath(content, "https://memos.example.com", "image-uid", "attachments/photo.png"))
}

func TestBuildEchoSubmissionAddsStableSuffixForAttachmentsCreatedTogether(t *testing.T) {
	memo := &store.Memo{UID: "memo-uid", CreatedTs: 1789992000, UpdatedTs: 1789992061,
		Content: "![](/file/attachments/image-b/b.png)\n![](/file/attachments/image-a/a.png)"}
	submission, err := buildEchoSubmission("https://memos.example.com", 7, memo, []echoAttachment{
		{UID: "image-b", Filename: "b.png", Type: "image/png", Blob: []byte("b"), CreatedTs: 1789992000},
		{UID: "image-a", Filename: "a.png", Type: "image/png", Blob: []byte("a"), CreatedTs: 1789992000},
	})
	require.NoError(t, err)
	require.Equal(t, "attachments/memos/memo-uid/20260921-200000.png", submission.Delivery.Files[1].Path)
	require.Equal(t, "attachments/memos/memo-uid/20260921-200000-02.png", submission.Delivery.Files[2].Path)
}

func TestSameOrigin(t *testing.T) {
	require.True(t, sameOrigin("https://memos.example.com", "https://memos.example.com/app"))
	require.False(t, sameOrigin("https://evil.example", "https://memos.example.com"))
	require.False(t, sameOrigin("", "https://memos.example.com"))
}
