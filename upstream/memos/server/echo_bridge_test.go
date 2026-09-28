package server

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/require"

	"github.com/usememos/memos/internal/profile"
	"github.com/usememos/memos/store"
)

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

func TestBuildEchoSubmissionRejectsEmptyMemo(t *testing.T) {
	_, err := buildEchoSubmission("https://memos.example.com", 7, &store.Memo{UID: "memo-uid", CreatedTs: 1, UpdatedTs: 1, Content: "  "}, nil)
	require.ErrorContains(t, err, "empty memo")
}

func TestBuildEchoSubmissionIncludesAndRewritesImage(t *testing.T) {
	memo := &store.Memo{UID: "memo-uid", CreatedTs: 1789992000, UpdatedTs: 1789992061,
		Content: "图片：![](/file/attachments/image-uid/photo.png)"}
	submission, err := buildEchoSubmission("https://memos.example.com", 7, memo, []echoAttachment{{
		UID: "image-uid", Filename: "photo.png", Type: "image/png", Blob: []byte{0x89, 0x50, 0x4e, 0x47},
	}})
	require.NoError(t, err)
	require.Len(t, submission.Delivery.Files, 2)
	require.Equal(t, "00_Inbox/20260921-200000.md", submission.Delivery.Files[0].Path)
	require.Regexp(t, `^attachments/memos/memo-uid/[0-9a-f]{64}\.png$`, submission.Delivery.Files[1].Path)
	decoded, err := base64.StdEncoding.DecodeString(submission.Delivery.Files[0].Base64)
	require.NoError(t, err)
	require.Contains(t, string(decoded), submission.Delivery.Files[1].Path)
	require.NotContains(t, string(decoded), "/file/attachments/")
}

func TestSameOrigin(t *testing.T) {
	require.True(t, sameOrigin("https://memos.example.com", "https://memos.example.com/app"))
	require.False(t, sameOrigin("https://evil.example", "https://memos.example.com"))
	require.False(t, sameOrigin("", "https://memos.example.com"))
}


