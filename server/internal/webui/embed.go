package webui

import (
	"bytes"
	"embed"
	"fmt"
	"io/fs"
	"mime"
	"net/http"
	"path"
	"strings"
	"time"
)

// dist contains the Vite production build. The Docker build replaces the
// checked-in placeholder with the real build before compiling the Go binary.
//
//go:embed dist
var embeddedAssets embed.FS

type handler struct {
	assets fs.FS
	index  []byte
}

func NewHandler() (http.Handler, error) {
	assets, err := fs.Sub(embeddedAssets, "dist")
	if err != nil {
		return nil, fmt.Errorf("open embedded web assets: %w", err)
	}
	index, err := fs.ReadFile(assets, "index.html")
	if err != nil {
		return nil, fmt.Errorf("read embedded index.html: %w", err)
	}

	return &handler{assets: assets, index: index}, nil
}

func (h *handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		http.Error(w, http.StatusText(http.StatusMethodNotAllowed), http.StatusMethodNotAllowed)
		return
	}

	assetPath := strings.TrimPrefix(path.Clean("/"+r.URL.Path), "/")
	if assetPath == "." || assetPath == "" {
		h.serveIndex(w, r)
		return
	}

	if info, err := fs.Stat(h.assets, assetPath); err == nil && !info.IsDir() {
		contents, readErr := fs.ReadFile(h.assets, assetPath)
		if readErr != nil {
			http.Error(w, http.StatusText(http.StatusInternalServerError), http.StatusInternalServerError)
			return
		}
		if contentType := mime.TypeByExtension(path.Ext(assetPath)); contentType != "" {
			w.Header().Set("Content-Type", contentType)
		}
		if strings.HasPrefix(assetPath, "assets/") {
			w.Header().Set("Cache-Control", "public, max-age=31536000, immutable")
		}
		http.ServeContent(w, r, assetPath, info.ModTime(), bytes.NewReader(contents))
		return
	}

	// Client-side routes are served by the same SPA entry point. API and
	// WebSocket paths are intercepted by the HTTP router before reaching here.
	h.serveIndex(w, r)
}

func (h *handler) serveIndex(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-cache")
	http.ServeContent(w, r, "index.html", time.Time{}, bytes.NewReader(h.index))
}
