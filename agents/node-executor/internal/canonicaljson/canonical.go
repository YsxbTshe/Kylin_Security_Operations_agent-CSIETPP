package canonicaljson

import "github.com/cyberphone/json-canonicalization/go/src/webpki.org/jsoncanonicalizer"

func Transform(value []byte) ([]byte, error) {
	return jsoncanonicalizer.Transform(value)
}
