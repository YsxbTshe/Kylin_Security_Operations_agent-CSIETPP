//go:build !linux

package protocol

import (
	"fmt"
	"net"
)

// peerUID 在非 Linux 平台上无实现。
//
// 返回错误而不是返回一个「放行」的默认值：workspace-runner 只在 Linux 节点上运行
// （它要 rootless Podman），若哪天有人在别的平台把它跑起来，必须是每个连接都被拒，
// 而不是静默地不做对端校验。开发机上不配 KYLIN_WORKSPACE_PEER_USER 时校验整体关闭，
// 单测因此仍能在 macOS 上跑通。
func peerUID(net.Conn) (uint32, error) {
	return 0, fmt.Errorf("SO_PEERCRED 仅在 linux 上可用，当前平台无法确认对端身份")
}
