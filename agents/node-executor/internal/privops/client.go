package privops

import (
	"encoding/json"
	"fmt"
	"io"
	"net"
	"strings"
	"time"
)

// Client 是 daemon 侧的助手客户端。它不持有任何密钥，只负责把结构化请求
// 送到特权边界另一侧，并把结果带回来。
type Client struct {
	socketPath string
	timeout    time.Duration
}

func NewClient(socketPath string, timeout time.Duration) Client {
	if strings.TrimSpace(socketPath) == "" {
		socketPath = DefaultSocketPath
	}
	if timeout <= 0 {
		timeout = 30 * time.Second
	}
	return Client{socketPath: socketPath, timeout: timeout}
}

// Call 发送一次请求。每次调用一条连接、一来一回，无状态。
func (c Client) Call(request Request) (string, error) {
	deadline := time.Now().Add(c.timeout)
	conn, err := net.DialTimeout("unix", c.socketPath, c.timeout)
	if err != nil {
		// 助手不可达时必须失败，绝不能退回到「本地直接执行」——那等于绕过特权边界。
		return "", fmt.Errorf("特权助手不可达（%s）：%w", c.socketPath, err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(deadline)

	payload, err := json.Marshal(request)
	if err != nil {
		return "", fmt.Errorf("编码特权请求失败：%w", err)
	}
	if _, err := conn.Write(append(payload, '\n')); err != nil {
		return "", fmt.Errorf("发送特权请求失败：%w", err)
	}
	// 半关闭写方向，让助手知道请求已完整。
	if unixConn, ok := conn.(*net.UnixConn); ok {
		_ = unixConn.CloseWrite()
	}

	raw, err := io.ReadAll(io.LimitReader(conn, maxMessageBytes))
	if err != nil {
		return "", fmt.Errorf("读取特权应答失败：%w", err)
	}
	var response Response
	if err := json.Unmarshal(raw, &response); err != nil {
		return "", fmt.Errorf("解析特权应答失败：%w", err)
	}
	if strings.TrimSpace(response.Error) != "" {
		return response.Output, fmt.Errorf("%s", response.Error)
	}
	return response.Output, nil
}
