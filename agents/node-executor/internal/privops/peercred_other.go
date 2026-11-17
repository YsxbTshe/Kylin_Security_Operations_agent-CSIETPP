//go:build !linux

package privops

import (
	"fmt"
	"net"
)

// PeerUID 在非 Linux 平台上无实现。
//
// 返回错误而不是返回一个「放行」的默认值：特权助手只在 Linux 目标机上运行，
// 若哪天有人在别的平台把它跑起来，必须是启动即失败，而不是静默地不做对端校验。
func PeerUID(net.Conn) (uint32, error) {
	return 0, fmt.Errorf("SO_PEERCRED 仅在 linux 上可用，特权助手不支持当前平台")
}
