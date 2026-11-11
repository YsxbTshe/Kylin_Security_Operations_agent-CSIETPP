package controlstreamv2

import (
	"crypto/rand"
	"crypto/rsa"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"os"
	"path/filepath"
)

type CertStore struct {
	Dir string
}

type CertificateMetadata struct {
	Subject      string
	ExpiresUnix  int64
	SerialNumber string
}

func (s CertStore) NodeCertPath() string { return filepath.Join(s.Dir, "node.crt") }
func (s CertStore) NodeKeyPath() string  { return filepath.Join(s.Dir, "node.key") }
func (s CertStore) CACertPath() string   { return filepath.Join(s.Dir, "ca.crt") }

func (s CertStore) Ready() bool {
	return exists(s.NodeCertPath()) && exists(s.NodeKeyPath()) && exists(s.CACertPath())
}

func (s CertStore) GenerateCSR(nodeID string, organizationID string, hostname string) (string, error) {
	if err := os.MkdirAll(s.Dir, 0700); err != nil {
		return "", err
	}
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		return "", err
	}
	keyBytes := x509.MarshalPKCS1PrivateKey(key)
	if err := os.WriteFile(s.NodeKeyPath(), pem.EncodeToMemory(&pem.Block{Type: "RSA PRIVATE KEY", Bytes: keyBytes}), 0600); err != nil {
		return "", err
	}
	csrBytes, err := x509.CreateCertificateRequest(rand.Reader, &x509.CertificateRequest{Subject: pkix.Name{CommonName: nodeID, Organization: []string{organizationID}, OrganizationalUnit: []string{hostname}}}, key)
	if err != nil {
		return "", err
	}
	return string(pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE REQUEST", Bytes: csrBytes})), nil
}

func (s CertStore) Save(nodeCertPEM string, caCertPEM string) error {
	if err := os.MkdirAll(s.Dir, 0700); err != nil {
		return err
	}
	if err := os.WriteFile(s.NodeCertPath(), []byte(nodeCertPEM), 0644); err != nil {
		return err
	}
	if err := os.WriteFile(s.CACertPath(), []byte(caCertPEM), 0644); err != nil {
		return err
	}
	return nil
}

func (s CertStore) Clear() error {
	for _, path := range []string{s.NodeCertPath(), s.NodeKeyPath(), s.CACertPath()} {
		if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	return nil
}

func (s CertStore) CertificateInfo() (string, int64, error) {
	metadata, err := s.CertificateMetadata()
	if err != nil {
		return "", 0, err
	}
	return metadata.Subject, metadata.ExpiresUnix, nil
}

func (s CertStore) CertificateMetadata() (CertificateMetadata, error) {
	data, err := os.ReadFile(s.NodeCertPath())
	if err != nil {
		return CertificateMetadata{}, err
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return CertificateMetadata{}, fmt.Errorf("invalid node certificate PEM")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return CertificateMetadata{}, err
	}
	return CertificateMetadata{
		Subject:      cert.Subject.String(),
		ExpiresUnix:  cert.NotAfter.Unix(),
		SerialNumber: cert.SerialNumber.Text(16),
	}, nil
}

func exists(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}
