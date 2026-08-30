package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"transdot.local/transfer-assistant/server/internal/maintenance"
)

func main() {
	if err := run(context.Background(), os.Args[1:]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run(ctx context.Context, args []string) error {
	if len(args) == 0 { return fmt.Errorf("usage: transdot-maintenance <inspect|verify> --data-dir PATH [--max-schema N] --json") }
	command := args[0]
	flags := flag.NewFlagSet(command, flag.ContinueOnError)
	flags.SetOutput(os.Stderr)
	dataDir := flags.String("data-dir", "", "TransDot data directory")
	jsonOutput := flags.Bool("json", false, "emit JSON")
	maxSchema := flags.Int("max-schema", 11, "maximum supported schema version")
	if err := flags.Parse(args[1:]); err != nil { return err }
	if *dataDir == "" { return fmt.Errorf("--data-dir is required") }
	if !*jsonOutput { return fmt.Errorf("--json is required") }

	var value any
	var err error
	switch command {
	case "inspect":
		value, err = maintenance.Inspect(ctx, *dataDir)
	case "verify":
		value, err = maintenance.Verify(ctx, *dataDir, *maxSchema)
	default:
		return fmt.Errorf("unknown command %q", command)
	}
	if err != nil { return err }
	encoder := json.NewEncoder(os.Stdout)
	encoder.SetEscapeHTML(false)
	return encoder.Encode(value)
}
