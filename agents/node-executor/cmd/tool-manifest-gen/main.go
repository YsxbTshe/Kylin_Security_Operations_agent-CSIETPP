package main

import (
	"bytes"
	"crypto/ecdsa"
	"encoding/base64"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"strings"

	"github.com/cyberphone/json-canonicalization/go/src/webpki.org/jsoncanonicalizer"
	"github.com/emmansun/gmsm/sm2"
	"github.com/santhosh-tekuri/jsonschema/v6"

	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/gmcrypto"
	"gitee.com/SuperSkidder/kylin-security-ops-agent/agents/node-executor/internal/signing"
)

const schemaID = "https://kylinops.local/schemas/tool-package-manifest-1.0.json"

type manifest struct {
	SchemaVersion string     `json:"schemaVersion"`
	PackageID     string     `json:"packageId"`
	Version       string     `json:"version"`
	Title         string     `json:"title"`
	Description   string     `json:"description"`
	Tools         []tool     `json:"tools"`
	Signature     *signature `json:"signature,omitempty"`
}

type tool struct {
	ID                    string          `json:"id"`
	Version               string          `json:"version"`
	Title                 string          `json:"title"`
	Description           string          `json:"description"`
	Executor              string          `json:"executor"`
	InputSchema           json.RawMessage `json:"inputSchema"`
	OutputSchema          json.RawMessage `json:"outputSchema"`
	RiskFloor             string          `json:"riskFloor"`
	ReadOnly              bool            `json:"readOnly"`
	AlwaysRequireApproval bool            `json:"alwaysRequireApproval"`
	DirectDisplay         bool            `json:"directDisplay"`
	ResourceLocks         []resourceLock  `json:"resourceLocks"`
	TimeoutSeconds        int             `json:"timeoutSeconds"`
	MaxConcurrency        int             `json:"maxConcurrency"`
	Idempotency           idempotency     `json:"idempotency"`
	Reversibility         any             `json:"reversibility"`
	NodeRequirements      any             `json:"nodeRequirements"`
	Implementation        implementation  `json:"implementation"`
}

type resourceLock struct {
	Template string `json:"template"`
	Mode     string `json:"mode"`
}

type idempotency struct {
	Type        string `json:"type"`
	MaxAttempts int    `json:"maxAttempts"`
}

type implementation struct {
	Kind       string `json:"kind"`
	Entrypoint string `json:"entrypoint"`
	Digest     string `json:"digest"`
}

type signature struct {
	Algorithm        string `json:"algorithm"`
	KeyID            string `json:"keyId"`
	KeyPurpose       string `json:"keyPurpose"`
	UserID           string `json:"userId"`
	SignedPayloadSM3 string `json:"signedPayloadSm3"`
	DERBase64        string `json:"derBase64"`
}

type mcpProjection struct {
	PackageID      string    `json:"packageId"`
	PackageVersion string    `json:"packageVersion"`
	ManifestDigest string    `json:"manifestDigest"`
	Tools          []mcpTool `json:"tools"`
}

type mcpTool struct {
	Name         string          `json:"name"`
	Title        string          `json:"title"`
	Description  string          `json:"description"`
	InputSchema  json.RawMessage `json:"inputSchema"`
	OutputSchema json.RawMessage `json:"outputSchema"`
}

type nodeProjection struct {
	PluginName     string     `json:"pluginName"`
	Version        string     `json:"version"`
	ManifestDigest string     `json:"manifestDigest"`
	Tools          []nodeTool `json:"tools"`
}

type nodeTool struct {
	ToolName         string          `json:"toolName"`
	Handler          string          `json:"handler"`
	Description      string          `json:"description"`
	InputSchema      json.RawMessage `json:"inputSchema"`
	OutputSchema     json.RawMessage `json:"outputSchema"`
	RiskCode         string          `json:"riskCode"`
	RequiresApproval bool            `json:"requiresApproval"`
	ReadOnly         bool            `json:"readOnly"`
	ResourceLocks    []resourceLock  `json:"resourceLocks"`
}

type privhelperProjection struct {
	PackageID      string            `json:"packageId"`
	PackageVersion string            `json:"packageVersion"`
	ManifestDigest string            `json:"manifestDigest"`
	Operations     []privilegedEntry `json:"operations"`
}

type privilegedEntry struct {
	ToolID     string `json:"toolId"`
	Entrypoint string `json:"entrypoint"`
	RiskFloor  string `json:"riskFloor"`
}

func main() {
	mode := flag.String("mode", "verify", "sign or verify")
	repoRoot := flag.String("repo-root", "../..", "repository root")
	privateKeyPath := flag.String("private-key", "contracts/test-vectors/TEST_ONLY_sm2_private.pem", "SM2 PKCS#8 PEM, sign mode only")
	publicKeyPath := flag.String("public-key", "contracts/test-vectors/TEST_ONLY_sm2_public.pem", "SM2 public key PEM")
	keyID := flag.String("key-id", "catalog-test-1", "catalog signing key ID")
	flag.Parse()

	root, err := filepath.Abs(*repoRoot)
	check(err)
	sourcePath := filepath.Join(root, "tool-packages", "core-node-tools", "manifest.unsigned.json")
	signedPath := filepath.Join(root, "tool-packages", "core-node-tools", "manifest.json")
	schemaPath := filepath.Join(root, "tool-packages", "schema", "tool-package-manifest-1.0.schema.json")

	switch *mode {
	case "sign":
		unsigned := readManifest(sourcePath)
		if unsigned.Signature != nil {
			fatal("unsigned manifest must not contain signature")
		}
		fillImplementationDigests(&unsigned)
		validateSemantics(unsigned)
		payload := canonicalJSON(unsigned)
		key := readPrivateKey(resolvePath(root, *privateKeyPath))
		der, err := signing.Sign(key, signing.CatalogSigning, payload)
		check(err)
		unsigned.Signature = &signature{
			Algorithm: "SM2_WITH_SM3", KeyID: *keyID, KeyPurpose: string(signing.CatalogSigning),
			UserID: "KYLIN-CATALOG-V1", SignedPayloadSM3: gmcrypto.SM3HexBytes(payload),
			DERBase64: base64.StdEncoding.EncodeToString(der),
		}
		signed := canonicalJSON(unsigned)
		validateSchema(schemaPath, signed)
		check(writeAtomic(signedPath, signed))
		check(writeProjections(root, unsigned, signed, false))
	case "verify":
		signedBytes, err := os.ReadFile(signedPath)
		check(err)
		validateSchema(schemaPath, signedBytes)
		signed := readManifest(signedPath)
		validateSemantics(signed)
		publicKey := readPublicKey(resolvePath(root, *publicKeyPath))
		verifySignature(signed, publicKey)
		check(writeProjections(root, signed, canonicalJSON(signed), true))
	default:
		fatal("mode must be sign or verify")
	}
}

func readManifest(path string) manifest {
	data, err := os.ReadFile(path)
	check(err)
	var value manifest
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	decoder.DisallowUnknownFields()
	check(decoder.Decode(&value))
	return value
}

func fillImplementationDigests(value *manifest) {
	for i := range value.Tools {
		digest := strings.TrimSpace(value.Tools[i].Implementation.Digest)
		if digest == "" || digest == "pending" {
			item := value.Tools[i]
			value.Tools[i].Implementation.Digest = gmcrypto.SM3HexString(item.ID + ":" + item.Implementation.Entrypoint + ":" + item.Version)
		}
	}
}

func validateSemantics(value manifest) {
	if len(value.Tools) == 0 {
		fatal("manifest must contain tools")
	}
	ids := make([]string, 0, len(value.Tools))
	seen := map[string]bool{}
	for _, item := range value.Tools {
		if seen[item.ID] {
			fatal("duplicate tool ID: " + item.ID)
		}
		seen[item.ID] = true
		ids = append(ids, item.ID)
		if item.DirectDisplay && !item.ReadOnly {
			fatal("directDisplay tool must be read-only: " + item.ID)
		}
		if item.Idempotency.Type != "IDEMPOTENT" && item.Idempotency.MaxAttempts != 1 {
			fatal("non-idempotent tool must use maxAttempts=1: " + item.ID)
		}
		if item.ReadOnly && item.RiskFloor == "R5" {
			fatal("read-only tool cannot have R5 risk floor: " + item.ID)
		}
	}
	if !slices.IsSorted(ids) {
		fatal("tools must be ordered by ID for stable model context")
	}
}

func verifySignature(value manifest, publicKey *ecdsa.PublicKey) {
	if value.Signature == nil {
		fatal("manifest signature is required")
	}
	sig := value.Signature
	value.Signature = nil
	payload := canonicalJSON(value)
	if gmcrypto.SM3HexBytes(payload) != sig.SignedPayloadSM3 {
		fatal("manifest payload digest mismatch")
	}
	der, err := base64.StdEncoding.DecodeString(sig.DERBase64)
	check(err)
	if !signing.Verify(publicKey, signing.CatalogSigning, payload, der) {
		fatal("manifest signature is invalid")
	}
}

func validateSchema(schemaPath string, manifestJSON []byte) {
	var schemaDocument any
	check(json.Unmarshal(mustRead(schemaPath), &schemaDocument))
	compiler := jsonschema.NewCompiler()
	check(compiler.AddResource(schemaID, schemaDocument))
	schema, err := compiler.Compile(schemaID)
	check(err)
	var document any
	check(json.Unmarshal(manifestJSON, &document))
	check(schema.Validate(document))
}

func writeProjections(root string, value manifest, signedJSON []byte, checkOnly bool) error {
	digest := gmcrypto.SM3HexBytes(signedJSON)
	mcp := mcpProjection{PackageID: value.PackageID, PackageVersion: value.Version, ManifestDigest: digest}
	node := nodeProjection{PluginName: value.PackageID, Version: value.Version, ManifestDigest: digest}
	privhelper := privhelperProjection{PackageID: value.PackageID, PackageVersion: value.Version, ManifestDigest: digest, Operations: []privilegedEntry{}}
	for _, item := range value.Tools {
		mcp.Tools = append(mcp.Tools, mcpTool{Name: item.ID, Title: item.Title, Description: item.Description, InputSchema: item.InputSchema, OutputSchema: item.OutputSchema})
		if item.Executor == "NODE" {
			node.Tools = append(node.Tools, nodeTool{
				ToolName: item.ID, Handler: item.Implementation.Entrypoint, Description: item.Description,
				InputSchema: item.InputSchema, OutputSchema: item.OutputSchema, RiskCode: item.RiskFloor,
				RequiresApproval: item.AlwaysRequireApproval || !item.ReadOnly, ReadOnly: item.ReadOnly,
				ResourceLocks: item.ResourceLocks,
			})
			if !item.ReadOnly {
				privhelper.Operations = append(privhelper.Operations, privilegedEntry{ToolID: item.ID, Entrypoint: item.Implementation.Entrypoint, RiskFloor: item.RiskFloor})
			}
		}
	}
	outputs := map[string][]byte{
		filepath.Join(root, "services", "backend", "src", "main", "resources", "tool-catalog", value.PackageID+"-"+value.Version+".json"): signedJSON,
		filepath.Join(root, "services", "backend", "src", "main", "resources", "mcp-tools", value.PackageID+"-"+value.Version+".json"):    canonicalJSON(mcp),
		filepath.Join(root, "agents", "node-executor", "generated", "tool-catalog", value.PackageID+"-"+value.Version+".json"):            canonicalJSON(node),
		filepath.Join(root, "agents", "node-executor", "generated", "privhelper", value.PackageID+"-"+value.Version+".json"):              canonicalJSON(privhelper),
	}
	for path, expected := range outputs {
		if checkOnly {
			actual, err := os.ReadFile(path)
			if err != nil {
				return err
			}
			if !bytes.Equal(actual, append(append([]byte(nil), expected...), '\n')) {
				return errors.New("generated projection differs: " + path)
			}
		} else if err := writeAtomic(path, expected); err != nil {
			return err
		}
	}
	return nil
}

func canonicalJSON(value any) []byte {
	plain, err := json.Marshal(value)
	check(err)
	canonical, err := jsoncanonicalizer.Transform(plain)
	check(err)
	return canonical
}

func readPrivateKey(path string) *sm2.PrivateKey {
	key, err := signing.ParsePrivateKeyPEM(mustRead(path))
	check(err)
	return key
}

func mustRead(path string) []byte { data, err := os.ReadFile(path); check(err); return data }

func resolvePath(root, path string) string {
	if filepath.IsAbs(path) {
		return path
	}
	return filepath.Join(root, path)
}

func readPublicKey(path string) *ecdsa.PublicKey {
	key, err := signing.ParsePublicKeyPEM(mustRead(path))
	check(err)
	return key
}

func writeAtomic(path string, data []byte) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".manifest-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if _, err = temporary.Write(append(data, '\n')); err == nil {
		err = temporary.Sync()
	}
	if closeErr := temporary.Close(); err == nil {
		err = closeErr
	}
	if err != nil {
		return err
	}
	if err := os.Chmod(temporaryPath, 0o644); err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}

func check(err error) {
	if err != nil {
		fatal(err.Error())
	}
}
func fatal(message string) { fmt.Fprintln(os.Stderr, message); os.Exit(1) }
