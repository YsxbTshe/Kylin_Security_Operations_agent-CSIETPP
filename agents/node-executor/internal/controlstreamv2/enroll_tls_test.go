package controlstreamv2

import (
	"bytes"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"log"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// enroll 是信任链的起点：不校验对端就等于把 bootstrap token 和后续 mTLS 的信任锚
// 一起交给任何能拦截这条连接的人。默认必须拒绝，而不是静默 TOFU。
func TestEnrollTLSConfigRefusesWhenNoPlatformIdentityConfigured(t *testing.T) {
	clearEnrollEnv(t)

	cfg, err := enrollTLSConfig()
	if err == nil {
		t.Fatalf("expected enroll to be refused without CA配置, got config %+v", cfg)
	}
	if cfg != nil {
		t.Fatalf("expected nil config on refusal, got %+v", cfg)
	}
	for _, want := range []string{envEnrollCAFile, envEnrollCAPin, envEnrollInsecureTOFU} {
		if !strings.Contains(err.Error(), want) {
			t.Fatalf("error must tell ops which variable to set; %q missing from %v", want, err)
		}
	}
}

// 逃生舱关闭（显式 false / 无法解析）时同样拒绝——只有显式 true 才放行。
func TestEnrollTLSConfigRefusesWhenInsecureSwitchIsNotExplicitlyTrue(t *testing.T) {
	for _, value := range []string{"", "false", "0", "no-thanks", "TRUEish"} {
		clearEnrollEnv(t)
		t.Setenv(envEnrollInsecureTOFU, value)
		if _, err := enrollTLSConfig(); err == nil {
			t.Fatalf("%s=%q must not enable insecure mode", envEnrollInsecureTOFU, value)
		}
	}
}

func TestEnrollTLSConfigUsesCAFile(t *testing.T) {
	clearEnrollEnv(t)
	certPEM, _ := selfSignedCertificate(t)
	caPath := filepath.Join(t.TempDir(), "ca.crt")
	if err := os.WriteFile(caPath, certPEM, 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv(envEnrollCAFile, caPath)

	cfg, err := enrollTLSConfig()
	if err != nil {
		t.Fatalf("enrollTLSConfig() error = %v", err)
	}
	if cfg.InsecureSkipVerify {
		t.Fatal("CA 文件模式必须走标准链校验，不能 InsecureSkipVerify")
	}
	if cfg.RootCAs == nil {
		t.Fatal("expected RootCAs to be populated from the CA file")
	}
	if cfg.VerifyPeerCertificate != nil {
		t.Fatal("只配 CA 文件时不应挂指纹回调")
	}
	if cfg.MinVersion != tls.VersionTLS12 {
		t.Fatalf("expected TLS 1.2 minimum, got %#x", cfg.MinVersion)
	}
}

func TestEnrollTLSConfigRejectsUnreadableOrInvalidCAFile(t *testing.T) {
	clearEnrollEnv(t)
	t.Setenv(envEnrollCAFile, filepath.Join(t.TempDir(), "missing.crt"))
	if _, err := enrollTLSConfig(); err == nil {
		t.Fatal("expected error for missing CA file")
	}

	garbage := filepath.Join(t.TempDir(), "garbage.crt")
	if err := os.WriteFile(garbage, []byte("not a certificate"), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv(envEnrollCAFile, garbage)
	if _, err := enrollTLSConfig(); err == nil {
		t.Fatal("expected error for non-PEM CA file")
	}
}

func TestEnrollTLSConfigPinsServerCertificateFingerprint(t *testing.T) {
	clearEnrollEnv(t)
	_, der := selfSignedCertificate(t)
	sum := sha256.Sum256(der)
	// 带冒号、大写的指纹是运维从 openssl 直接粘贴的常见形态，必须能接受。
	t.Setenv(envEnrollCAPin, strings.ToUpper(colonize(hex.EncodeToString(sum[:]))))

	cfg, err := enrollTLSConfig()
	if err != nil {
		t.Fatalf("enrollTLSConfig() error = %v", err)
	}
	if !cfg.InsecureSkipVerify {
		t.Fatal("只配指纹时没有信任锚，必须由回调接管校验")
	}
	if cfg.VerifyPeerCertificate == nil {
		t.Fatal("expected a pin verification callback")
	}
	if err := cfg.VerifyPeerCertificate([][]byte{der}, nil); err != nil {
		t.Fatalf("matching certificate must pass the pin: %v", err)
	}
	_, otherDER := selfSignedCertificate(t)
	if err := cfg.VerifyPeerCertificate([][]byte{otherDER}, nil); err == nil {
		t.Fatal("a different certificate must fail the pin")
	}
	if err := cfg.VerifyPeerCertificate(nil, nil); err == nil {
		t.Fatal("an empty certificate chain must fail the pin")
	}
}

func TestEnrollTLSConfigRejectsMalformedFingerprint(t *testing.T) {
	for _, pin := range []string{"deadbeef", strings.Repeat("z", 64)} {
		clearEnrollEnv(t)
		t.Setenv(envEnrollCAPin, pin)
		if _, err := enrollTLSConfig(); err == nil {
			t.Fatalf("expected malformed fingerprint %q to be rejected", pin)
		}
	}
}

// 两个变量同时配置时，链校验与指纹钉扎必须同时生效；只生效一个比不配更危险。
func TestEnrollTLSConfigCombinesCAFileAndFingerprint(t *testing.T) {
	clearEnrollEnv(t)
	certPEM, der := selfSignedCertificate(t)
	caPath := filepath.Join(t.TempDir(), "ca.crt")
	if err := os.WriteFile(caPath, certPEM, 0o600); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(der)
	t.Setenv(envEnrollCAFile, caPath)
	t.Setenv(envEnrollCAPin, hex.EncodeToString(sum[:]))

	cfg, err := enrollTLSConfig()
	if err != nil {
		t.Fatalf("enrollTLSConfig() error = %v", err)
	}
	if cfg.InsecureSkipVerify {
		t.Fatal("配了 CA 文件就必须保留链校验")
	}
	if cfg.RootCAs == nil || cfg.VerifyPeerCertificate == nil {
		t.Fatal("两个变量都配置时，RootCAs 与指纹回调都要生效")
	}
	if err := cfg.VerifyPeerCertificate([][]byte{der}, nil); err != nil {
		t.Fatalf("matching certificate must pass the pin: %v", err)
	}
	_, otherDER := selfSignedCertificate(t)
	if err := cfg.VerifyPeerCertificate([][]byte{otherDER}, nil); err == nil {
		t.Fatal("a different certificate must fail the pin even when a CA file is configured")
	}
}

// 逃生舱是显式选择，但每次连接都要在日志里留下痕迹——不做去重、不降级为 debug。
func TestEnrollTLSConfigInsecureEscapeHatchWarnsOnEveryConnection(t *testing.T) {
	clearEnrollEnv(t)
	t.Setenv(envEnrollInsecureTOFU, "true")

	var logs bytes.Buffer
	restore := captureLog(t, &logs)
	defer restore()

	for attempt := 0; attempt < 3; attempt++ {
		cfg, err := enrollTLSConfig()
		if err != nil {
			t.Fatalf("explicit escape hatch must be allowed: %v", err)
		}
		if !cfg.InsecureSkipVerify {
			t.Fatal("insecure TOFU mode must set InsecureSkipVerify")
		}
	}

	warnings := strings.Count(logs.String(), "SECURITY WARNING")
	if warnings != 3 {
		t.Fatalf("expected one warning per connection attempt, got %d in:\n%s", warnings, logs.String())
	}
	for _, want := range []string{envEnrollInsecureTOFU, "InsecureSkipVerify", "TOFU"} {
		if !strings.Contains(logs.String(), want) {
			t.Fatalf("warning must name %q; got:\n%s", want, logs.String())
		}
	}
}

func clearEnrollEnv(t *testing.T) {
	t.Helper()
	for _, key := range []string{envEnrollCAFile, envEnrollCAPin, envEnrollInsecureTOFU, "KYLIN_ENROLL_SERVER_NAME"} {
		t.Setenv(key, "")
		if err := os.Unsetenv(key); err != nil {
			t.Fatal(err)
		}
	}
}

func captureLog(t *testing.T, buffer *bytes.Buffer) func() {
	t.Helper()
	previousOutput := log.Writer()
	previousFlags := log.Flags()
	log.SetOutput(buffer)
	log.SetFlags(0)
	return func() {
		log.SetOutput(previousOutput)
		log.SetFlags(previousFlags)
	}
}

func selfSignedCertificate(t *testing.T) (pemBytes []byte, der []byte) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 96))
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "localhost"},
		NotBefore:             time.Now().Add(-time.Minute),
		NotAfter:              time.Now().Add(time.Hour),
		BasicConstraintsValid: true,
		IsCA:                  true,
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature,
		DNSNames:              []string{"localhost"},
	}
	der, err = x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), der
}

func colonize(value string) string {
	parts := make([]string, 0, len(value)/2)
	for index := 0; index+2 <= len(value); index += 2 {
		parts = append(parts, value[index:index+2])
	}
	return strings.Join(parts, ":")
}
