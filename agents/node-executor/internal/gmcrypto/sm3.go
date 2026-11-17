package gmcrypto

import (
	"crypto/hmac"
	"encoding/hex"
	"hash"

	"github.com/emmansun/gmsm/sm3"
)

const SM3Algorithm = "SM3"

func NewSM3() hash.Hash {
	return sm3.New()
}

func SM3HexString(value string) string {
	return SM3HexBytes([]byte(value))
}

func SM3HexBytes(value []byte) string {
	sum := sm3.Sum(value)
	return hex.EncodeToString(sum[:])
}

// HMACSM3HexString 计算 HMAC-SM3(key, message) 的十六进制摘要。
// key 为空时退化为零长密钥（与服务端 BouncyCastle HMac(SM3) 的空密钥行为一致），
// 用于无密钥的本地演示；生产/演示应通过 KYLIN_APPROVAL_TOKEN_SECRET 配置非空密钥。
func HMACSM3HexString(key []byte, message string) string {
	mac := hmac.New(sm3.New, key)
	mac.Write([]byte(message))
	return hex.EncodeToString(mac.Sum(nil))
}
