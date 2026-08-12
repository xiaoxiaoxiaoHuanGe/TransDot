package migrations

import "embed"

// Files contains every numbered SQL migration applied at server startup.
//
//go:embed *.sql
var Files embed.FS
