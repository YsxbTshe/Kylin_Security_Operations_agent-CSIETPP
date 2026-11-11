//go:build linux

package protocol

import (
	"fmt"
	"net"
	"syscall"
)

// peerUID 取 unix socket 对端进程的 uid（SO_PEERCRED）。
//
// 与 node-executor 的 privops.PeerUID 同构：文件权限只能约束「谁能连上」，且依赖
// 部署时把属主设对；SO_PEERCRED 是内核在连接建立时记录的事实，进程无法伪造，
// 也不受 socket 文件被误改权限影响。
func peerUID(conn net.Conn) (uint32, error) {
	unixConn, ok := conn.(*net.UnixConn)
	if !ok {
		return 0, fmt.Errorf("对端不是 unix socket 连接")
	}
	raw, err := unixConn.SyscallConn()
	if err != nil {
		return 0, fmt.Errorf("获取连接文件描述符失败：%w", err)
	}
	var ucred *syscall.Ucred
	var sockErr error
	if err := raw.Control(func(fd uintptr) {
		ucred, sockErr = syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
	}); err != nil {
		return 0, fmt.Errorf("读取对端凭据失败：%w", err)
	}
	if sockErr != nil {
		return 0, fmt.Errorf("读取对端凭据失败：%w", sockErr)
	}
	return ucred.Uid, nil
}
